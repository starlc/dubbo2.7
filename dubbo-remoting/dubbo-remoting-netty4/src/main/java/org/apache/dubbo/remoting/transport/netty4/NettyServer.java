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
import org.apache.dubbo.common.utils.ExecutorUtil;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.transport.AbstractServer;
import org.apache.dubbo.remoting.transport.dispatcher.ChannelHandlers;
import org.apache.dubbo.remoting.utils.UrlUtils;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.dubbo.common.constants.CommonConstants.IO_THREADS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.KEEP_ALIVE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SSL_ENABLED_KEY;


/**
 * NettyServer.
 */
public class NettyServer extends AbstractServer implements RemotingServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);
    /**
     * the cache for alive worker channel.
     * <ip:port, dubbo channel>
     */
    private Map<String, Channel> channels;
    /**
     * netty server bootstrap.
     */
    private ServerBootstrap bootstrap;
    /**
     * the boss channel that receive connections and dispatch these to worker channel.
     */
	private io.netty.channel.Channel channel;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public NettyServer(URL url, ChannelHandler handler) throws RemotingException {
        // you can customize name and type of client thread pool by THREAD_NAME_KEY and THREADPOOL_KEY in CommonConstants.
        // the handler will be wrapped: MultiMessageHandler->HeartbeatHandler->AllChannelHandler->DecodeHandler->HeadExchangerHandler
        //我们可以在创建 NettyServerHandler 的地方添加断点 Debug 得到下图，也印证了上图的内容
        super(ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME), ChannelHandlers.wrap(handler, url));
    }

    /**
     * Init and start netty server
     *
     * @throws Throwable
     */
    @Override
    protected void doOpen() throws Throwable {
        // 创建ServerBootstrap
        bootstrap = new ServerBootstrap();
        // 创建boss EventLoopGroup
        bossGroup = createBossGroup();

        // 创建worker EventLoopGroup
        workerGroup = createWorkerGroup();
        // 创建NettyServerHandler，它是一个Netty中的ChannelHandler实现，
        // 不是Dubbo Remoting层的ChannelHandler接口的实现 Netty ChannelDuplexHandler的实现类
        final NettyServerHandler nettyServerHandler = createNettyServerHandler();
        // 获取当前NettyServer创建的所有Channel，这里的channels集合中的
        // Channel不是Netty中的Channel对象，而是Dubbo Remoting层的Channel对象
        channels = nettyServerHandler.getChannels();

        initServerBootstrap(nettyServerHandler);

        // bind
        // 绑定指定的地址和端口
        ChannelFuture channelFuture = bootstrap.bind(getBindAddress());
        channelFuture.syncUninterruptibly(); // 等待bind操作完成
        channel = channelFuture.channel();

    }

    protected EventLoopGroup createBossGroup() {
        return NettyEventLoopFactory.eventLoopGroup(1, "NettyServerBoss");
    }

    protected EventLoopGroup createWorkerGroup() {
        return NettyEventLoopFactory.eventLoopGroup(
                getUrl().getPositiveParameter(IO_THREADS_KEY, Constants.DEFAULT_IO_THREADS),
                "NettyServerWorker");
    }

    protected NettyServerHandler createNettyServerHandler() {
        return new NettyServerHandler(getUrl(), this);
    }

    protected void initServerBootstrap(NettyServerHandler nettyServerHandler) {
        boolean keepalive = getUrl().getParameter(KEEP_ALIVE_KEY, Boolean.FALSE);
        // 初始化ServerBootstrap，指定boss和worker EventLoopGroup
        bootstrap.group(bossGroup, workerGroup)
                .channel(NettyEventLoopFactory.serverSocketChannelClass())
                /**
                 *SO_REUSEADDR (服务端选项) ：
                 * - 允许在同一端口上启动同一个服务器的多个实例
                 * - 当一个服务端进程关闭后，操作系统会等待一段时间才释放端口
                 * - 启用此选项可以立即重用处于 TIME_WAIT 状态的端口
                 */
                .option(ChannelOption.SO_REUSEADDR, Boolean.TRUE)
                /**
                 * TCP_NODELAY (客户端选项) ：
                 * - 禁用 Nagle 算法
                 * - 不会将小包组合成大包发送
                 * - 提高实时性，降低延迟
                 * - 适合于 RPC 这样的请求-响应模式通信
                 * - 适合于 RPC 这样的请求-响应模式通信
                 * 3. SO_KEEPALIVE (客户端选项) ：
                 * - 启用 TCP keepalive 机制
                 * - 定期检测连接是否存活
                 * - 可以检测出死连接
                 * - 由 URL 参数中的 keepalive 参数控制
                 *
                 * 4. ALLOCATOR (客户端选项) ：
                 * - 设置 ByteBuf 的分配器
                 * - 使用池化的 ByteBuf 分配器
                 * - 可以重用 ByteBuf，减少内存分配和 GC 压力
                 * - 提高性能，特别是在高并发场景下
                 */
                .childOption(ChannelOption.TCP_NODELAY, Boolean.TRUE)
                .childOption(ChannelOption.SO_KEEPALIVE, keepalive)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        // FIXME: should we use getTimeout()?
                        // 连接空闲超时时间 默认3分钟
                        int idleTimeout = UrlUtils.getIdleTimeout(getUrl());
                        // NettyCodecAdapter中会创建Decoder和Encoder
                        NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyServer.this);
                        if (getUrl().getParameter(SSL_ENABLED_KEY, false)) {
                            ch.pipeline().addLast("negotiation",
                                    SslHandlerInitializer.sslServerHandler(getUrl(), nettyServerHandler));
                        }
                        ch.pipeline()
                                // 注册Decoder和Encoder
                                .addLast("decoder", adapter.getDecoder())//ByteToMessageDecoder extends ChannelInboundHandlerAdapter
                                .addLast("encoder", adapter.getEncoder())//MessageToByteEncoder<I> extends ChannelOutboundHandlerAdapter
                                // 注册IdleStateHandler 是 Netty 提供的一个工具型 ChannelHandler，用于定时心跳请求的功能或是自动关闭长时间空闲连接的功能。
                                .addLast("server-idle-handler", new IdleStateHandler(0, 0, idleTimeout, MILLISECONDS))
                                // 注册NettyServerHandler
                                .addLast("handler", nettyServerHandler);
                    }

                    /**
                     * IdleStateHandler它是 Netty 提供的一个工具型 ChannelHandler，
                     * 用于定时心跳请求的功能或是自动关闭长时间空闲连接的功能。
                     * 它的原理到底是怎样的呢？
                     * 在 IdleStateHandler 中通过 lastReadTime、lastWriteTime 等几个字段，
                     * 记录了最近一次读/写事件的时间，IdleStateHandler 初始化的时候，会创建一个定时任务，
                     * 定时检测当前时间与最后一次读/写时间的差值。
                     * 如果超过我们设置的阈值（也就是上面 NettyServer 中设置的 idleTimeout），
                     * 就会触发 IdleStateEvent 事件，并传递给后续的 ChannelHandler 进行处理。
                     * 后续 ChannelHandler 的 userEventTriggered() 方法会根据接收到的 IdleStateEvent 事件，
                     * 决定是关闭长时间空闲的连接，还是发送心跳探活。
                     */

                });

        /**
         * 网络数据 -> InternalDecoder.decode (Netty层)
         *             将字节转换为 Message 对象
         *               ↓
         *           NettyServerHandler (Netty层转Dubbo层)
         *               ↓
         *           DecodeHandler.decode (Dubbo层)
         *             处理消息体解码
         */
    }

    @Override
    protected void doClose() throws Throwable {
        try {
            if (channel != null) {
                // unbind.
                channel.close();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            Collection<Channel> channels = getChannels();
            if (channels != null && channels.size() > 0) {
                for (Channel channel : channels) {
                    try {
                        channel.close();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (bootstrap != null) {
                bossGroup.shutdownGracefully().syncUninterruptibly();
                workerGroup.shutdownGracefully().syncUninterruptibly();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (channels != null) {
                channels.clear();
            }
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public Collection<Channel> getChannels() {
        Collection<Channel> chs = new ArrayList<>(this.channels.size());
        chs.addAll(this.channels.values());
        // check of connection status is unnecessary since we are using channels in NettyServerHandler
//        for (Channel channel : this.channels.values()) {
//            if (channel.isConnected()) {
//                chs.add(channel);
//            } else {
//                channels.remove(NetUtils.toAddressString(channel.getRemoteAddress()));
//            }
//        }
        return chs;
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return channels.get(NetUtils.toAddressString(remoteAddress));
    }

    @Override
    public boolean canHandleIdle() {
        return true;
    }

    @Override
    public boolean isBound() {
        return channel.isActive();
    }

    protected EventLoopGroup getBossGroup() {
        return bossGroup;
    }

    protected EventLoopGroup getWorkerGroup() {
        return workerGroup;
    }

    protected ServerBootstrap getServerBootstrap() {
        return bootstrap;
    }

    protected io.netty.channel.Channel getBossChannel() {
        return channel;
    }

    protected Map<String, Channel> getServerChannels() {
        return channels;
    }
}
