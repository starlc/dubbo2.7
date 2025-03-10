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
package org.apache.dubbo.metadata.store.zookeeper;

import com.google.gson.Gson;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.metadata.MappingChangedEvent;
import org.apache.dubbo.metadata.MappingListener;
import org.apache.dubbo.metadata.MetadataInfo;
import org.apache.dubbo.metadata.report.identifier.BaseMetadataIdentifier;
import org.apache.dubbo.metadata.report.identifier.KeyTypeEnum;
import org.apache.dubbo.metadata.report.identifier.MetadataIdentifier;
import org.apache.dubbo.metadata.report.identifier.ServiceMetadataIdentifier;
import org.apache.dubbo.metadata.report.identifier.SubscriberMetadataIdentifier;
import org.apache.dubbo.metadata.report.support.AbstractMetadataReport;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;

/**
 * ZookeeperMetadataReport
 *
 * 在 ZookeeperMetadataReport 中维护了一个 ZookeeperClient 实例用来和 ZooKeeper 进行交互。
 * ZookeeperMetadataReport 读写元数据的根目录是 metadataReportURL 的 group 参数值，默认值为 dubbo。
 */
public class ZookeeperMetadataReport extends AbstractMetadataReport {

    private final String root;

    /**
     * ZookeeperClient 实例用来和 ZooKeeper 进行交互
     */
    final ZookeeperClient zkClient;

    private Gson gson = new Gson();

    private Map<String, ChildListener> listenerMap = new ConcurrentHashMap<>();

    public ZookeeperMetadataReport(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        this.root = group;
        zkClient = zookeeperTransporter.connect(url);
    }

    String toRootDir() {
        if (root.equals(PATH_SEPARATOR)) {
            return root;
        }
        return root + PATH_SEPARATOR;
    }

    @Override
    protected void doStoreProviderMetadata(MetadataIdentifier providerMetadataIdentifier, String serviceDefinitions) {
        storeMetadata(providerMetadataIdentifier, serviceDefinitions);
    }

    @Override
    protected void doStoreConsumerMetadata(MetadataIdentifier consumerMetadataIdentifier, String value) {
        storeMetadata(consumerMetadataIdentifier, value);
    }

    /**
     * doSaveMetadata()、doRemoveMetadata() 以及 doGetExportedURLs() 方法参数是
     * ServiceMetadataIdentifier 对象，对应的 ZNode 节点 path 是：
     *
     * /dubbo/metadata/服务接口/version/group/side/protocol/revision
     * @param metadataIdentifier
     * @param url
     */
    @Override
    protected void doSaveMetadata(ServiceMetadataIdentifier metadataIdentifier, URL url) {
        zkClient.create(getNodePath(metadataIdentifier), URL.encode(url.toFullString()), false);
    }

    @Override
    protected void doRemoveMetadata(ServiceMetadataIdentifier metadataIdentifier) {
        zkClient.delete(getNodePath(metadataIdentifier));
    }

    @Override
    protected List<String> doGetExportedURLs(ServiceMetadataIdentifier metadataIdentifier) {
        String content = zkClient.getContent(getNodePath(metadataIdentifier));
        if (StringUtils.isEmpty(content)) {
            return Collections.emptyList();
        }
        return new ArrayList<String>(Arrays.asList(URL.decode(content)));
    }

    /**
     * doSaveSubscriberData()、doGetSubscribedURLs() 方法的参数是 SubscriberMetadataIdentifier 对象，
     * 对应的 ZNode 节点 path 是：
     *
     * /dubbo/metadata/服务接口/version/group/side/revision
     * @param subscriberMetadataIdentifier
     * @param urls
     */
    @Override
    protected void doSaveSubscriberData(SubscriberMetadataIdentifier subscriberMetadataIdentifier, String urls) {
        zkClient.create(getNodePath(subscriberMetadataIdentifier), urls, false);
    }

    @Override
    protected String doGetSubscribedURLs(SubscriberMetadataIdentifier subscriberMetadataIdentifier) {
        return zkClient.getContent(getNodePath(subscriberMetadataIdentifier));
    }

    @Override
    public String getServiceDefinition(MetadataIdentifier metadataIdentifier) {
        return zkClient.getContent(getNodePath(metadataIdentifier));
    }

    /**
     * MetadataIdentifier 对象对应 ZNode 节点的 path 默认格式是 :
     *
     * /dubbo/metadata/服务接口/version/group/side/application
     * @param metadataIdentifier
     * @param v
     */
    private void storeMetadata(MetadataIdentifier metadataIdentifier, String v) {
        zkClient.create(getNodePath(metadataIdentifier), v, false);
    }

    String getNodePath(BaseMetadataIdentifier metadataIdentifier) {
        return toRootDir() + metadataIdentifier.getUniqueKey(KeyTypeEnum.PATH);
    }

    @Override
    public void publishAppMetadata(SubscriberMetadataIdentifier identifier, MetadataInfo metadataInfo) {
        String path = getNodePath(identifier);
        if (StringUtils.isBlank(zkClient.getContent(path))) {
            zkClient.create(path, gson.toJson(metadataInfo), false);
        }
    }

    @Override
    public void registerServiceAppMapping(String serviceKey, String application, URL url) {
        String path = toRootDir() + serviceKey + PATH_SEPARATOR + application;
        if (StringUtils.isBlank(zkClient.getContent(path))) {
            Map<String, String> value = new HashMap<>();
            value.put("timestamp", String.valueOf(System.currentTimeMillis()));
            zkClient.create(path, gson.toJson(value), false);
        }
    }

    @Override
    public MetadataInfo getAppMetadata(SubscriberMetadataIdentifier identifier, Map<String, String> instanceMetadata) {
        String content = zkClient.getContent(getNodePath(identifier));
        return gson.fromJson(content, MetadataInfo.class);
    }

    @Override
    public Set<String> getServiceAppMapping(String serviceKey, MappingListener listener, URL url) {
        Set<String>  appNameSet = new HashSet<>();
        String path = toRootDir() + serviceKey;

        List<String> appNameList;

        if (null == listenerMap.get(path)) {
            zkClient.create(path, false);
            appNameList = addServiceMappingListener(path, serviceKey, listener);
        } else {
            appNameList = zkClient.getChildren(path);
        }

        if (!CollectionUtils.isEmpty(appNameList)) {
            appNameSet.addAll(appNameList);
        }

        return appNameSet;
    }

    private List<String> addServiceMappingListener(String path, String serviceKey, MappingListener listener) {
        ChildListener zkListener = new ChildListener() {
            @Override
            public void childChanged(String path, List<String> children) {
                MappingChangedEvent event = new MappingChangedEvent();
                event.setServiceKey(serviceKey);
                event.setApps(null != children ? new HashSet<>(children) : null);
                listener.onEvent(event);
            }
        };
        List<String> childNodes = zkClient.addChildListener(path, zkListener);
        listenerMap.put(path, zkListener);
        return childNodes;
    }
}
