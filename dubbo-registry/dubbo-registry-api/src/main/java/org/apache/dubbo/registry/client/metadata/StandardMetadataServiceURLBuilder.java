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
package org.apache.dubbo.registry.client.metadata;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.metadata.MetadataService;
import org.apache.dubbo.registry.client.ServiceInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.lang.String.valueOf;
import static org.apache.dubbo.common.constants.CommonConstants.CONSUMER;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PORT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;
import static org.apache.dubbo.metadata.MetadataConstants.DEFAULT_METADATA_TIMEOUT_VALUE;
import static org.apache.dubbo.metadata.MetadataConstants.METADATA_PROXY_TIMEOUT_KEY;
import static org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils.getMetadataServiceURLsParams;

/**
 * Standard Dubbo provider enabling introspection service discovery mode.
 * 根据 ServiceInstance.metadata 携带的 URL 参数、Service Name、ServiceInstance
 * 的 host 等信息构造 MetadataService 服务对应 URL
 * @see MetadataService
 * @since 2.7.5
 */
public class StandardMetadataServiceURLBuilder implements MetadataServiceURLBuilder {
    
    public static final String NAME = "standard";

    /**
     * Build the {@link URL urls} from {@link ServiceInstance#getMetadata() the metadata} of {@link ServiceInstance}
     *
     * @param serviceInstance {@link ServiceInstance}
     * @return the not-null {@link List}
     */
    @Override
    public List<URL> build(ServiceInstance serviceInstance) {

        // 从metadata集合中获取"dubbo.metadata-service.url-params"这个Key对应的Value值，
        // 这个Key是在MetadataServiceURLParamsMetadataCustomizer中写入的
        Map<String, Map<String, String>> paramsMap = getMetadataServiceURLsParams(serviceInstance);

        List<URL> urls = new ArrayList<>(paramsMap.size());

        // 获取Service Name
        String serviceName = serviceInstance.getServiceName();

        // 获取ServiceInstance监听的host
        String host = serviceInstance.getHost();

        // MetadataService接口可能被发布成多种协议，遍历paramsMap集合，为每种协议都生成对应的URL
        for (Map.Entry<String, Map<String, String>> entry : paramsMap.entrySet()) {
            String protocol = entry.getKey();
            Map<String, String> params = entry.getValue();
            int port = Integer.parseInt(params.get(PORT_KEY));
            URLBuilder urlBuilder = new URLBuilder()
                    .setHost(host)
                    .setPort(port)
                    .setProtocol(protocol)
                    .setPath(MetadataService.class.getName())
                    .addParameter(TIMEOUT_KEY, ConfigurationUtils.get(METADATA_PROXY_TIMEOUT_KEY, DEFAULT_METADATA_TIMEOUT_VALUE))
                    .addParameter(SIDE_KEY, CONSUMER);

            // add parameters
            params.forEach((name, value) -> urlBuilder.addParameter(name, valueOf(value)));

            // add the default parameters
            urlBuilder.addParameter(GROUP_KEY, serviceName);

            urls.add(urlBuilder.build());
        }

        return urls;
    }
}
