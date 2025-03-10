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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

import java.util.Collections;
import java.util.List;

/**
 * Protocol. (API/SPI, Singleton, ThreadSafe)
 * 整个 Dubbo Protocol 层的核心接口之一，其中定义了 export() 和 refer() 两个核心方法
 * 在 Protocol 接口的实现中，export() 方法并不是简单地将 Invoker 对象包装成 Exporter 对象返回，
 * 其中还涉及代理对象的创建、底层 Server 的启动等操作；refer() 方法除了根据传入的 type 类型以及
 * URL 参数查询 Invoker 之外，还涉及相关 Client 的创建等操作。
 */
@SPI("dubbo")
public interface Protocol {

    /**
     * Get default port when user doesn't config the port.
     * 默认端口
     * @return default port
     */
    int getDefaultPort();

    /**
     * Export service for remote invocation: <br>
     * 1. Protocol should record request source address after receive a request:
     * RpcContext.getContext().setRemoteAddress();<br>
     * 2. export() must be idempotent, that is, there's no difference between invoking once and invoking twice when
     * export the same URL<br>
     * 3. Invoker instance is passed in by the framework, protocol needs not to care <br>
     *
     * 1.将一个Invoker暴露出去，export()方法实现需要是幂等的，
     *   即同一个服务暴露多次和暴露一次的效果是相同的
     * @param <T>     Service type
     * @param invoker Service invoker
     * @return exporter reference for exported service, useful for unexport the service later
     * @throws RpcException thrown when error occurs during export the service, for example: port is occupied
     */
    @Adaptive
    <T> Exporter<T> export(Invoker<T> invoker) throws RpcException;

    /**
     * Refer a remote service: <br>
     * 1. When user calls `invoke()` method of `Invoker` object which's returned from `refer()` call, the protocol
     * needs to correspondingly execute `invoke()` method of `Invoker` object <br>
     * 2. It's protocol's responsibility to implement `Invoker` which's returned from `refer()`. Generally speaking,
     * protocol sends remote request in the `Invoker` implementation. <br>
     * 3. When there's check=false set in URL, the implementation must not throw exception but try to recover when
     * connection fails.
     * 1.引用一个Invoker，refer()方法会根据参数返回一个Invoker对象
     *   Consumer端可以通过这个Invoker请求到Provider端的服务
     * @param <T>  Service type
     * @param type Service class
     * @param url  URL address for the remote service
     * @return invoker service's local proxy
     * @throws RpcException when there's any error while connecting to the service provider
     */
    @Adaptive
    <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException;

    /**
     * Destroy protocol: <br>
     * 1. Cancel all services this protocol exports and refers <br>
     * 2. Release all occupied resources, for example: connection, port, etc. <br>
     * 3. Protocol can continue to export and refer new service even after it's destroyed.
     *
     * 销毁export()方法以及refer()方法使用到的Invoker对象，释放当前Protocol对象底层占用的资源
     */
    void destroy();

    /**
     * Get all servers serving this protocol
     * 返回当前Protocol底层的全部ProtocolServer
     * @return
     */
    default List<ProtocolServer> getServers() {
        return Collections.emptyList();
    }

}