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
package org.apache.dubbo.rpc.cluster.support.registry;

import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.support.AbstractClusterInvoker;
import org.apache.dubbo.rpc.cluster.support.wrapper.AbstractCluster;

/**
 * See {@link ZoneAwareClusterInvoker}
 * ZoneAwareCluster 这个 Cluster 实现类的扩展名为 zone-aware，
 * 在其 doJoin() 方法中创建的是 ZoneAwareClusterInvoker 类型的 Invoker 对象
 */
public class ZoneAwareCluster extends AbstractCluster {

    public static final String NAME = "zone-aware";

    @Override
    protected <T> AbstractClusterInvoker<T> doJoin(Directory<T> directory) throws RpcException {
        return new ZoneAwareClusterInvoker<T>(directory);
    }

}