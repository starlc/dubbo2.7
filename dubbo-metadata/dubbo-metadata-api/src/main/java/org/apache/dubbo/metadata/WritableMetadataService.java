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
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.rpc.model.ApplicationModel;

import static org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader;

/**
 * Local {@link MetadataService} that extends {@link MetadataService} and provides the modification, which is used for
 * Dubbo's consumers and providers.
 *
 * 在 MetadataService 接口中定义的都是查询元数据的方法，在其子接口 WritableMetadataService 中添加了一些发布元数据的写方法
 *
 * WritableMetadataService 接口被 @SPI 注解修饰，是一个扩展接口，
 * 在前面的继承关系图中也可以看出，它有两个比较基础的扩展实现，
 * 分别是 InMemoryWritableMetadataService（默认扩展实现）
 * 和 RemoteWritableMetadataServiceDelegate，对应扩展名分别是 local 和 remote
 * @since 2.7.5
 */
@SPI("default")
public interface WritableMetadataService extends MetadataService {
    /**
     * Gets the current Dubbo Service name
     * // ServiceName默认是从ApplicationModel中获取
     * // ExtensionLoader、DubboBootstrap以及ApplicationModel是单个Dubbo进程范围内的单例对象，
     * // ExtensionLoader用于Dubbo SPI机制加载扩展实现，DubboBootstrap用于启动Dubbo进程，
     * // ApplicationModel用于表示一个Dubbo实例，其中维护了多个ProviderModel对象表示当前Dubbo实例发布的服务，
     * // 维护了多个ConsumerModel对象表示当前Dubbo实例引用的服务。
     * @return non-null
     */
    @Override
    default String serviceName() {
        return ApplicationModel.getApplication();
    }

    /**
     * Exports a {@link URL}
     * // 发布该URL所代表的服务
     *
     * @param url a {@link URL}
     * @return If success , return <code>true</code>
     */
    boolean exportURL(URL url);

    /**
     * Unexports a {@link URL}
     * // 注销该URL所代表的服务
     * @param url a {@link URL}
     * @return If success , return <code>true</code>
     */
    boolean unexportURL(URL url);

    /**
     * Subscribes a {@link URL}
     * // 订阅该URL所代表的服务
     * @param url a {@link URL}
     * @return If success , return <code>true</code>
     */
    boolean subscribeURL(URL url);

    /**
     * Unsubscribes a {@link URL}
     * // 取消订阅该URL所代表的服务
     * @param url a {@link URL}
     * @return If success , return <code>true</code>
     */
    boolean unsubscribeURL(URL url);


    // 发布Provider端的ServiceDefinition
    void publishServiceDefinition(URL providerUrl);


    /**
     * Get {@link ExtensionLoader#getDefaultExtension() the defautl extension} of {@link WritableMetadataService}
     * // 获取WritableMetadataService的默认扩展实现
     * @return non-null
     */
    static WritableMetadataService getDefaultExtension() {
        return getExtensionLoader(WritableMetadataService.class).getDefaultExtension();
    }
}
