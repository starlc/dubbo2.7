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
package org.apache.dubbo.metadata;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.SPI;

import java.util.Set;

import static org.apache.dubbo.common.constants.CommonConstants.CONFIG_MAPPING_TYPE;
import static org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader;
import static org.apache.dubbo.common.utils.StringUtils.SLASH;
import static org.apache.dubbo.metadata.DynamicConfigurationServiceNameMapping.DEFAULT_MAPPING_GROUP;

/**
 * The interface for Dubbo service name Mapping
 * ServiceNameMapping 接口的主要功能是实现 Service ID 到 Service Name 之间的转换，
 * 底层会依赖配置中心实现数据存储和查询。
 * @since 2.7.5
 */
@SPI("config")
public interface ServiceNameMapping {

    /**
     * Map the specified Dubbo service interface, group, version and protocol to current Dubbo service name
     * // 服务接口、group、version、protocol四部分构成了Service ID，并与当前Service Name之间形成映射，记录到配置中心
     */
    void map(URL url);

    /**
     * Get the service names from the specified Dubbo service interface, group, version and protocol
     * // 根据服务接口、group、version、protocol四部分构成的Service ID，查询对应的Service Name
     *
     * @return
     */
    Set<String> getAndListen(URL url, MappingListener mappingListener);

    /**
     * Get the default extension of {@link ServiceNameMapping}
     *  // 获取默认的ServiceNameMapping接口的扩展实现
     * @return non-null {@link ServiceNameMapping}
     * @see DynamicConfigurationServiceNameMapping
     */
    static ServiceNameMapping getDefaultExtension() {
        return getExtensionLoader(ServiceNameMapping.class).getDefaultExtension();
    }

    static ServiceNameMapping getExtension(String name) {
        return getExtensionLoader(ServiceNameMapping.class).getExtension(name == null ? CONFIG_MAPPING_TYPE : name);
    }

    static String buildGroup(String serviceInterface) {
        //        the issue : https://github.com/apache/dubbo/issues/4671
        //        StringBuilder groupBuilder = new StringBuilder(serviceInterface)
        //                .append(KEY_SEPARATOR).append(defaultString(group))
        //                .append(KEY_SEPARATOR).append(defaultString(version))
        //                .append(KEY_SEPARATOR).append(defaultString(protocol));
        //        return groupBuilder.toString();
        return DEFAULT_MAPPING_GROUP + SLASH + serviceInterface;
    }
}
