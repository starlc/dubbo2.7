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
package org.apache.dubbo.rpc.cluster;

import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;

/**
 * Cluster. (SPI, Singleton, ThreadSafe)
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Computer_cluster">Cluster</a>
 * <a href="http://en.wikipedia.org/wiki/Fault-tolerant_system">Fault-Tolerant</a>
 * 主要功能是将多个 Provider 伪装成一个 Provider 供 Consumer 调用，
 * 其中涉及集群的容错处理、路由规则的处理以及负载均衡。
 *
 * 是集群容错的接口，主要是在某些 Provider 节点发生故障时，
 * 让 Consumer 的调用请求能够发送到正常的 Provider 节点，从而保证整个系统的可用性。
 *
 * Cluster 层的核心流程是这样的：
 * 当调用进入 Cluster 的时候，Cluster 会创建一个 AbstractClusterInvoker 对象，
 * 在这个 AbstractClusterInvoker 中，首先会从 Directory 中获取当前 Invoker 集合；
 * 然后按照 Router 集合进行路由，得到符合条件的 Invoker 集合；
 * 接下来按照 LoadBalance 指定的负载均衡策略得到最终要调用的 Invoker 对象。
 *
 * 如果调用失败，则会按照集群的容错策略进行容错处理。
 * Dubbo 默认内置了若干容错策略，并且每种容错策略都有自己独特的应用场景，我们可以通过配置选择不同的容错策略。
 * 如果这些内置容错策略不能满足需求，我们还可以通过自定义容错策略进行配置。
 *
 *
 * Cluster 的工作流程大致可以分为两步（如下图所示）：
 * ①创建 Cluster Invoker 实例（在 Consumer 初始化时，
 * Cluster 实现类会创建一个 Cluster Invoker 实例，即下图中的 merge 操作）；
 * ②使用 Cluster Invoker 实例（在 Consumer 服务消费者发起远程调用请求的时候，
 * Cluster Invoker 会依赖前面课时介绍的 Directory、Router、LoadBalance 等组件得到最终要调用的 Invoker 对象）。
 *
 * 这个过程是一个正常流程，没有涉及容错处理。Dubbo 中常见的容错方式有如下几个。
 *
 * Failover Cluster：失败自动切换。它是 Dubbo 的默认容错机制，在请求一个 Provider 节点失败的时候，
 * 自动切换其他 Provider 节点，默认执行 3 次，适合幂等操作。当然，重试次数越多，
 * 在故障容错的时候带给 Provider 的压力就越大，在极端情况下甚至可能造成雪崩式的问题。
 * Failback Cluster：失败自动恢复。失败后记录到队列中，通过定时器重试。
 * Failfast Cluster：快速失败。请求失败后返回异常，不进行任何重试。
 * Failsafe Cluster：失败安全。请求失败后忽略异常，不进行任何重试。
 * Forking Cluster：并行调用多个 Provider 节点，只要有一个成功就返回。
 * Broadcast Cluster：广播多个 Provider 节点，只要有一个节点失败就失败。
 * Available Cluster：遍历所有的 Provider 节点，找到每一个可用的节点，就直接调用。如果没有可用的 Provider 节点，则直接抛出异常。
 * Mergeable Cluster：请求多个 Provider 节点并将得到的结果进行合并。
 */
@SPI(Cluster.DEFAULT)
public interface Cluster {

    String DEFAULT = "failover";

    /**
     * Merge the directory invokers to a virtual invoker.
     *
     * @param <T>
     * @param directory
     * @return cluster invoker
     * @throws RpcException
     */
    @Adaptive
    <T> Invoker<T> join(Directory<T> directory) throws RpcException;

    static Cluster getCluster(String name) {
        return getCluster(name, true);
    }

    static Cluster getCluster(String name, boolean wrap) {
        if (StringUtils.isEmpty(name)) {
            name = Cluster.DEFAULT;
        }
        return ExtensionLoader.getExtensionLoader(Cluster.class).getExtension(name, wrap);
    }
}
