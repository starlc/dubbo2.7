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
package org.apache.dubbo.metadata.report;


import org.apache.dubbo.common.URL;
import org.apache.dubbo.metadata.MappingListener;
import org.apache.dubbo.metadata.MetadataInfo;
import org.apache.dubbo.metadata.definition.model.ServiceDefinition;
import org.apache.dubbo.metadata.report.identifier.MetadataIdentifier;
import org.apache.dubbo.metadata.report.identifier.ServiceMetadataIdentifier;
import org.apache.dubbo.metadata.report.identifier.SubscriberMetadataIdentifier;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 元数据中心是 Dubbo 2.7.0 版本之后新增的一项优化，其主要目的是将 URL 中的一部分内容
 * 存储到元数据中心，从而减少注册中心的压力。
 *
 * 元数据中心的数据只是给本端自己使用的，改动不需要告知对端，例如，Provider 修改了元数据，
 * 不需要实时通知 Consumer。这样，在注册中心存储的数据量减少的同时，
 * 还减少了因为配置修改导致的注册中心频繁通知监听者情况的发生，很好地减轻了注册中心的压力。
 *
 * MetadataReport 接口是 Dubbo 节点与元数据中心交互的桥梁
 *
 * 先来分析 AbstractMetadataReport 抽象类提供的公共实现，然后以 ZookeeperMetadataReport 这个具体实现为例，
 * 介绍 MetadataReport 如何与 ZooKeeper 配合实现元数据上报。
 */
public interface MetadataReport {
    /**
     * Service Definition -- START
     * // 存储Provider元数据
     **/
    void storeProviderMetadata(MetadataIdentifier providerMetadataIdentifier, ServiceDefinition serviceDefinition);

    /**
     *  // 查询ServiceDefinition
     * @param metadataIdentifier
     * @return
     */
    String getServiceDefinition(MetadataIdentifier metadataIdentifier);

    /**
     * Application Metadata -- START
     **/
    default void publishAppMetadata(SubscriberMetadataIdentifier identifier, MetadataInfo metadataInfo) {
    }

    default MetadataInfo getAppMetadata(SubscriberMetadataIdentifier identifier, Map<String, String> instanceMetadata) {
        return null;
    }

    /**
     * Service<-->Application Mapping -- START
     **/
    default Set<String> getServiceAppMapping(String serviceKey, MappingListener listener, URL url) {
        return Collections.emptySet();
    }

    default void registerServiceAppMapping(String serviceKey, String application, URL url) {
        return;
    }

    /**
     * deprecated or need triage
     * // 存储Consumer元数据
     **/
    void storeConsumerMetadata(MetadataIdentifier consumerMetadataIdentifier, Map<String, String> serviceParameterMap);

    /**
     * // 查询暴露的URL
     * @param metadataIdentifier
     * @return
     */
    List<String> getExportedURLs(ServiceMetadataIdentifier metadataIdentifier);

    /**
     * // 存储Service元数据
     * @param metadataIdentifier
     * @param url
     */
    void saveServiceMetadata(ServiceMetadataIdentifier metadataIdentifier, URL url);

    /**
     * 、删除Service元数据
     * @param metadataIdentifier
     */
    void removeServiceMetadata(ServiceMetadataIdentifier metadataIdentifier);

    void saveSubscribedData(SubscriberMetadataIdentifier subscriberMetadataIdentifier, Set<String> urls);

    /**
     * // 查询订阅数据
     * @param subscriberMetadataIdentifier
     * @return
     */
    List<String> getSubscribedURLs(SubscriberMetadataIdentifier subscriberMetadataIdentifier);

}
