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
package org.apache.dubbo.rpc.filter;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.AsyncRpcResult;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import static org.apache.dubbo.rpc.Constants.$ECHO;

/**
 * Dubbo provided default Echo echo service, which is available for all dubbo provider service interface.
 * 判断方法名是否带$echo 且参数为1个 如果是 立即返回一个AsyncRpcResult
 *
 * EchoFilter 用于支持 回声测试（Echo Test），即通过发送一个简单的请求来检测服务是否可用。
 * 回声测试：客户端发送一个测试请求（通常是 $echo 方法），服务端直接返回相同的请求数据。
 *
 * 用途：
 * 检测服务提供者是否在线。
 * 验证网络连接是否正常。
 * 用于健康检查或监控。
 *
 * (2) 实现机制
 * 拦截请求：当客户端调用 $echo 方法时，EchoFilter 会拦截该请求。
 *
 * 直接返回：服务端不执行实际业务逻辑，而是直接将请求参数返回给客户端。
 */
@Activate(group = CommonConstants.PROVIDER, order = -110000)
public class EchoFilter implements Filter {

    @Override
    public Result invoke(Invoker<?> invoker, Invocation inv) throws RpcException {
        if (inv.getMethodName().equals($ECHO) && inv.getArguments() != null && inv.getArguments().length == 1) {
            return AsyncRpcResult.newDefaultAsyncResult(inv.getArguments()[0], inv);
        }
        return invoker.invoke(inv);
    }

}
