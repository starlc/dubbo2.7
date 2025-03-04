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
package org.apache.dubbo.rpc.protocol;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;

/**
 * abstract ProtocolSupport.
 * 提供了一些 Protocol 实现需要的公共能力以及公共字段，它的核心字段有如下三个
 *
 * AbstractProtocol 没有对 Protocol 的 export() 方法进行实现，对 refer()
 * 方法的实现也是委托给了 protocolBindingRefer() 这个抽象方法，然后由子类实现。
 *
 * AbstractProtocol 唯一实现的方法就是 destory() 方法，其首先会遍历 Invokers 集合，
 * 销毁全部的服务引用，然后遍历全部的 exporterMap 集合，销毁发布出去的服务，
 */
public abstract class AbstractProtocol implements Protocol {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 用于存储出去的服务集合，其中的 Key 通过 ProtocolUtils.serviceKey() 方法创建的服务标识，
     * 在 ProtocolUtils 中维护了多层的 Map 结构（如下图所示）。首先按照 group 分组，
     * 在实践中我们可以根据需求设置 group，例如，按照机房、地域等进行 group 划分，
     * 做到就近调用；在 GroupServiceKeyCache 中，依次按照 serviceName、serviceVersion、port 进行分类，
     * 最终缓存的 serviceKey 是前面三者拼接而成的。
     */
    protected final DelegateExporterMap exporterMap = new DelegateExporterMap();

    /**
     * <host:port, ProtocolServer>
     * 记录了全部的 ProtocolServer 实例，其中的 Key 是 host 和 port 组成的字符串，
     * Value 是监听该地址的 ProtocolServer。ProtocolServer 就是对 RemotingServer 的一层简单封装，表示一个服务端。
     */
    protected final Map<String, ProtocolServer> serverMap = new ConcurrentHashMap<>();

    //TODO SoftReference
    /**
     * 服务引用的集合。
     */
    protected final Set<Invoker<?>> invokers = new ConcurrentHashSet<Invoker<?>>();

    protected static String serviceKey(URL url) {
        int port = url.getParameter(Constants.BIND_PORT_KEY, url.getPort());
        return serviceKey(port, url.getPath(), url.getParameter(VERSION_KEY), url.getParameter(GROUP_KEY));
    }

    protected static String serviceKey(int port, String serviceName, String serviceVersion, String serviceGroup) {
        return ProtocolUtils.serviceKey(port, serviceName, serviceVersion, serviceGroup);
    }

    @Override
    public List<ProtocolServer> getServers() {
        return Collections.unmodifiableList(new ArrayList<>(serverMap.values()));
    }

    @Override
    public void destroy() {
        for (Invoker<?> invoker : invokers) {
            if (invoker != null) {
                invokers.remove(invoker);
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Destroy reference: " + invoker.getUrl());
                    }
                    invoker.destroy();// 关闭全部的服务引用
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
        for (Map.Entry<String, Exporter<?>> item : exporterMap.getExporterMap().entrySet()) {
            if (exporterMap.removeExportMap(item.getKey(), item.getValue())) {
                try {
                    if (logger.isInfoEnabled()) {
                        logger.info("Unexport service: " + item.getValue().getInvoker().getUrl());
                    }
                    item.getValue().unexport();// 关闭暴露出去的服务
                } catch (Throwable t) {
                    logger.warn(t.getMessage(), t);
                }
            }
        }
    }

    /**
     * Dubbo 会将 DubboProtocol.protocolBindingRefer() 方法返回的 Invoker
     * 对象（即 DubboInvoker 对象）用 AsyncToSyncInvoker 封装一层。
     *
     * refer() 方法的实现也是委托给了 protocolBindingRefer() 这个抽象方法
     * @param type Service class
     * @param url  URL address for the remote service
     * @return
     * @param <T>
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        return new AsyncToSyncInvoker<>(protocolBindingRefer(type, url));
    }

    protected abstract <T> Invoker<T> protocolBindingRefer(Class<T> type, URL url) throws RpcException;

    public Map<String, Exporter<?>> getExporterMap() {
        return exporterMap.getExporterMap();
    }

    public Collection<Exporter<?>> getExporters() {
        return exporterMap.getExporters();
    }
}
