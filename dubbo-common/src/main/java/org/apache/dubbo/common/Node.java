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
package org.apache.dubbo.common;

/**
 * Node. (API/SPI, Prototype, ThreadSafe)
 * 在 Dubbo 中，一般使用 Node 这个接口来抽象节点的概念。
 * Node不仅可以表示 Provider 和 Consumer 节点，还可以表示注册中心节点。Node 接口中定义了三个非常基础的方法
 */
public interface Node {

    /**
     * get url.
     * 返回表示当前节点的 URL；
     * @return url.
     */
    URL getUrl();

    /**
     * is available.
     * 检测当前节点是否可用；
     * @return available.
     */
    boolean isAvailable();

    /**
     * destroy.
     * 负责销毁当前节点并释放底层资源。
     */
    void destroy();

}