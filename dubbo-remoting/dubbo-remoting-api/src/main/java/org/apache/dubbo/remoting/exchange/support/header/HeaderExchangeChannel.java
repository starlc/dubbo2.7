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
package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * ExchangeReceiver
 * 是 ExchangeChannel 的实现，它本身是 Channel 的装饰器，封装了一个 Channel 对象，
 * 其 send() 方法和 request() 方法的实现都是依赖底层修饰的这个 Channel 对象实现的
 */
final class HeaderExchangeChannel implements ExchangeChannel {

    private static final Logger logger = LoggerFactory.getLogger(HeaderExchangeChannel.class);

    private static final String CHANNEL_KEY = HeaderExchangeChannel.class.getName() + ".CHANNEL";

    private final Channel channel;

    private volatile boolean closed = false;

    HeaderExchangeChannel(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("channel == null");
        }
        this.channel = channel;
    }

    static HeaderExchangeChannel getOrAddChannel(Channel ch) {
        if (ch == null) {
            return null;
        }
        HeaderExchangeChannel ret = (HeaderExchangeChannel) ch.getAttribute(CHANNEL_KEY);
        if (ret == null) {
            ret = new HeaderExchangeChannel(ch);
            if (ch.isConnected()) {
                ch.setAttribute(CHANNEL_KEY, ret);
            }
        }
        return ret;
    }

    static void removeChannelIfDisconnected(Channel ch) {
        if (ch != null && !ch.isConnected()) {
            ch.removeAttribute(CHANNEL_KEY);
        }
    }

    static void removeChannel(Channel ch) {
        if (ch != null) {
            ch.removeAttribute(CHANNEL_KEY);
        }
    }

    @Override
    public void send(Object message) throws RemotingException {
        send(message, false);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null,
                    "Failed to send message " + message + ", cause: The channel " + this + " is closed!");
        }
        if (message instanceof Request
                || message instanceof Response
                || message instanceof String) {
            //实现都是依赖底层修饰的这个 Channel 对象实现的
            channel.send(message, sent);
        } else {
            /**
             * 这段代码处理的是普通消息的发送逻辑。
             * 当消息不是 Request、Response 或 String 类型时，会将其封装成一个单向的 Request 对象
             * 心跳消息 事件通知 单向的业务消息
             */
            Request request = new Request();
            request.setVersion(Version.getProtocolVersion());
            request.setTwoWay(false);
            request.setData(message);
            channel.send(request, sent);
        }
    }

    @Override
    public CompletableFuture<Object> request(Object request) throws RemotingException {
        return request(request, null);
    }

    @Override
    public CompletableFuture<Object> request(Object request, int timeout) throws RemotingException {
        return request(request, timeout, null);
    }

    @Override
    public CompletableFuture<Object> request(Object request, ExecutorService executor) throws RemotingException {
        return request(request, channel.getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT), executor);
    }

    /**
     * 返回的是一个 DefaultFuture 对象。通过前面课时的介绍我们知道，
     * io.netty.channel.Channel 的 send() 方法会返回一个 ChannelFuture 方法，
     * 表示此次发送操作是否完成，而这里的DefaultFuture 就表示此次请求-响应是否完成，也就是说，要收到响应为 Future 才算完成。
     * @param request
     * @param timeout
     * @param executor
     * @return
     * @throws RemotingException
     */
    @Override
    public CompletableFuture<Object> request(Object request, int timeout, ExecutorService executor) throws RemotingException {
        if (closed) {
            throw new RemotingException(this.getLocalAddress(), null,
                    "Failed to send request " + request + ", cause: The channel " + this + " is closed!");
        }
        // create request.
        // 创建Request对象
        Request req = new Request();
        req.setVersion(Version.getProtocolVersion());
        req.setTwoWay(true);
        req.setData(request);
        // 创建DefaultFuture
        DefaultFuture future = DefaultFuture.newFuture(channel, req, timeout, executor);
        try {
            //将请求通过底层的 Dubbo Channel 发送出去，发送过程中会触发沿途 ChannelHandler 的 sent() 方法，
            // 其中的 HeaderExchangeHandler 会调用 DefaultFuture.sent() 方法更新 sent 字段，记录请求发送的时间戳。
            // 见NettyServerHandler.write 中消息发送出去后 就会调用sent
            //HeartbeatHandler.sent
            // 后续如果响应超时，则会将该发送时间戳添加到提示信息中。
            channel.send(req);
        } catch (RemotingException e) {
            future.cancel();
            throw e;
        }
        /**
         * 上述代码中，future似乎和channel.send 没有产生半点联系，为什么要返回future呢？
         * 1、在创建 DefaultFuture 时，会将 future 与请求ID关联存储
         *   DefaultFuture future = DefaultFuture.newFuture(channel, req, timeout, executor);
         *   DefaultFuture 内部会维护映射关系 reqId->future reqId->channel
         *   FUTURES.put(req.getId(), future);
         *   CHANNELS.put(id, channel);
         *
         * 2、请求处理阶段
         * // 当收到响应时，会根据响应中的请求ID找到对应的 future
         * HeaderExchangeHandler#received(Channel, Object)->handleResponse()->DefaultFuture.received(channel, response);
         * ->future.doReceived(response);->this.complete(res.getResult());
         * 利用了CompletableFuture#complete(java.lang.Object) 将当前future标记为完成。
         * 这样在其他地方就可以通过future获取完成后的结果。也会触发 完成后的其他方法 比如thenApply等
         *
         * 3、响应处理机制
         * HeaderExchangeHandler#handleRequest(ExchangeChannel, Request)
         * // 处理请求并返回结果
         *         CompletionStage<Object> future = handler.reply(channel, msg);
         *         future.whenComplete((appResult, t) -> {
         *             if (t == null) {
         *                 res.setStatus(Response.OK);
         *                 res.setResult(appResult);
         *             } else {
         *                 res.setStatus(Response.SERVICE_ERROR);
         *             }
         *             // 发送响应
         *             channel.send(res);
         *         });
         *
         * 4、Future完成阶段 - 在 `DefaultFuture.java` 中：
         * public static void received(Channel channel, Response response) {
         *     // 1. 根据响应ID获取对应的future
         *     DefaultFuture future = FUTURES.remove(response.getId());
         *     if (future != null) {
         *         // 2. 设置响应结果
         *         future.doReceived(response);
         *     }
         * }
         *
         * private void doReceived(Response res) {
         *     if (res.getStatus() == Response.OK) {
         *         // 3. 设置future完成
         *         this.complete(res.getResult());
         *     } else {
         *         // 4. 设置异常完成
         *         this.completeExceptionally(new RemotingException(...));
         *     }
         * }
         * 5、Future使用方式 ：
         * - 同步调用： Object result = future.get(timeout, TimeUnit.MILLISECONDS);
         * - 异步调用：
         * ```java
         * future.whenComplete((result, exception) -> {
         *     if (exception == null) {
         *         // 处理正常结果
         *     } else {
         *         // 处理异常
         *     }
         * });
         *  ```
         * ```
         * 整个流程的关键点：
         *
         * 1. 请求发送时通过请求ID与Future建立映射关系
         * 2. 响应返回时通过请求ID找到对应Future
         * 3. 处理响应结果，调用Future的complete/completeExceptionally方法
         * 4. 业务代码通过Future获取调用结果
         * 这就是一个完整的异步请求-响应流程。
         */
        return future;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        // If the channel has been closed, return directly.
        if (closed) {
            return;
        }
        closed = true;
        try {
            // graceful close
            DefaultFuture.closeChannel(channel);
            channel.close();
        } catch (Throwable e) {
            logger.warn(e.getMessage(), e);
        }
    }

    // graceful close
    @Override
    public void close(int timeout) {
        if (closed) {
            return;
        }
        if (timeout > 0) {
            long start = System.currentTimeMillis();
            while (DefaultFuture.hasFuture(channel)
                    && System.currentTimeMillis() - start < timeout) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        close();
    }

    @Override
    public void startClose() {
        channel.startClose();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    @Override
    public URL getUrl() {
        return channel.getUrl();
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return channel.getChannelHandler();
    }

    @Override
    public ExchangeHandler getExchangeHandler() {
        return (ExchangeHandler) channel.getChannelHandler();
    }

    @Override
    public Object getAttribute(String key) {
        return channel.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        channel.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        channel.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        return channel.hasAttribute(key);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((channel == null) ? 0 : channel.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        HeaderExchangeChannel other = (HeaderExchangeChannel) obj;
        if (channel == null) {
            if (other.channel != null) {
                return false;
            }
        } else if (!channel.equals(other.channel)) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return channel.toString();
    }

}
