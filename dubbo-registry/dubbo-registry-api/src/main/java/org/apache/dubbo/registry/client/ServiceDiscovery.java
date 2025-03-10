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
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.common.utils.Page;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.event.EventDispatcher;
import org.apache.dubbo.event.EventListener;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.client.event.ServiceInstancesChangedEvent;
import org.apache.dubbo.registry.client.event.listener.ServiceInstancesChangedListener;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static org.apache.dubbo.event.EventDispatcher.getDefaultExtension;

/**
 * The common operations of Service Discovery
 * 在 Dubbo 创建 ServiceDiscovery 对象的时候，会通过 ServiceDiscoveryFactory 工厂类进行创建。
 * ServiceDiscoveryFactory 接口也是一个扩展接口，Dubbo 只提供了一个默认实现—— DefaultServiceDiscoveryFactory
 * @since 2.7.5
 */
@SPI("zookeeper")
public interface ServiceDiscovery extends Prioritized {

    // ==================================== Lifecycle ==================================== //

    /**
     * Initializes the {@link ServiceDiscovery}
     * // 初始化当前ServiceDiscovery实例，传入的是注册中心的URL
     * @param registryURL the {@link URL url} to connect service registry
     * @throws Exception If met with error
     */
    void initialize(URL registryURL) throws Exception;

    /**
     * Destroy the {@link ServiceDiscovery}
     * // 销毁当前ServiceDiscovery实例
     * @throws Exception If met with error
     */
    void destroy() throws Exception;

    // ==================================================================================== //

    // =================================== Registration =================================== //

    /**
     * Registers an instance of {@link ServiceInstance}.
     * // 发布传入的ServiceInstance实例
     * @param serviceInstance an instance of {@link ServiceInstance} to be registered
     * @throws RuntimeException if failed
     */
    void register(ServiceInstance serviceInstance) throws RuntimeException;

    /**
     * Updates the registered {@link ServiceInstance}.
     * 更新传入的ServiceInstance实例
     * @param serviceInstance the registered {@link ServiceInstance}
     * @throws RuntimeException if failed
     */
    void update(ServiceInstance serviceInstance) throws RuntimeException;

    /**
     * Unregisters an instance of {@link ServiceInstance}.
     * 注销传入的ServiceInstance实例
     * @param serviceInstance an instance of {@link ServiceInstance} to be unregistered
     * @throws RuntimeException if failed
     */
    void unregister(ServiceInstance serviceInstance) throws RuntimeException;

    // ==================================================================================== //

    // ==================================== Discovery ===================================== //

    /**
     * Get the default size of pagination query
     * 分页查询时默认每页的条数
     * @return the default value is 100
     */
    default int getDefaultPageSize() {
        return 100;
    }

    /**
     * Gets all service names
     *
     * 查询全部Service Name
     * @return non-null read-only {@link Set}
     */
    Set<String> getServices();

    /**
     * Gets all {@link ServiceInstance service instances} by the specified service name.
     * 根据ServiceName分页查询ServiceInstance
     * @param serviceName the service name
     * @return non-null {@link List}
     * @throws NullPointerException if <code>serviceName</code> is <code>null</code>
     */
    default List<ServiceInstance> getInstances(String serviceName) throws NullPointerException {

        List<ServiceInstance> allInstances = new LinkedList<>();

        int offset = 0;

        int pageSize = getDefaultPageSize();

        Page<ServiceInstance> page = getInstances(serviceName, offset, pageSize);

        allInstances.addAll(page.getData());

        while (page.hasNext()) {
            offset += page.getDataSize();
            page = getInstances(serviceName, offset, pageSize);
            allInstances.addAll(page.getData());
        }

        return unmodifiableList(allInstances);
    }

    /**
     * Gets the {@link Page pagination} of {@link ServiceInstance service instances} by the specified service name.
     * It's equal to {@link #getInstances(String, int, int, boolean)} with <code>healthyOnly == true</code>
     * 分页查询ServiceInstance
     * @param serviceName the service name
     * @param offset      the offset of request , the number "0" indicates first page
     * @param pageSize    the number of request, the {@link Integer#MAX_VALUE max value} indicates the range is unlimited
     * @return non-null {@link Page} object
     * @throws NullPointerException          if <code>serviceName</code> is <code>null</code>
     * @throws IllegalArgumentException      if <code>offset</code> or <code>pageSize</code> is negative number
     * @throws UnsupportedOperationException if not supported
     */
    default Page<ServiceInstance> getInstances(String serviceName, int offset, int pageSize) throws NullPointerException,
            IllegalArgumentException {
        return getInstances(serviceName, offset, pageSize, false);
    }

    /**
     * Get the {@link Page pagination} of {@link ServiceInstance service instances} by the specified service name.
     * If <code>healthyOnly == true</code>, filter healthy instances only.
     *
     * @param serviceName the service name
     * @param offset      the offset of request , the number "0" indicates first page
     * @param pageSize    the number of request, the {@link Integer#MAX_VALUE max value} indicates the range is unlimited
     * @param healthyOnly if <code>true</code> , filter healthy instances only
     * @return non-null {@link Page} object
     * @throws NullPointerException          if <code>serviceName</code> is <code>null</code>
     * @throws IllegalArgumentException      if <code>offset</code> or <code>pageSize</code> is negative number
     * @throws UnsupportedOperationException if not supported
     */
    default Page<ServiceInstance> getInstances(String serviceName, int offset, int pageSize, boolean healthyOnly) throws
            NullPointerException, IllegalArgumentException, UnsupportedOperationException {
        throw new UnsupportedOperationException("Current implementation does not support pagination query method.");
    }

    /**
     * batch-get all {@link ServiceInstance service instances} by the specified service names
     *
     * @param serviceNames the multiple service names
     * @param offset       the offset of request , the number "0" indicates first page
     * @param requestSize  the number of request, the {@link Integer#MAX_VALUE max value} indicates the range is unlimited
     * @return non-null read-only {@link Map} whose key is the service name and value is
     * the {@link Page pagination} of {@link ServiceInstance service instances}
     * @throws NullPointerException          if <code>serviceName</code> is <code>null</code>
     * @throws IllegalArgumentException      if <code>offset</code> or <code>requestSize</code> is negative number
     * @throws UnsupportedOperationException if not supported
     */
    default Map<String, Page<ServiceInstance>> getInstances(Iterable<String> serviceNames, int offset, int requestSize) throws
            NullPointerException, IllegalArgumentException {
        Map<String, Page<ServiceInstance>> instances = new LinkedHashMap<>();
        for (String serviceName : serviceNames) {
            instances.put(serviceName, getInstances(serviceName, offset, requestSize));
        }
        return unmodifiableMap(instances);
    }

    /**
     * Add an instance of {@link ServiceInstancesChangedListener} for specified service
     * <p>
     * Default, current method will be invoked by {@link ServiceDiscoveryRegistry#subscribe(URL, NotifyListener)
     * the ServiceDiscoveryRegistry on the subscription}, and it's mandatory to
     * {@link EventDispatcher#addEventListener(EventListener) add} the {@link ServiceInstancesChangedListener} argument
     * into {@link EventDispatcher} whether the subclass implements same approach or not, thus this method is used to
     * trigger or adapt the vendor's change notification mechanism typically, like Zookeeper Watcher,
     * Nacos EventListener. If the registry observes the change, It's suggested that the implementation could invoke
     * {@link #dispatchServiceInstancesChangedEvent(String)} method or variants
     * 添加ServiceInstance监听器
     * @param listener an instance of {@link ServiceInstancesChangedListener}
     * @throws NullPointerException
     * @throws IllegalArgumentException
     * @see EventPublishingServiceDiscovery
     * @see EventDispatcher
     */
    default void addServiceInstancesChangedListener(ServiceInstancesChangedListener listener)
            throws NullPointerException, IllegalArgumentException {
    }

    /**
     * unsubscribe to instances change event.
     * @param listener
     * @throws IllegalArgumentException
     */
    default void removeServiceInstancesChangedListener(ServiceInstancesChangedListener listener)
            throws IllegalArgumentException {
    }

    /**
     * Dispatch the {@link ServiceInstancesChangedEvent}
     * 触发ServiceInstancesChangedEvent事件
     * @param serviceName the name of service whose service instances have been changed
     */
    default void dispatchServiceInstancesChangedEvent(String serviceName) {
        dispatchServiceInstancesChangedEvent(serviceName, getInstances(serviceName));
    }

    /**
     * Dispatch the {@link ServiceInstancesChangedEvent}
     *
     * @param serviceName       the name of service whose service instances have been changed
     * @param otherServiceNames the names of other services
     */
    default void dispatchServiceInstancesChangedEvent(String serviceName, String... otherServiceNames) {
        dispatchServiceInstancesChangedEvent(serviceName, getInstances(serviceName));
        if (otherServiceNames != null) {
            Stream.of(otherServiceNames)
                    .filter(StringUtils::isNotEmpty)
                    .forEach(this::dispatchServiceInstancesChangedEvent);
        }
    }

    /**
     * Dispatch the {@link ServiceInstancesChangedEvent}
     *
     * @param serviceName      the name of service whose service instances have been changed
     * @param serviceInstances the service instances have been changed
     */
    default void dispatchServiceInstancesChangedEvent(String serviceName, List<ServiceInstance> serviceInstances) {
        dispatchServiceInstancesChangedEvent(new ServiceInstancesChangedEvent(serviceName, serviceInstances));
    }

    /**
     * Dispatch the {@link ServiceInstancesChangedEvent}
     *
     * @param event the {@link ServiceInstancesChangedEvent}
     */
    default void dispatchServiceInstancesChangedEvent(ServiceInstancesChangedEvent event) {
        getDefaultExtension().dispatch(event);
    }

    // ==================================================================================== //

//    String getKey(URL exportedURL);

    default URL getUrl() {
        return null;
    }

    ServiceInstance getLocalInstance();

    /**
     * A human-readable description of the implementation
     *
     * @return The description.
     */
    String toString();
}
