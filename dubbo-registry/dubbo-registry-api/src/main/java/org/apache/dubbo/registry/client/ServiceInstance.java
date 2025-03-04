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

import java.io.Serializable;
import java.util.Map;

/**
 * The model class of an instance of a service, which is used for service registration and discovery.
 * <p>
 * Service Instance 唯一标识一个服务实例 这里的服务实例指一个服务节点
 * @since 2.7.5
 */
public interface ServiceInstance extends Serializable {

    /**
     * The id of the registered service instance.
     * 唯一标识
     *
     * @return nullable
     */
    String getId();

    /**
     * The name of service that current instance belongs to.
     * 获取当前ServiceInstance所属的Service Name
     * @return non-null
     */
    String getServiceName();

    /**
     * The hostname of the registered service instance.
     * 获取当前ServiceInstance的host
     * @return non-null
     */
    String getHost();

    /**
     * The port of the registered service instance.
     * 获取当前ServiceInstance的port
     * @return the positive integer if present
     */
    Integer getPort();

    String getAddress();

    /**
     * The enable status of the registered service instance.
     *
     * @return if <code>true</code>, indicates current instance is enabled, or disable, the client should remove this one.
     * The default value is <code>true</code>
     * // 当前ServiceInstance的状态
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * The registered service instance is health or not.
     *
     * @return if <code>true</code>, indicates current instance is enabled, or disable, the client may ignore this one.
     * The default value is <code>true</code>
     * // 检测当前ServiceInstance的状态
     */
    default boolean isHealthy() {
        return true;
    }

    /**
     * The key / value pair metadata associated with the service instance.
     *  // 获取当前ServiceInstance关联的元数据，这些元数据以KV格式存储
     * @return non-null, mutable and unsorted {@link Map}
     */
    Map<String, String> getMetadata();

    Map<String, String> getExtendParams();

    Map<String, String> getAllParams();

    /**
     * Get the value of metadata by the specified name
     *
     * @param name the specified name
     * @return the value of metadata if found, or <code>null</code>
     * @since 2.7.8
     */
    default String getMetadata(String name) {
        return getMetadata(name, null);
    }

    /**
     * Get the value of metadata by the specified name
     *
     * @param name the specified name
     * @return the value of metadata if found, or <code>defaultValue</code>
     * @since 2.7.8
     */
    default String getMetadata(String name, String defaultValue) {
        return getMetadata().getOrDefault(name, defaultValue);
    }

    /**
     * @return the hash code of current instance.
     *  计算当前ServiceInstance对象的hashCode值
     */
    int hashCode();

    /**
     * @param another another {@link ServiceInstance}
     * @return if equals , return <code>true</code>, or <code>false</code>
     * 比较两个ServiceInstance对象
     */
    boolean equals(Object another);

    InstanceAddressURL toURL();

}
