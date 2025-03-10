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
package org.apache.dubbo.remoting.zookeeper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;

import static org.apache.dubbo.common.extension.ExtensionLoader.getExtensionLoader;

/**
 * ZookeeperTransporter 只负责一件事情，那就是创建 ZookeeperClient 对象。
 */
@SPI
public interface ZookeeperTransporter {

    String CURATOR_5 = "curator5";

    String CURATOR = "curator";

    ZookeeperClient connect(URL url);

    static ZookeeperTransporter getExtension() {
        ExtensionLoader<ZookeeperTransporter> extensionLoader = getExtensionLoader(ZookeeperTransporter.class);
        boolean isHighVersion = isHighVersionCurator();
        if (isHighVersion) {
            return extensionLoader.getExtension(CURATOR_5);
        }
        return extensionLoader.getExtension(CURATOR);
    }

    static boolean isHighVersionCurator() {
        try {
            Class.forName("org.apache.curator.framework.recipes.cache.CuratorCache");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

}
