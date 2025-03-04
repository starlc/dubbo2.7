/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.transport.netty4;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.transport.netty4.SslHandlerInitializer.HandshakeCompletionEvent;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.timeout.IdleStateEvent;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NettyServerHandler.
 * NettyServerHandler，它继承了 ChannelDuplexHandler，
 * 这是 Netty 提供的一个同时处理 Inbound 数据和 Outbound 数据的 ChannelHandler
 *
 * 有 channels 和 handler 两个核心字段
 *
 *
 * 在 NettyServer 创建 NettyServerHandler 的时候，可以看到下面的这行代码：
 *
 * final NettyServerHandler nettyServerHandler = new NettyServerHandler(getUrl(), this);
 * 其中第二个参数传入的是 NettyServer 这个对象，你可以追溯一下 NettyServer 的继承结构，
 * 会发现它的最顶层父类 AbstractPeer 实现了 ChannelHandler，并且将所有的方法委托给其中封装的
 * ChannelHandler 对象
 * 也就是说，NettyServerHandler 会将数据委托给这个 ChannelHandler。
 *
 * NettyServerHandler (网络事件处理)
 *   ↓
 * MultiMessageHandler (处理多消息)
 *   ↓
 * HeartbeatHandler (心跳检测)
 *   ↓
 * AllChannelHandler (线程池分发)
 *   ↓
 * DecodeHandler (消息解码)
 *   ↓
 * HeaderExchangeHandler (请求响应处理)
 *   ↓
 * DubboProtocol$ExchangeHandler (协议层处理)
 */
@io.netty.channel.ChannelHandler.Sharable
public class NettyServerHandler extends ChannelDuplexHandler {
    private static final Logger logger = LoggerFactory.getLogger(NettyServerHandler.class);
    /**
     * the cache for alive worker channel.
     * <ip:port, dubbo channel>
     *  记录了当前 Server 创建的所有 Channel，从下图中可以看到，连接创建（触发 channelActive() 方法）、
     *  连接断开（触发 channelInactive()方法）会操作 channels 集合进行相应的增删。
     */
    private final Map<String, Channel> channels = new ConcurrentHashMap<String, Channel>();

    private final URL url;

    /**
     * NettyServerHandler 内几乎所有方法都会触发该 Dubbo ChannelHandler 对象
     */
    private final ChannelHandler handler;

    public NettyServerHandler(URL url, ChannelHandler handler) {
        if (url == null) {
            throw new IllegalArgumentException("url == null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler == null");
        }
        this.url = url;
        this.handler = handler;
    }

    public Map<String, Channel> getChannels() {
        return channels;
    }

    /**
     * - 当新连接建立时被调用
     * - 将新连接包装成 NettyChannel 并存入 channels 集合
     * - 触发 connected 事件通知上层处理器
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        if (channel != null) {
            //put方法
            channels.put(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()), channel);
        }
        handler.connected(channel);

        if (logger.isInfoEnabled()) {
            logger.info("The connection of " + channel.getRemoteAddress() + " -> " + channel.getLocalAddress() + " is established.");
        }
    }

    /**
     * - 当连接断开时被调用
     * - 从 channels 集合中移除对应的 channel
     * - 触发 disconnected 事件通知上层处理器
     * @param ctx
     * @throws Exception
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            //从集合中移出
            channels.remove(NetUtils.toAddressString((InetSocketAddress) ctx.channel().remoteAddress()));
            handler.disconnected(channel);
        } finally {
            NettyChannel.removeChannel(ctx.channel());
        }

        if (logger.isInfoEnabled()) {
            logger.info("The connection of " + channel.getRemoteAddress() + " -> " + channel.getLocalAddress() + " is disconnected.");
        }
    }

    /**
     * 服务端接收消费端消息的入口
     * - 当 Netty 接收到消息时，会首先调用 channelRead 方法
     * - 该方法是 Netty 的标准入口点，用于处理所有入站消息
     *
     * NettyServerHandler
     * -> MultiMessageHandler
     * -> HeartbeatHandler
     * -> AllChannelHandler  ChannelHandlerDispatcher
     * -> DecodeHandler
     * -> HeaderExchangeHandler
     * -> DubboProtocol$ExchangeHandler
     *
     * 从创建nettyServer的逻辑可以反向推断出要经过上述的handler
     * @param ctx
     * @param msg
     * @throws Exception
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        //the handler will be wrapped:NettyServerHandler-> MultiMessageHandler->HeartbeatHandler->handler
        //再看最后的handler来源
        //Transporters.bind(URL,ChannelHandler...) ChannelHandlerDispatcher
        //new DecodeHandler(new HeaderExchangeHandler(handler)))
        handler.received(channel, msg);
    }


    /**
     * - 消息发送时的处理方法
     * - 触发 sent 事件通知上层处理器
     * - 不影响消息继续发送
     * NettyServerHandler.sent()
     * -> MultiMessageHandler.sent()
     * -> HeartbeatHandler.sent()
     * -> AllChannelHandler.sent()
     * -> DecodeHandler.sent()
     * -> HeaderExchangeHandler.sent()
     * -> DubboProtocol$ExchangeHandlerAdapter.sent()
     * @param ctx
     * @param msg
     * @param promise
     * @throws Exception
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);// 将发送的数据继续向下传递
        // 并不影响消息的继续发送，只是触发sent()方法进行相关的处理，这也是方法
        // 名称是动词过去式的原因，可以仔细体会一下。其他方法可能没有那么明显，
        // 这里以write()方法为例进行说明
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        handler.sent(channel, msg);
    }

    /**
     * - 处理用户自定义事件
     * - 主要用于处理空闲连接检测事件(IdleStateEvent)
     * - 在超时时关闭空闲连接
     *
     * 当连接空闲时间超过设定值，会触发 IdleStateEvent 事件
     * 服务端默认空闲超时时间为 3 分钟
     * 可通过 server-idle-minutes 参数配置
     * 超时后会触发 IdleStateEvent，然后断开连接
     * @param ctx
     * @param evt
     * @throws Exception
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // server will close channel when server don't receive any heartbeat from client util timeout.
        //NettyServerHandler 在收到 IdleStateEvent 事件时会断开连接，而 NettyClientHandler 则会发送心跳消息
        if (evt instanceof IdleStateEvent) {
            NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
            try {
                logger.info("IdleStateEvent triggered, close channel " + channel);
                channel.close();
            } finally {
                NettyChannel.removeChannelIfDisconnected(ctx.channel());
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * - 异常处理方法
     * - 将异常传递给上层处理器
     * - 清理异常连接的资源
     * @param ctx
     * @param cause
     * @throws Exception
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        NettyChannel channel = NettyChannel.getOrAddChannel(ctx.channel(), url, handler);
        try {
            handler.caught(channel, cause);
        } finally {
            NettyChannel.removeChannelIfDisconnected(ctx.channel());
        }
    }

    public void handshakeCompleted(HandshakeCompletionEvent evt) {
        // TODO
    }
}
