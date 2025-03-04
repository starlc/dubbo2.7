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
package org.apache.dubbo.common.threadpool;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The most important difference between this Executor and other normal Executor is that this one doesn't manage
 * any thread.
 * <p>
 * Tasks submitted to this executor through {@link #execute(Runnable)} will not get scheduled to a specific thread, though normal executors always do the schedule.
 * Those tasks are stored in a blocking queue and will only be executed when a thread calls {@link #waitAndDrain()}, the thread executing the task
 * is exactly the same as the one calling waitAndDrain.
 * ThreadlessExecutor 是消费端的特殊优化，服务端会继续使用常规线程池模式。
 * ThreadlessExecutor 是一种特殊类型的线程池，与其他正常的线程池最主要的区别是：
 * ThreadlessExecutor 内部不管理任何线程。
 * 我们可以调用 ThreadlessExecutor 的execute() 方法，将任务提交给这个线程池，
 * 但是这些提交的任务不会被调度到任何线程执行，
 * 而是存储在阻塞队列中，只有当其他线程调用 ThreadlessExecutor.waitAndDrain() 方法时才会真正执行。
 * 也说就是，执行任务的线程 与调用 waitAndDrain() 方法的是同一个线程。
 *
 * 那为什么会有 ThreadlessExecutor 这个实现呢？**这主要是因为在 Dubbo 2.7.5 版本之前，
 * 在 WrappedChannelHandler 中会为每个连接启动一个线程池。
 *
 * OLD:
 * 请求-响应流程：
 * 业务线程发出请求之后，拿到一个 Future 实例。
 * 业务线程紧接着调用 Future.get() 阻塞等待请求结果返回。
 * 当响应返回之后，交由连接关联的独立线程池进行反序列化等解析处理。
 * 待处理完成之后，将业务结果通过 Future.set() 方法返回给业务线程。
 *
 * 在这个设计里面，Consumer 端会维护一个线程池，而且线程池是按照连接隔离的，
 * 即每个连接独享一个线程池。这样，当面临需要消费大量服务且并发数比较大的场景时，例如，典型网关类场景，
 * 可能会导致 Consumer 端线程个数不断增加，导致线程调度消耗过多 CPU ，也可能因为线程创建过多而导致 OOM。
 *
 * 为了解决上述问题，Dubbo 在 2.7.5 版本之后，引入了 ThreadlessExecutor，将线程模型修改成了下图的样子：
 * 业务线程发出请求之后，拿到一个 Future 对象。
 * 业务线程会调用 ThreadlessExecutor.waitAndDrain() 方法，waitAndDrain() 方法会在阻塞队列上等待。
 * 当收到响应时，IO 线程会生成一个任务，填充到 ThreadlessExecutor 队列中，
 * 业务线程会将上面添加的任务取出，并在本线程中执行。得到业务结果之后，调用 Future.set() 方法进行设置，
 * 此时 waitAndDrain() 方法返回。
 * 业务线程从 Future 中拿到结果值。
 *
 * 关键点说明：
 * 1. 整个过程只有一个业务线程
 * 2. ThreadlessExecutor 不创建新线程
 * 3. IO线程只负责将任务放入队列
 * 4. 响应处理实际是在原业务线程中执行
 *
 *
 * ThreadLessExecutor 的主要使用入口在 Dubbo 的异步调用流程中，具体位置在：
 * 1. 入口类 ： `DubboInvoker.java`
 *
 * ThreadlessExecutor 的销毁发生在以下几个时机
 * waitAndDrain 只能执行一次，执行多次无效果
 * ThreadlessExecutor 的生命周期确实是和方法调用绑定的。
 * - 方法执行完成后，没有其他引用持有 ThreadlessExecutor 实例
 * - 由于是方法局部变量，自然会被 GC 回收
 * - finished 标记确保了即使有残留引用也不会重复使用
 *
 */
public class ThreadlessExecutor extends AbstractExecutorService {
    private static final Logger logger = LoggerFactory.getLogger(ThreadlessExecutor.class.getName());

    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();//阻塞队列，用来在 IO 线程和业务线程之间传递任务。

    private ExecutorService sharedExecutor;//ThreadlessExecutor 底层关联的共享线程池，当业务线程已经不再等待响应时，会由该共享线程执行提交的任务。

    private CompletableFuture<?> waitingFuture;//指向请求对应的 DefaultFuture 对象，其具体实现我们会在后面的课时详细展开介绍。

    private boolean finished = false;//若为true，则此次调用直接返回

    private volatile boolean waiting = true;

    private final Object lock = new Object();

    public ThreadlessExecutor(ExecutorService sharedExecutor) {
        this.sharedExecutor = sharedExecutor;
    }

    public CompletableFuture<?> getWaitingFuture() {
        return waitingFuture;
    }

    public void setWaitingFuture(CompletableFuture<?> waitingFuture) {
        this.waitingFuture = waitingFuture;
    }

    public boolean isWaiting() {
        return waiting;
    }

    /**
     * Waits until there is a task, executes the task and all queued tasks (if there're any). The task is either a normal
     * response or a timeout response.
     */
    public void waitAndDrain() throws InterruptedException {
        /**
         * Usually, {@link #waitAndDrain()} will only get called once. It blocks for the response for the first time,
         * once the response (the task) reached and being executed waitAndDrain will return, the whole request process
         * then finishes. Subsequent calls on {@link #waitAndDrain()} (if there're any) should return immediately.
         *
         * There's no need to worry that {@link #finished} is not thread-safe. Checking and updating of
         * 'finished' only appear in waitAndDrain, since waitAndDrain is binding to one RPC call (one thread), the call
         * of it is totally sequential.
         *
         * ThreadlessExecutor 中的 waitAndDrain() 方法一般与一次 RPC 调用绑定，只会执行一次。
         * 当后续再次调用 waitAndDrain() 方法时，会检查 finished 字段，若为true，则此次调用直接返回。
         * 当后续再次调用 execute() 方法提交任务时，会根据 waiting 字段决定任务是放入 queue 队列等待业务线程执行，
         * 还是直接由 sharedExecutor 线程池执行。
         */
        if (finished) {// 检测当前ThreadlessExecutor状态
            return;
        }

        // 获取阻塞队列中获取任务
        Runnable runnable;
        try {
            runnable = queue.take();
        }catch (InterruptedException e){
            waiting = false;
            throw e;
        }

        synchronized (lock) {
            waiting = false;// 修改waiting状态
            runnable.run(); // 执行任务
        }
        runnable = queue.poll();
        while (runnable != null) {// 如果阻塞队列中还有其他任务，也需要一并执行
            runnable.run();
            runnable = queue.poll();
        }
        // mark the status of ThreadlessExecutor as finished.
        finished = true;// 修改finished状态
    }

    public long waitAndDrain(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        /*long startInMs = System.currentTimeMillis();
        Runnable runnable = queue.poll(timeout, unit);
        if (runnable == null) {
            throw new TimeoutException();
        }
        runnable.run();
        long elapsedInMs = System.currentTimeMillis() - startInMs;
        long timeLeft = timeout - elapsedInMs;
        if (timeLeft < 0) {
            throw new TimeoutException();
        }
        return timeLeft;*/
        throw new UnsupportedOperationException();
    }

    /**
     * If the calling thread is still waiting for a callback task, add the task into the blocking queue to wait for schedule.
     * Otherwise, submit to shared callback executor directly.
     * 根据 waiting 状态决定任务提交到哪里
     *
     * @param runnable
     */
    @Override
    public void execute(Runnable runnable) {
        runnable = new RunnableWrapper(runnable);
        synchronized (lock) {
            if (!waiting) {// 判断业务线程是否还在等待响应结果
                // 不等待，则直接交给共享线程池处理任务
                sharedExecutor.execute(runnable);
            } else {
                // 业务线程还在等待，则将任务写入队列，然后由业务线程自己执行
                queue.add(runnable);
            }
        }
    }

    /**
     * tells the thread blocking on {@link #waitAndDrain()} to return, despite of the current status, to avoid endless waiting.
     */
    public void notifyReturn(Throwable t) {
        // an empty runnable task.
        execute(() -> {
            waitingFuture.completeExceptionally(t);
        });
    }

    /**
     * The following methods are still not supported
     */

    @Override
    public void shutdown() {
        shutdownNow();
    }

    @Override
    public List<Runnable> shutdownNow() {
        notifyReturn(new IllegalStateException("Consumer is shutting down and this call is going to be stopped without " +
                "receiving any result, usually this is called by a slow provider instance or bad service implementation."));
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return false;
    }

    private static class RunnableWrapper implements Runnable {
        private Runnable runnable;

        public RunnableWrapper(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void run() {
            try {
                runnable.run();
            } catch (Throwable t) {
                logger.info(t);
            }
        }
    }
}
