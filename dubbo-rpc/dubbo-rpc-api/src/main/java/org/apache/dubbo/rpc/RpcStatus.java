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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.URL;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * URL statistics. (API, Cached, ThreadSafe)
 *
 * @see org.apache.dubbo.rpc.filter.ActiveLimitFilter
 * @see org.apache.dubbo.rpc.filter.ExecuteLimitFilter
 * @see org.apache.dubbo.rpc.cluster.loadbalance.LeastActiveLoadBalance
 *
 * 从 ActiveLimitFilter.invoke() 方法的代码中可以看到，其核心实现与 RpcStatus 对象密切相关。RpcStatus 中维护了两个集合，分别是：
 *
 * SERVICE_STATISTICS 集合（ConcurrentMap<String, RpcStatus> 类型），
 * 这个集合记录了当前 Consumer 调用每个服务的状态信息，其中 Key 是 URL，Value 是对应的 RpcStatus 对象；
 *
 * METHOD_STATISTICS 集合（ConcurrentMap<String, ConcurrentMap<String, RpcStatus>> 类型），
 * 这个集合记录了当前 Consumer 调用每个服务方法的状态信息，其中第一层 Key 是 URL ，第二层 Key 是方法名称，
 * 第三层是对应的 RpcStatus 对象。
 *
 * RpcStatus 中统计了很多调用相关的信息，核心字段有如下几个。
 *
 * active（AtomicInteger 类型）：当前并发度。这也是 ActiveLimitFilter 中关注的并发度。
 * total（AtomicLong 类型）：调用的总数。
 * failed（AtomicInteger 类型）：失败的调用数。
 * totalElapsed（AtomicLong 类型）：所有调用的总耗时。
 * failedElapsed（AtomicLong 类型）：所有失败调用的总耗时。
 * maxElapsed（AtomicLong 类型）：所有调用中最长的耗时。
 * failedMaxElapsed（AtomicLong 类型）：所有失败调用中最长的耗时。
 * succeededMaxElapsed（AtomicLong 类型）：所有成功调用中最长的耗时。
 *
 * 另外，RpcStatus 提供了上述字段的 getter/setter 方法，用于读写这些字段值，这里不再展开分析。
 *
 * RpcStatus 中的 beginCount() 方法会在远程调用开始之前执行，其中会从 SERVICE_STATISTICS 集合和
 * METHOD_STATISTICS 集合中获取服务和服务方法对应的 RpcStatus 对象，然后分别将它们的 active 字段加一
 */
public class RpcStatus {

    private static final ConcurrentMap<String, RpcStatus> SERVICE_STATISTICS = new ConcurrentHashMap<String,
            RpcStatus>();

    private static final ConcurrentMap<String, ConcurrentMap<String, RpcStatus>> METHOD_STATISTICS =
            new ConcurrentHashMap<String, ConcurrentMap<String, RpcStatus>>();

    private final ConcurrentMap<String, Object> values = new ConcurrentHashMap<String, Object>();

    private final AtomicInteger active = new AtomicInteger();
    private final AtomicLong total = new AtomicLong();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicLong totalElapsed = new AtomicLong();
    private final AtomicLong failedElapsed = new AtomicLong();
    private final AtomicLong maxElapsed = new AtomicLong();
    private final AtomicLong failedMaxElapsed = new AtomicLong();
    private final AtomicLong succeededMaxElapsed = new AtomicLong();

    private RpcStatus() {
    }

    /**
     * @param url
     * @return status
     */
    public static RpcStatus getStatus(URL url) {
        String uri = url.toIdentityString();
        return SERVICE_STATISTICS.computeIfAbsent(uri, key -> new RpcStatus());
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url) {
        String uri = url.toIdentityString();
        SERVICE_STATISTICS.remove(uri);
    }

    /**
     * @param url
     * @param methodName
     * @return status
     */
    public static RpcStatus getStatus(URL url, String methodName) {
        String uri = url.toIdentityString();
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.computeIfAbsent(uri, k -> new ConcurrentHashMap<>());
        return map.computeIfAbsent(methodName, k -> new RpcStatus());
    }

    /**
     * @param url
     */
    public static void removeStatus(URL url, String methodName) {
        String uri = url.toIdentityString();
        ConcurrentMap<String, RpcStatus> map = METHOD_STATISTICS.get(uri);
        if (map != null) {
            map.remove(methodName);
        }
    }

    public static void beginCount(URL url, String methodName) {
        beginCount(url, methodName, Integer.MAX_VALUE);
    }

    /**
     * @param url
     */
    public static boolean beginCount(URL url, String methodName, int max) {
        max = (max <= 0) ? Integer.MAX_VALUE : max;
        // 获取服务对应的RpcStatus对象
        RpcStatus appStatus = getStatus(url);
        // 获取服务方法对应的RpcStatus对象
        RpcStatus methodStatus = getStatus(url, methodName);
        if (methodStatus.active.get() == Integer.MAX_VALUE) {// 并发度溢出
            return false;
        }
        for (int i; ; ) {
            i = methodStatus.active.get();

            if (i == Integer.MAX_VALUE || i + 1 > max) {// 并发度超过max上限，直接返回false
                return false;
            }

            if (methodStatus.active.compareAndSet(i, i + 1)) {// CAS操作
                break;// 更新成功后退出当前循环
            }
        }

        appStatus.active.incrementAndGet();// 单个服务的并发度加一

        return true;
    }

    /**
     * @param url
     * @param elapsed
     * @param succeeded
     */
    public static void endCount(URL url, String methodName, long elapsed, boolean succeeded) {
        endCount(getStatus(url), elapsed, succeeded);
        endCount(getStatus(url, methodName), elapsed, succeeded);
    }

    private static void endCount(RpcStatus status, long elapsed, boolean succeeded) {
        status.active.decrementAndGet();// 请求完成，降低并发度
        status.total.incrementAndGet(); // 调用总次数增加
        status.totalElapsed.addAndGet(elapsed);// 调用总耗时增加

        if (status.maxElapsed.get() < elapsed) {// 更新最大耗时
            status.maxElapsed.set(elapsed);
        }

        if (succeeded) {// 如果此次调用成功，则会更新成功调用的最大耗时
            if (status.succeededMaxElapsed.get() < elapsed) {
                status.succeededMaxElapsed.set(elapsed);
            }

        } else {// 如果此次调用失败，则会更新失败调用的最大耗时
            status.failed.incrementAndGet();
            status.failedElapsed.addAndGet(elapsed);
            if (status.failedMaxElapsed.get() < elapsed) {
                status.failedMaxElapsed.set(elapsed);
            }
        }
    }

    /**
     * set value.
     *
     * @param key
     * @param value
     */
    public void set(String key, Object value) {
        values.put(key, value);
    }

    /**
     * get value.
     *
     * @param key
     * @return value
     */
    public Object get(String key) {
        return values.get(key);
    }

    /**
     * get active.
     *
     * @return active
     */
    public int getActive() {
        return active.get();
    }

    /**
     * get total.
     *
     * @return total
     */
    public long getTotal() {
        return total.longValue();
    }

    /**
     * get total elapsed.
     *
     * @return total elapsed
     */
    public long getTotalElapsed() {
        return totalElapsed.get();
    }

    /**
     * get average elapsed.
     *
     * @return average elapsed
     */
    public long getAverageElapsed() {
        long total = getTotal();
        if (total == 0) {
            return 0;
        }
        return getTotalElapsed() / total;
    }

    /**
     * get max elapsed.
     *
     * @return max elapsed
     */
    public long getMaxElapsed() {
        return maxElapsed.get();
    }

    /**
     * get failed.
     *
     * @return failed
     */
    public int getFailed() {
        return failed.get();
    }

    /**
     * get failed elapsed.
     *
     * @return failed elapsed
     */
    public long getFailedElapsed() {
        return failedElapsed.get();
    }

    /**
     * get failed average elapsed.
     *
     * @return failed average elapsed
     */
    public long getFailedAverageElapsed() {
        long failed = getFailed();
        if (failed == 0) {
            return 0;
        }
        return getFailedElapsed() / failed;
    }

    /**
     * get failed max elapsed.
     *
     * @return failed max elapsed
     */
    public long getFailedMaxElapsed() {
        return failedMaxElapsed.get();
    }

    /**
     * get succeeded.
     *
     * @return succeeded
     */
    public long getSucceeded() {
        return getTotal() - getFailed();
    }

    /**
     * get succeeded elapsed.
     *
     * @return succeeded elapsed
     */
    public long getSucceededElapsed() {
        return getTotalElapsed() - getFailedElapsed();
    }

    /**
     * get succeeded average elapsed.
     *
     * @return succeeded average elapsed
     */
    public long getSucceededAverageElapsed() {
        long succeeded = getSucceeded();
        if (succeeded == 0) {
            return 0;
        }
        return getSucceededElapsed() / succeeded;
    }

    /**
     * get succeeded max elapsed.
     *
     * @return succeeded max elapsed.
     */
    public long getSucceededMaxElapsed() {
        return succeededMaxElapsed.get();
    }

    /**
     * Calculate average TPS (Transaction per second).
     *
     * @return tps
     */
    public long getAverageTps() {
        if (getTotalElapsed() >= 1000L) {
            return getTotal() / (getTotalElapsed() / 1000L);
        }
        return getTotal();
    }


}
