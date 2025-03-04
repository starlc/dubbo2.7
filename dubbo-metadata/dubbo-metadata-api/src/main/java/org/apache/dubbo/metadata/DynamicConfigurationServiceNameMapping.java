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
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static java.lang.String.valueOf;
import static java.util.Arrays.asList;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.rpc.model.ApplicationModel.getName;

/**
 * The {@link ServiceNameMapping} implementation based on {@link DynamicConfiguration}
 * DynamicConfigurationServiceNameMapping 是 ServiceNameMapping 的默认实现，也是唯一实现，
 * 其中会依赖 DynamicConfiguration 读写配置中心，
 * 完成 Service ID 和 Service Name 的映射。
 */
public class DynamicConfigurationServiceNameMapping implements ServiceNameMapping {

    public static String DEFAULT_MAPPING_GROUP = "mapping";

    private static final List<String> IGNORED_SERVICE_INTERFACES = asList(MetadataService.class.getName());

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public void map(URL url) {
        // 跳过MetadataService接口的处理
        String serviceInterface = url.getServiceInterface();
        String group = url.getParameter(GROUP_KEY);

        if (IGNORED_SERVICE_INTERFACES.contains(serviceInterface)) {
            return;
        }

        // 获取DynamicConfiguration对象
        DynamicConfiguration dynamicConfiguration = DynamicConfiguration.getDynamicConfiguration();

        // the Dubbo Service Key as group
        // the service(application) name as key
        // It does matter whatever the content is, we just need a record
        // 从ApplicationModel中获取Service Name
        String key = getName();
        String content = valueOf(System.currentTimeMillis());

        execute(() -> {
            // 在配置中心创建映射关系，这里的buildGroup()方法虽然接收四个参数，但是只使用了serviceInterface
            // 也就是使用创建了服务接口到Service Name的映射
            // 可以暂时将配置中心理解为一个KV存储，这里的Key是buildGroup()方法返回值+Service Name构成的，value是content（即时间戳）
            dynamicConfiguration.publishConfig(key, ServiceNameMapping.buildGroup(serviceInterface), content);
            if (logger.isInfoEnabled()) {
                logger.info(String.format("Dubbo service[%s] mapped to interface name[%s].",
                        group, serviceInterface));
            }
        });
    }

    @Override
    public Set<String> getAndListen(URL url, MappingListener mappingListener) {
        String serviceInterface = url.getServiceInterface();
        // 获取DynamicConfiguration对象
        DynamicConfiguration dynamicConfiguration = DynamicConfiguration.getDynamicConfiguration();

        // 根据Service ID从配置查找Service Name
        Set<String> serviceNames = new LinkedHashSet<>();
        execute(() -> {
            Set<String> keys = dynamicConfiguration
                    .getConfigKeys(ServiceNameMapping.buildGroup(serviceInterface));
            if (CollectionUtils.isNotEmpty(keys)) {
                serviceNames.addAll(keys);
            }
        });
        // 返回查找到的全部Service Name
        return Collections.unmodifiableSet(serviceNames);
    }

    private void execute(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable e) {
            if (logger.isWarnEnabled()) {
                logger.warn(e.getMessage(), e);
            }
        }
    }
}
