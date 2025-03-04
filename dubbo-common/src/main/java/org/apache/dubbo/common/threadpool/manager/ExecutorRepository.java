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
package org.apache.dubbo.common.threadpool.manager;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.SPI;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 *ExecutorRepository 负责创建并管理 Dubbo 中的线程池，该接口虽然是个 SPI 扩展点，
 * 但是只有一个默认实现—— DefaultExecutorRepository。
 * 在该默认实现中维护了一个 ConcurrentMap<String, ConcurrentMap<Integer, ExecutorService>> 集合（data 字段）
 * 缓存已有的线程池，
 * 第一层 Key 值表示线程池属于 Provider 端还是 Consumer 端，
 * 第二层 Key 值表示线程池关联服务的端口。
 *
 *
 * ExecutorRepository 默认使用 SynchronousQueue 作为线程池队列，主要有以下几个原因：
 *
 * 1. 直接传递特性 ：
 * - SynchronousQueue 是一个没有容量的阻塞队列
 * - 每个插入操作必须等待另一个线程的移除操作
 * - 适合于任务需要立即执行的场景
 * 2. RPC 调用特点 ：
 * - RPC 调用通常要求低延迟
 * - 任务需要被及时处理
 * - 不希望任务在队列中长时间等待
 * 3. 快速失败机制 ：
 * - 当线程池满载时，新任务会被拒绝
 * - 避免请求堆积导致系统崩溃
 * - 触发快速失败比等待更好
 * 4. 资源控制 ：
 * - 避免内存占用过大
 * - 防止任务堆积
 * - 便于及时发现系统容量问题
 * 5. 负载均衡考虑 ：
 * - 当服务过载时快速失败
 * - 便于服务治理和容量规划
 * - 有利于集群的负载均衡
 */
@SPI("default")
public interface ExecutorRepository {

    /**
     * Called by both Client and Server. TODO, consider separate these two parts.
     * When the Client or Server starts for the first time, generate a new threadpool according to the parameters specified.
     *
     * @param url
     * @return
     */
    ExecutorService createExecutorIfAbsent(URL url);

    ExecutorService getExecutor(URL url);

    /**
     * Modify some of the threadpool's properties according to the url, for example, coreSize, maxSize, ...
     *
     * @param url
     * @param executor
     */
    void updateThreadpool(URL url, ExecutorService executor);

    /**
     * Returns a scheduler from the scheduler list, call this method whenever you need a scheduler for a cron job.
     * If your cron cannot burden the possible schedule delay caused by sharing the same scheduler, please consider define a dedicate one.
     *
     * @return
     */
    ScheduledExecutorService nextScheduledExecutor();

    ScheduledExecutorService getServiceExporterExecutor();

    /**
     * Get the default shared threadpool.
     *
     * @return
     */
    ExecutorService getSharedExecutor();

    /**
     * Destroy all executors that are not in shutdown state
     */
    void destroyAll();
}
