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
package org.apache.dubbo.registry.zookeeper;

import org.apache.dubbo.registry.client.ServiceDiscovery;
import org.apache.dubbo.registry.client.event.ServiceInstancesChangedEvent;
import org.apache.dubbo.registry.client.event.listener.ServiceInstancesChangedListener;

import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import static org.apache.zookeeper.Watcher.Event.EventType.NodeChildrenChanged;
import static org.apache.zookeeper.Watcher.Event.EventType.NodeDataChanged;

/**
 * Zookeeper {@link ServiceDiscovery} Change {@link CuratorWatcher watcher} only interests in
 * {@link Watcher.Event.EventType#NodeChildrenChanged} and {@link Watcher.Event.EventType#NodeDataChanged} event types,
 * which will multicast a {@link ServiceInstancesChangedEvent} when the service node has been changed.
 *
 * 是 ZookeeperServiceDiscovery 配套的 CuratorWatcher 实现，
 * 其中 process() 方法实现会关注 NodeChildrenChanged 事件和 NodeDataChanged 事件，
 * 并调用关联的 ZookeeperServiceDiscovery 对象的 dispatchServiceInstancesChangedEvent() 方法
 * @since 2.7.5
 */
public class ZookeeperServiceDiscoveryChangeWatcher implements CuratorWatcher {
    private ServiceInstancesChangedListener listener;

    private final ZookeeperServiceDiscovery zookeeperServiceDiscovery;

    private volatile boolean keepWatching = true;

    private final String serviceName;

    public ZookeeperServiceDiscoveryChangeWatcher(ZookeeperServiceDiscovery zookeeperServiceDiscovery,
                                                  String serviceName,
                                                  ServiceInstancesChangedListener listener) {
        this.zookeeperServiceDiscovery = zookeeperServiceDiscovery;
        this.serviceName = serviceName;
        this.listener = listener;
    }

    /**
     * ZookeeperServiceDiscoveryChangeWatcher 的核心就是将 ZooKeeper 中的事件转换成了
     * Dubbo 内部的 ServiceInstancesChangedEvent 事件。
     * @param event
     * @throws Exception
     */
    @Override
    public void process(WatchedEvent event) throws Exception {

        // 获取监听到的事件类型
        Watcher.Event.EventType eventType = event.getType();

        // 这里只关注NodeChildrenChanged和NodeDataChanged两种事件类型
        if (NodeChildrenChanged.equals(eventType) || NodeDataChanged.equals(eventType)) {
            if (shouldKeepWatching()) {
                // 调用dispatchServiceInstancesChangedEvent()方法，分发ServiceInstancesChangedEvent事件
                listener.onEvent(new ServiceInstancesChangedEvent(serviceName, zookeeperServiceDiscovery.getInstances(serviceName)));
                zookeeperServiceDiscovery.registerServiceWatcher(serviceName, listener);
                // only the current registry will be queried, which may be pushed empty.
                // zookeeperServiceDiscovery.dispatchServiceInstancesChangedEvent(serviceName);
            }
        }
    }

    public boolean shouldKeepWatching() {
        return keepWatching;
    }

    public void stopWatching() {
        this.keepWatching = false;
    }
}
