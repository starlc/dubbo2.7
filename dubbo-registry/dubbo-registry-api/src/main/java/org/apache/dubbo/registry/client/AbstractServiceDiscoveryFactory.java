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
package org.apache.dubbo.registry.client;

import org.apache.dubbo.common.URL;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Abstract {@link ServiceDiscoveryFactory} implementation with cache, the subclass
 * should implement {@link #createDiscovery(URL)} method to create an instance of {@link ServiceDiscovery}
 *
 * @see ServiceDiscoveryFactory
 * @since 2.7.5
 *
 * 维护了一个 ConcurrentMap<String, ServiceDiscovery> 类型的集合（discoveries 字段）来缓存 ServiceDiscovery 对象，
 * 并提供了一个 createDiscovery() 抽象方法来创建 ServiceDiscovery 实例。
 */
public abstract class AbstractServiceDiscoveryFactory implements ServiceDiscoveryFactory {

    private final ConcurrentMap<String, ServiceDiscovery> discoveries = new ConcurrentHashMap<>();

    @Override
    public ServiceDiscovery getServiceDiscovery(URL registryURL) {
        String key = registryURL.toServiceStringWithoutResolving();
        return discoveries.computeIfAbsent(key, k -> createDiscovery(registryURL));
    }

    protected abstract ServiceDiscovery createDiscovery(URL registryURL);
}
