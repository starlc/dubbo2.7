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
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Client;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.remoting.Constants.HEARTBEAT_CHECK_TICK;
import static org.apache.dubbo.remoting.Constants.LEAST_HEARTBEAT_DURATION;
import static org.apache.dubbo.remoting.Constants.TICKS_PER_WHEEL;
import static org.apache.dubbo.remoting.utils.UrlUtils.getHeartbeat;
import static org.apache.dubbo.remoting.utils.UrlUtils.getIdleTimeout;

/**
 * DefaultMessageClient
 * HeaderExchangeClient 是 Client 装饰器，主要为其装饰的 Client 添加两个功能：
 *
 * 维持与 Server 的长连状态，这是通过定时发送心跳消息实现的；
 *
 * 在因故障掉线之后，进行重连，这是通过定时检查连接状态实现的。
 *
 * 侧重定时轮资源的分配、定时任务的创建和取消。
 *
 *
 *startHeartBeatTask中有检测是否需要开启自定义心跳任务
 * 这是一个很好的问题。Dubbo 中确实存在两层心跳检测机制，它们的作用是不同的：
 *
 * 1. **Netty层的心跳检测** (IdleStateHandler)：
 * ```java
 * // NettyClient.java
 * .addLast("client-idle-handler", new IdleStateHandler(heartbeatInterval, 0, 0, MILLISECONDS))
 * ```
 * - 用于检测底层 TCP 连接是否空闲
 * - 当指定时间内没有读操作时，会触发 IdleStateEvent 事件
 * - 主要用于及时发现连接异常
 *
 * 2. **Dubbo层的心跳检测** (HeaderExchangeClient)：
 * ```java
 * // HeaderExchangeClient.java
 * private void startHeartBeatTask(URL url) {
 *     if (!client.canHandleIdle()) {
 *         // 创建心跳任务
 *         this.heartBeatTimerTask = new HeartbeatTimerTask(cp, heartbeatTick, heartbeat);
 *         IDLE_CHECK_TIMER.newTimeout(heartBeatTimerTask, heartbeatTick, TimeUnit.MILLISECONDS);
 *     }
 * }
 * ```
 * - 主动发送业务层面的心跳消息
 * - 维护应用层面的连接状态
 * - 可以携带一些业务信息
 *
 * 两者的配合：
 * 1. Netty的 IdleStateHandler 负责**检测**连接空闲
 * 2. HeaderExchangeClient 负责**主动发送**心跳消息
 * 3. 重连任务则是在连接断开时进行重试
 *
 * 这种双重机制提供了更可靠的连接保障：
 * - 底层网络连接的及时检测
 * - 业务层面的连接维护
 * - 连接异常时的自动恢复
 *
 * 这也是为什么两种机制都需要存在的原因。
 *
 * 回到心跳定时任务进行分析，你可以回顾第 20 课时介绍的 NettyClient 实现，
 * 其 canHandleIdle() 方法返回 true，表示该实现可以自己发送心跳请求，
 * 无须 HeaderExchangeClient 再启动一个定时任务。
 * NettyClient 主要依靠 IdleStateHandler 中的定时任务来触发心跳事件，
 * 依靠 NettyClientHandler 来发送心跳请求
 */
public class HeaderExchangeClient implements ExchangeClient {

    private final Client client; //被修饰的 Client 对象。HeaderExchangeClient 中对 Client 接口的实现，都会委托给该对象进行处理。
    private final ExchangeChannel channel;//Client 与服务端建立的连接，HeaderExchangeChannel 也是一个装饰器

    private static final HashedWheelTimer IDLE_CHECK_TIMER = new HashedWheelTimer(
            new NamedThreadFactory("dubbo-client-idleCheck", true), 1, TimeUnit.SECONDS, TICKS_PER_WHEEL);
    private HeartbeatTimerTask heartBeatTimerTask;
    private ReconnectTimerTask reconnectTimerTask;

    public HeaderExchangeClient(Client client, boolean startTimer) {
        Assert.notNull(client, "Client can't be null");
        //第一个参数封装 Transport 层的 Client 对象
        this.client = client;
        this.channel = new HeaderExchangeChannel(client);

        //startTimer参与控制是否开启心跳定时任务和重连定时任务，如果为 true，才会进一步根据其他条件，最终决定是否启动定时任务
        if (startTimer) {
            URL url = client.getUrl();
            startReconnectTask(url);//重连定时任务
            startHeartBeatTask(url);//心跳定时任务
        }
    }

    @Override
    public CompletableFuture<Object> request(Object request) throws RemotingException {
        return channel.request(request);
    }

    @Override
    public URL getUrl() {
        return channel.getUrl();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    @Override
    public CompletableFuture<Object> request(Object request, int timeout) throws RemotingException {
        return channel.request(request, timeout);
    }

    @Override
    public CompletableFuture<Object> request(Object request, ExecutorService executor) throws RemotingException {
        return channel.request(request, executor);
    }

    @Override
    public CompletableFuture<Object> request(Object request, int timeout, ExecutorService executor) throws RemotingException {
        return channel.request(request, timeout, executor);
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return channel.getChannelHandler();
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    @Override
    public ExchangeHandler getExchangeHandler() {
        return channel.getExchangeHandler();
    }

    @Override
    public void send(Object message) throws RemotingException {
        channel.send(message);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        channel.send(message, sent);
    }

    @Override
    public boolean isClosed() {
        return channel.isClosed();
    }

    @Override
    public void close() {
        doClose();
        channel.close();
    }

    @Override
    public void close(int timeout) {
        // Mark the client into the closure process
        startClose();
        doClose();
        channel.close(timeout);
    }

    @Override
    public void startClose() {
        channel.startClose();
    }

    @Override
    public void reset(URL url) {
        client.reset(url);
        // FIXME, should cancel and restart timer tasks if parameters in the new URL are different?
    }

    @Override
    @Deprecated
    public void reset(org.apache.dubbo.common.Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    @Override
    public void reconnect() throws RemotingException {
        client.reconnect();
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

    private void startHeartBeatTask(URL url) {
        if (!client.canHandleIdle()) {// Client的具体实现决定是否启动该心跳任务
            AbstractTimerTask.ChannelProvider cp = () -> Collections.singletonList(HeaderExchangeClient.this);
            // 计算心跳间隔，最小间隔不能低于1s 默认60s
            int heartbeat = getHeartbeat(url);
            //默认为20*1000ms
            long heartbeatTick = calculateLeastDuration(heartbeat);
            // 创建心跳任务
            this.heartBeatTimerTask = new HeartbeatTimerTask(cp, heartbeatTick, heartbeat);
            // 提交到IDLE_CHECK_TIMER这个时间轮中等待执行
            IDLE_CHECK_TIMER.newTimeout(heartBeatTimerTask, heartbeatTick, TimeUnit.MILLISECONDS);
        }
    }

    private void startReconnectTask(URL url) {
        if (shouldReconnect(url)) {//根据配置决定是否需要重连
            AbstractTimerTask.ChannelProvider cp = () -> Collections.singletonList(HeaderExchangeClient.this);
            // 计算重连检测时间 默认3*60s
            int idleTimeout = getIdleTimeout(url);
            //默认为60*1000
            long heartbeatTimeoutTick = calculateLeastDuration(idleTimeout);
            this.reconnectTimerTask = new ReconnectTimerTask(cp, heartbeatTimeoutTick, idleTimeout);
            IDLE_CHECK_TIMER.newTimeout(reconnectTimerTask, heartbeatTimeoutTick, TimeUnit.MILLISECONDS);
        }
    }

    private void doClose() {
        if (heartBeatTimerTask != null) {
            heartBeatTimerTask.cancel();
        }

        if (reconnectTimerTask != null) {
            reconnectTimerTask.cancel();
        }
    }

    /**
     * Each interval cannot be less than 1000ms.
     * time 默认是180*1000ms
     * 所以重连时间时，默认返回是60 *1000ms
     * 计算心跳时间时 默认返回20*1000ms
     */
    private long calculateLeastDuration(int time) {
        if (time / HEARTBEAT_CHECK_TICK <= 0) {
            return LEAST_HEARTBEAT_DURATION;
        } else {
            return time / HEARTBEAT_CHECK_TICK;
        }
    }

    private boolean shouldReconnect(URL url) {
        return url.getParameter(Constants.RECONNECT_KEY, true);
    }

    @Override
    public String toString() {
        return "HeaderExchangeClient [channel=" + channel + "]";
    }
}
