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
package org.apache.dubbo.remoting.exchange.support;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.ThreadlessExecutor;
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.timer.Timeout;
import org.apache.dubbo.common.timer.Timer;
import org.apache.dubbo.common.timer.TimerTask;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.SerializationException;
import org.apache.dubbo.remoting.TimeoutException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * DefaultFuture.
 */
public class DefaultFuture extends CompletableFuture<Object> {

    private static final Logger logger = LoggerFactory.getLogger(DefaultFuture.class);

    /**
     * 管理请求与 Channel 之间的关联关系，其中 Key 为请求 ID，Value 为发送请求的 Channel。
     */
    private static final Map<Long, Channel> CHANNELS = new ConcurrentHashMap<>();

    /**
     * 管理请求与 DefaultFuture 之间的关联关系，其中 Key 为请求 ID，Value 为请求对应的 Future。
     */
    private static final Map<Long, DefaultFuture> FUTURES = new ConcurrentHashMap<>();

    /**
     * 是一个 HashedWheelTimer 对象，即 Dubbo 中对时间轮的实现，这是一个 static 字段，所有 DefaultFuture 对象共用一个。
     */
    public static final Timer TIME_OUT_TIMER = new HashedWheelTimer(
            new NamedThreadFactory("dubbo-future-timeout", true),
            30,
            TimeUnit.MILLISECONDS);

    // invoke id.
    private final Long id;//请求的 ID。
    private final Channel channel;//发送请求的 Channel。
    /**
     * DefaultFuture 中封装的 request 主要有以下几个作用：
     * 1. 请求标识和关联
     * - 通过 request.getId() 获取请求ID，用于后续响应的匹配
     * - 维护请求ID与Future的映射关
     * 2. 超时信息记录
     * - 在超时场景下，可以获取请求的详细信息用于日志记录
     * - copyWithoutData() 方法可以在不暴露敏感数据的情况下打印请求信息
     * 3. 异常处理
     * - 在连接断开等异常情况下，可以获取未完成请求的信息
     * - 帮助定位问题和错误排查
     * 4. 请求上下文传递
     * - 保存完整的请求信息，包括：
     *   - 请求参数
     *   - 请求方法
     *   - 请求版本
     *   - 是否需要响应(twoWay)等信息
     * 这些信息对于请求的生命周期管理、异常处理、监控统计等都很重要。
     */
    private final Request request;//对应请求以及
    private final int timeout;//整个请求-响应交互完成的超时时间。
    private final long start = System.currentTimeMillis();//该 DefaultFuture 的创建时间。
    private volatile long sent;//请求发送的时间。
    private Timeout timeoutCheckTask;//该定时任务到期时，表示对端响应超时。

    private ExecutorService executor;//请求关联的线程池。

    public ExecutorService getExecutor() {
        return executor;
    }

    public void setExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    private DefaultFuture(Channel channel, Request request, int timeout) {
        this.channel = channel;
        this.request = request;
        this.id = request.getId();
        //默认为1000
        this.timeout = timeout > 0 ? timeout : channel.getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
        // put into waiting map.
        FUTURES.put(id, this);
        CHANNELS.put(id, channel);
    }

    /**
     * check time out of the future
     */
    private static void timeoutCheck(DefaultFuture future) {
        //TIME_OUT_TIMER 是一个 HashedWheelTimer 对象，即 Dubbo 中对时间轮的实现，这是一个 static 字段，所有 DefaultFuture 对象共用一个。
        TimeoutCheckTask task = new TimeoutCheckTask(future.getId());
        future.timeoutCheckTask = TIME_OUT_TIMER.newTimeout(task, future.getTimeout(), TimeUnit.MILLISECONDS);
    }

    /**
     * init a DefaultFuture
     * 1.init a DefaultFuture
     * 2.timeout check
     *
     * @param channel channel
     * @param request the request
     * @param timeout timeout
     * @return a new DefaultFuture
     */
    public static DefaultFuture newFuture(Channel channel, Request request, int timeout, ExecutorService executor) {
        // 创建DefaultFuture对象，并初始化其中的核心字段
        final DefaultFuture future = new DefaultFuture(channel, request, timeout);
        future.setExecutor(executor);
        // ThreadlessExecutor needs to hold the waiting future in case of circuit return.
        // 对于ThreadlessExecutor的特殊处理，ThreadlessExecutor可以关联一个waitingFuture，就是这里创建DefaultFuture对象
        if (executor instanceof ThreadlessExecutor) {
            ((ThreadlessExecutor) executor).setWaitingFuture(future);
        }
        // timeout check
        // 创建一个定时任务，用处理响应超时的情况
        timeoutCheck(future);
        return future;
    }

    public static DefaultFuture getFuture(long id) {
        return FUTURES.get(id);
    }

    public static boolean hasFuture(Channel channel) {
        return CHANNELS.containsValue(channel);
    }

    public static void sent(Channel channel, Request request) {
        DefaultFuture future = FUTURES.get(request.getId());
        if (future != null) {
            future.doSent();
        }
    }

    /**
     * close a channel when a channel is inactive
     * directly return the unfinished requests.
     *
     * @param channel channel to close
     */
    public static void closeChannel(Channel channel) {
        for (Map.Entry<Long, Channel> entry : CHANNELS.entrySet()) {
            if (channel.equals(entry.getValue())) {
                DefaultFuture future = getFuture(entry.getKey());
                if (future != null && !future.isDone()) {
                    Response disconnectResponse = new Response(future.getId());
                    disconnectResponse.setStatus(Response.CHANNEL_INACTIVE);
                    disconnectResponse.setErrorMessage("Channel " +
                            channel +
                            " is inactive. Directly return the unFinished request : " +
                            (logger.isDebugEnabled() ? future.getRequest() : future.getRequest().copyWithoutData()));
                    DefaultFuture.received(channel, disconnectResponse);
                }
            }
        }
    }

    public static void received(Channel channel, Response response) {
        received(channel, response, false);
    }

    public static void received(Channel channel, Response response, boolean timeout) {
        try {
            // 清理FUTURES中记录的请求ID与DefaultFuture之间的映射关系
            DefaultFuture future = FUTURES.remove(response.getId());
            if (future != null) {
                Timeout t = future.timeoutCheckTask;
                if (!timeout) {
                    // decrease Time
                    // 未超时，取消定时任务
                    t.cancel();
                }
                // 调用doReceived()方法
                future.doReceived(response);
            } else {
                // 查找不到关联的DefaultFuture会打印日志(略)}
                logger.warn("The timeout response finally returned at "
                        + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()))
                        + ", response status is " + response.getStatus()
                        + (channel == null ? "" : ", channel: " + channel.getLocalAddress()
                        + " -> " + channel.getRemoteAddress()) + ", please check provider side for detailed result.");
            }
        } finally {
            // 清理CHANNELS中记录的请求ID与Channel之间的映射关系
            CHANNELS.remove(response.getId());
        }
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        Response errorResult = new Response(id);
        errorResult.setStatus(Response.CLIENT_ERROR);
        errorResult.setErrorMessage("request future has been canceled.");
        this.doReceived(errorResult);
        FUTURES.remove(id);
        CHANNELS.remove(id);
        return true;
    }

    public void cancel() {
        this.cancel(true);
    }

    /**
     * 在 Client 端发送请求时，首先会创建对应的 DefaultFuture（其中记录了请求 ID 等信息），
     * 然后依赖 Netty 的异步发送特性将请求发送到 Server 端。需要说明的是，这整个发送过程是不会阻塞任何线程的。
     * 之后，将 DefaultFuture 返回给上层，在这个返回过程中，DefaultFuture 会被封装成 AsyncRpcResult，同时也可以添加回调函数。
     *
     * 当 Client 端接收到响应结果的时候，会交给关联的线程池（ExecutorService）或是业务线程（使用 ThreadlessExecutor 场景）进行处理，
     * 得到 Server 返回的真正结果。拿到真正的返回结果后，会将其设置到 DefaultFuture 中，
     * 并调用 complete() 方法将其设置为完成状态。此时，就会触发前面注册在 DefaulFuture 上的回调函数，执行回调逻辑。
     * @param res
     */
    private void doReceived(Response res) {
        if (res == null) {
            throw new IllegalStateException("response cannot be null");
        }
        if (res.getStatus() == Response.OK) {// 正常响应
            this.complete(res.getResult());
        } else if (res.getStatus() == Response.CLIENT_TIMEOUT || res.getStatus() == Response.SERVER_TIMEOUT) {// 超时
            this.completeExceptionally(new TimeoutException(res.getStatus() == Response.SERVER_TIMEOUT, channel, res.getErrorMessage()));
        } else if(res.getStatus() == Response.SERIALIZATION_ERROR){
            this.completeExceptionally(new SerializationException(channel, res.getErrorMessage()));
        }else {// 其他异常
            this.completeExceptionally(new RemotingException(channel, res.getErrorMessage()));
        }

        // the result is returning, but the caller thread may still waiting
        // to avoid endless waiting for whatever reason, notify caller thread to return.
        // 下面是针对ThreadlessExecutor的兜底处理，主要是防止业务线程一直阻塞在ThreadlessExecutor上
        if (executor != null && executor instanceof ThreadlessExecutor) {
            ThreadlessExecutor threadlessExecutor = (ThreadlessExecutor) executor;
            if (threadlessExecutor.isWaiting()) {
                // notifyReturn()方法会向ThreadlessExecutor提交一个任务，这样业务线程就不会阻塞了，提交的任务会尝试将DefaultFuture设置为异常结束
                threadlessExecutor.notifyReturn(new IllegalStateException("The result has returned, but the biz thread is still waiting" +
                        " which is not an expected state, interrupt the thread manually by returning an exception."));
            }
        }
    }

    private long getId() {
        return id;
    }

    private Channel getChannel() {
        return channel;
    }

    private boolean isSent() {
        return sent > 0;
    }

    public Request getRequest() {
        return request;
    }

    private int getTimeout() {
        return timeout;
    }

    private void doSent() {
        sent = System.currentTimeMillis();
    }

    private String getTimeoutMessage(boolean scan) {
        long nowTimestamp = System.currentTimeMillis();
        return (sent > 0 ? "Waiting server-side response timeout" : "Sending request timeout in client-side")
                + (scan ? " by scan timer" : "") + ". start time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(start))) + ", end time: "
                + (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(nowTimestamp))) + ","
                + (sent > 0 ? " client elapsed: " + (sent - start)
                + " ms, server elapsed: " + (nowTimestamp - sent)
                : " elapsed: " + (nowTimestamp - start)) + " ms, timeout: "
                + timeout + " ms, request: " + (logger.isDebugEnabled() ? request : request.copyWithoutData()) + ", channel: " + channel.getLocalAddress()
                + " -> " + channel.getRemoteAddress();
    }

    /**
     *  是 DefaultFuture 中的内部类，实现了 TimerTask 接口，可以提交到时间轮中等待执行。
     *  当响应超时的时候，TimeoutCheckTask 会创建一个 Response，
     *  并调用前面介绍的 DefaultFuture.received() 方法
     */
    private static class TimeoutCheckTask implements TimerTask {

        private final Long requestID;

        TimeoutCheckTask(Long requestID) {
            this.requestID = requestID;
        }

        @Override
        public void run(Timeout timeout) {
            // 检查该任务关联的DefaultFuture对象是否已经完成
            DefaultFuture future = DefaultFuture.getFuture(requestID);
            if (future == null || future.isDone()) {
                return;
            }

            // 提交到线程池执行，注意ThreadlessExecutor的情况
            if (future.getExecutor() != null) {
                future.getExecutor().execute(() -> notifyTimeout(future));
            } else {
                notifyTimeout(future);
            }
        }

        private void notifyTimeout(DefaultFuture future) {
            // create exception response.
            // 没有收到对端的响应，这里会创建一个Response，表示超时的响应
            Response timeoutResponse = new Response(future.getId());
            // set timeout status.
            timeoutResponse.setStatus(future.isSent() ? Response.SERVER_TIMEOUT : Response.CLIENT_TIMEOUT);
            timeoutResponse.setErrorMessage(future.getTimeoutMessage(true));
            // handle response.
            // 将关联的DefaultFuture标记为超时异常完成
            DefaultFuture.received(future.getChannel(), timeoutResponse, true);
        }
    }
}
