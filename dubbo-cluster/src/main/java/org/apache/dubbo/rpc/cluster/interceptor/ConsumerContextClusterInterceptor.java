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
package org.apache.dubbo.rpc.cluster.interceptor;

import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.support.AbstractClusterInvoker;

/**
 * 在 ConsumerContextClusterInterceptor 的 before() 方法中，
 * 会在 RpcContext 中设置当前 Consumer 地址、此次调用的 Invoker 等信息，
 * 同时还会删除之前与当前线程绑定的 Server Context。
 *
 * 在 after() 方法中，会删除本地 RpcContext 的信息。
 *
 *
 * 同时继承了 ClusterInterceptor.Listener 接口，在其 onMessage() 方法中，
 * 会获取响应中的 attachments 并设置到 RpcContext 中的 SERVER_LOCAL 之中，
 */
@Activate
public class ConsumerContextClusterInterceptor implements ClusterInterceptor, ClusterInterceptor.Listener {

    public static final String NAME = "context";

    @Override
    public void before(AbstractClusterInvoker<?> invoker, Invocation invocation) {
        // 获取当前线程绑定的RpcContext
        // 设置Invoker、Consumer地址等信息 context.setInvocation(invocation).setLocalAddress(NetUtils.getLocalHost(), 0);
        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(invoker);
        }
        RpcContext.removeServerContext();
    }

    @Override
    public void after(AbstractClusterInvoker<?> clusterInvoker, Invocation invocation) {
        RpcContext.removeContext(true);
    }

    @Override
    public void onMessage(Result appResponse, AbstractClusterInvoker<?> invoker, Invocation invocation) {
        // 从AppResponse中获取attachment，并设置到SERVER_LOCAL这个RpcContext中
        RpcContext.getServerContext().setObjectAttachments(appResponse.getObjectAttachments());
    }

    @Override
    public void onError(Throwable t, AbstractClusterInvoker<?> invoker, Invocation invocation) {

    }
}
