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

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Inject;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.support.AbstractRegistryFactory;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;

/**
 * ZookeeperRegistryFactory.
 * ZookeeperRegistryFactory 实现了 AbstractRegistryFactory，
 * 其中的 createRegistry() 方法会创建 ZookeeperRegistry 实例，
 * 后续将由该 ZookeeperRegistry 实例完成与 Zookeeper 的交互。
 */
public class ZookeeperRegistryFactory extends AbstractRegistryFactory {

    private ZookeeperTransporter zookeeperTransporter;

    public ZookeeperRegistryFactory() {
        this.zookeeperTransporter = ZookeeperTransporter.getExtension();
    }

    /**
     * Invisible injection of zookeeper client via IOC/SPI
     * 这里标注了无效了
     * 提供了一个 setZookeeperTransporter() 方法，你可以回顾一下之前我们介绍的 Dubbo SPI 机制，会通过 SPI 或 Spring Ioc 的方式完成自动装载。
     * @param zookeeperTransporter
     */
    @Inject(enable = false)
    public void setZookeeperTransporter(ZookeeperTransporter zookeeperTransporter) {
        this.zookeeperTransporter = zookeeperTransporter;
    }

    @Override
    public Registry createRegistry(URL url) {
        return new ZookeeperRegistry(url, zookeeperTransporter);
    }

}
