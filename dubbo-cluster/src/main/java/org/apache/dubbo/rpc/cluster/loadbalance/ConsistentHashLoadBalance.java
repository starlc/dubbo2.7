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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;

/**
 * ConsistentHashLoadBalance
 * ConsistentHashLoadBalance 底层使用一致性 Hash 算法实现负载均衡
 *
 * 一致性 Hash 负载均衡可以让参数相同的请求每次都路由到相同的服务节点上，
 * 这种负载均衡策略可以在某些 Provider 节点下线的时候，
 * 让这些节点上的流量平摊到其他 Provider 上，不会引起流量的剧烈波动。
 *
 * 一致性 Hash 算法的原理也是取模算法，与 Hash 取模的不同之处在于：
 * Hash 取模是对 Provider 节点数量取模，而一致性 Hash 算法是对 2^32 取模。
 *
 * 一致性 Hash 算法需要同时对 Provider 地址以及请求参数进行取模：
 *
 * hash(Provider地址) % 2^32
 * hash(请求参数) % 2^32
 *
 * 数据倾斜的问题。所谓数据倾斜是指由于节点不够分散，导致大量请求落到了同一个节点上，而其他节点只会接收到少量请求的情况。
 * 为了解决一致性 Hash 算法中出现的数据倾斜问题，又演化出了 Hash 槽的概念。
 * Hash 槽解决数据倾斜的思路是：既然问题是由 Provider 节点在 Hash 环上分布不均匀造成的，
 * 那么可以虚拟出 n 组 P1、P2、P3 的 Provider 节点 ，让多组 Provider 节点相对均匀地分布在 Hash 环上。
 * 如下图所示，相同阴影的节点均为同一个 Provider 节点，比如 P1-1、P1-2……P1-99 表示的都是 P1 这个 Provider 节点。
 * 引入 Provider 虚拟节点之后，让 Provider 在圆环上分散开来，以避免数据倾斜问题。
 */
public class ConsistentHashLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "consistenthash";

    /**
     * Hash nodes name
     */
    public static final String HASH_NODES = "hash.nodes";

    /**
     * Hash arguments name
     */
    public static final String HASH_ARGUMENTS = "hash.arguments";

    private final ConcurrentMap<String, ConsistentHashSelector<?>> selectors = new ConcurrentHashMap<String, ConsistentHashSelector<?>>();

    /**
     *  doSelect() 方法的实现，其中会根据 ServiceKey 和 methodName 选择一个 ConsistentHashSelector 对象，
     *  核心算法都委托给 ConsistentHashSelector 对象完成。
     * @param invokers
     * @param url
     * @param invocation
     * @return
     * @param <T>
     */
    @SuppressWarnings("unchecked")
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // 获取调用的方法名称
        String methodName = RpcUtils.getMethodName(invocation);
        // 将ServiceKey和方法拼接起来，构成一个key
        String key = invokers.get(0).getUrl().getServiceKey() + "." + methodName;
        // using the hashcode of list to compute the hash only pay attention to the elements in the list
        // 注意：这是为了在invokers列表发生变化时都会重新生成ConsistentHashSelector对象
        int invokersHashCode = getCorrespondingHashCode(invokers);
        // 根据key获取对应的ConsistentHashSelector对象，selectors是一个ConcurrentMap<String, ConsistentHashSelector>集合
        ConsistentHashSelector<T> selector = (ConsistentHashSelector<T>) selectors.get(key);
        if (selector == null || selector.identityHashCode != invokersHashCode) {
            // 未查找到ConsistentHashSelector对象，则进行创建
            selectors.put(key, new ConsistentHashSelector<T>(invokers, methodName, invokersHashCode));
            selector = (ConsistentHashSelector<T>) selectors.get(key);
        }
        // 通过ConsistentHashSelector对象选择一个Invoker对象
        return selector.select(invocation);
    }

    /**
     * get hash code of invokers
     * Make this method to public in order to use this method in test case
     * @param invokers
     * @return
     */
    public <T> int getCorrespondingHashCode(List<Invoker<T>> invokers){
        return invokers.hashCode();
    }

    private static final class ConsistentHashSelector<T> {

        /**
         * 用于记录虚拟 Invoker 对象的 Hash 环。这里使用 TreeMap 实现 Hash 环，并将虚拟的 Invoker 对象分布在 Hash 环上。
         */
        private final TreeMap<Long, Invoker<T>> virtualInvokers;

        /**
         * 虚拟 Invoker 个数。
         */
        private final int replicaNumber;

        /**
         * Invoker 集合的 HashCode 值
         */
        private final int identityHashCode;

        /**
         * 需要参与 Hash 计算的参数索引。例如，argumentIndex = [0, 1, 2] 时，表示调用的目标方法的前三个参数要参与 Hash 计算。
         */
        private final int[] argumentIndex;

        /**
         * key: server(invoker) address
         * value: count of requests accept by certain server
         */
        private Map<String, AtomicLong> serverRequestCountMap = new ConcurrentHashMap<>();

        /**
         * count of total requests accept by all servers
         */
        private AtomicLong totalRequestCount;

        /**
         * count of current servers(invokers)
         */
        private int serverCount;

        /**
         * the ratio which allow count of requests accept by each server
         * overrate average (totalRequestCount/serverCount).
         * 1.5 is recommended, in the future we can make this param configurable
         */
        private static final double OVERLOAD_RATIO_THREAD = 1.5F;

        /**
         * 构建 Hash 槽；
         * 确认参与一致性 Hash 计算的参数，默认是第一个参数。
         * @param invokers
         * @param methodName
         * @param identityHashCode
         */
        ConsistentHashSelector(List<Invoker<T>> invokers, String methodName, int identityHashCode) {
            // 初始化virtualInvokers字段，也就是虚拟Hash槽
            this.virtualInvokers = new TreeMap<Long, Invoker<T>>();
            // 记录Invoker集合的hashCode，用该hashCode值来判断Provider列表是否发生了变化
            this.identityHashCode = identityHashCode;
            URL url = invokers.get(0).getUrl();
            // 从hash.nodes参数中获取虚拟节点的个数
            this.replicaNumber = url.getMethodParameter(methodName, HASH_NODES, 160);
            // 获取参与Hash计算的参数下标值，默认对第一个参数进行Hash运算
            String[] index = COMMA_SPLIT_PATTERN.split(url.getMethodParameter(methodName, HASH_ARGUMENTS, "0"));
            argumentIndex = new int[index.length];
            for (int i = 0; i < index.length; i++) {
                argumentIndex[i] = Integer.parseInt(index[i]);
            }
            // 构建虚拟Hash槽，默认replicaNumber=160，相当于在Hash槽上放160个槽位
            // 外层轮询40次，内层轮询4次，共40*4=160次，也就是同一节点虚拟出160个槽位
            for (Invoker<T> invoker : invokers) {
                String address = invoker.getUrl().getAddress();
                for (int i = 0; i < replicaNumber / 4; i++) {
                    // 对address + i进行md5运算，得到一个长度为16的字节数组
                    byte[] digest = Bytes.getMD5(address + i);
                    for (int h = 0; h < 4; h++) {
                        // h = 0 时，取 digest 中下标为 0~3 的 4 个字节进行位运算
                        // h = 1 时，取 digest 中下标为 4~7 的 4 个字节进行位运算
                        // h = 2 和 h = 3时，过程同上
                        long m = hash(digest, h);
                        virtualInvokers.put(m, invoker);
                    }
                }
            }

            totalRequestCount = new AtomicLong(0);
            serverCount = invokers.size();
            serverRequestCountMap.clear();
        }


        /**
         * 最后，请求会通过 ConsistentHashSelector.select() 方法选择合适的 Invoker 对象，
         * 其中会先对请求参数进行 md5 以及 Hash 运算，得到一个 Hash 值，
         * 然后再通过这个 Hash 值到 TreeMap 中查找目标 Invoker。
         * @param invocation
         * @return
         */
        public Invoker<T> select(Invocation invocation) {
            // 将参与一致性Hash的参数拼接到一起
            String key = toKey(invocation.getArguments());
            // 计算key的Hash值
            byte[] digest = Bytes.getMD5(key);
            // 匹配Invoker对象
            return selectForKey(hash(digest, 0));
        }

        private String toKey(Object[] args) {
            StringBuilder buf = new StringBuilder();
            for (int i : argumentIndex) {
                if (i >= 0 && i < args.length) {
                    buf.append(args[i]);
                }
            }
            return buf.toString();
        }

        private Invoker<T> selectForKey(long hash) {
            // 从virtualInvokers集合（TreeMap是按照Key排序的）中查找第一个节点值大于或等于传入Hash值的Invoker对象
            Map.Entry<Long, Invoker<T>> entry = virtualInvokers.ceilingEntry(hash);
            // 如果Hash值大于Hash环中的所有Invoker，则回到Hash环的开头，返回第一个Invoker对象
            if (entry == null) {
                entry = virtualInvokers.firstEntry();
            }

            String serverAddress = entry.getValue().getUrl().getAddress();

            /**
             * The following part of codes aims to select suitable invoker.
             * This part is not complete thread safety.
             * However, in the scene of consumer-side load balance,
             * thread race for this part of codes
             * (execution time cost for this part of codes without any IO or
             * network operation is very low) will rarely occur. And even in
             * extreme case, a few requests are assigned to an invoker which
             * is above OVERLOAD_RATIO_THREAD will not make a significant impact
             * on the effect of this new algorithm.
             * And make this part of codes synchronized will reduce efficiency of
             * every request. In my opinion, this is not worth. So it is not a
             * problem for this part is not complete thread safety.
             */
            double overloadThread = ((double) totalRequestCount.get() / (double) serverCount) * OVERLOAD_RATIO_THREAD;
            /**
             * Find a valid server node:
             * 1. Not have accept request yet
             * or
             * 2. Not have overloaded (request count already accept < thread (average request count * overloadRatioAllowed ))
             */
            while (serverRequestCountMap.containsKey(serverAddress)
                    && serverRequestCountMap.get(serverAddress).get() >= overloadThread) {
                /**
                 * If server node is not valid, get next node
                 */
                entry = getNextInvokerNode(virtualInvokers, entry);
                serverAddress = entry.getValue().getUrl().getAddress();
            }
            if (!serverRequestCountMap.containsKey(serverAddress)) {
                serverRequestCountMap.put(serverAddress, new AtomicLong(1));
            } else {
                serverRequestCountMap.get(serverAddress).incrementAndGet();
            }
            totalRequestCount.incrementAndGet();

            return entry.getValue();
        }

        private Map.Entry<Long, Invoker<T>> getNextInvokerNode(TreeMap<Long, Invoker<T>> virtualInvokers, Map.Entry<Long, Invoker<T>> entry){
            Map.Entry<Long, Invoker<T>> nextEntry = virtualInvokers.higherEntry(entry.getKey());
            if(nextEntry == null){
                return virtualInvokers.firstEntry();
            }
            return nextEntry;
        }

        private long hash(byte[] digest, int number) {
            return (((long) (digest[3 + number * 4] & 0xFF) << 24)
                    | ((long) (digest[2 + number * 4] & 0xFF) << 16)
                    | ((long) (digest[1 + number * 4] & 0xFF) << 8)
                    | (digest[number * 4] & 0xFF))
                    & 0xFFFFFFFFL;
        }
    }

}
