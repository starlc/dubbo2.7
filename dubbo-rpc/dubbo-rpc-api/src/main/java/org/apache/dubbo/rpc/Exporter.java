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

/**
 * Exporter. (API/SPI, Prototype, ThreadSafe)
 * Exporter 暴露 Invoker 的实现，说白了，就是让 Provider 能够根据请求的各种信息，找到对应的 Invoker。
 * 我们可以维护一个 Map，其中 Key 可以根据请求中的信息构建，Value 为封装相应服务 Bean 的 Exporter 对象，
 * 这样就可以实现上述服务发布的要求了
 * @see org.apache.dubbo.rpc.Protocol#export(Invoker)
 * @see org.apache.dubbo.rpc.ExporterListener
 * @see org.apache.dubbo.rpc.protocol.AbstractExporter
 */
public interface Exporter<T> {

    /**
     * get invoker.
     * 获取底层封装的Invoker对象
     * @return invoker
     */
    Invoker<T> getInvoker();

    /**
     * unexport.
     * <p>
     * <code>
     * getInvoker().destroy();
     * </code>
     * 取消发布底层的Invoker对象
     */
    void unexport();

}