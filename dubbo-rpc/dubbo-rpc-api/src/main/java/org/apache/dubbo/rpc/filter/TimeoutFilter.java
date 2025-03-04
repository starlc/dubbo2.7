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
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.AppResponse;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.TimeoutCountDown;

import static org.apache.dubbo.common.constants.CommonConstants.TIME_COUNTDOWN_KEY;

/**
 * Log any invocation timeout, but don't stop server from running
 * 在前文介绍 ConsumerContextFilter 的时候可以看到，如果通过 TIME_COUNTDOWN_KEY
 * 在 RpcContext 中配置了 TimeCountDown，就会对 TimeoutCountDown 进行检查，
 * 判定此次请求是否超时。然后，在 DubboInvoker 的 doInvoker() 方法实现中可以看到，
 * 在发起请求之前会调用 calculateTimeout() 方法确定该请求还有多久过期
 *
 *
 * 当请求到达 Provider 时，ContextFilter 会根据 Invocation 中的 attachment
 * 恢复 RpcContext 的attachment，其中就包含 TIMEOUT_ATTACHENT_KEY（对应的 Value 会恢复成 TimeoutCountDown 对象）。
 *
 * TimeoutFilter 是 Provider 端另一个涉及超时时间的 Filter 实现，其 invoke() 方法实现比较简单，
 * 直接将请求转发给后续 Filter 处理。在 TimeoutFilter 对 onResponse() 方法的实现中，
 * 会从 RpcContext 中读取上述 TimeoutCountDown 对象，并检查此次请求是否超时。
 * 如果请求已经超时，则会将 AppResponse 中的结果清空，同时打印一条警告日志
 */
@Activate(group = CommonConstants.PROVIDER)
public class TimeoutFilter implements Filter, Filter.Listener {

    private static final Logger logger = LoggerFactory.getLogger(TimeoutFilter.class);

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        return invoker.invoke(invocation);
    }

    @Override
    public void onResponse(Result appResponse, Invoker<?> invoker, Invocation invocation) {
        Object obj = RpcContext.getContext().get(TIME_COUNTDOWN_KEY);
        if (obj != null) {
            TimeoutCountDown countDown = (TimeoutCountDown) obj;
            if (countDown.isExpired()) {// 检查结果是否超时
                // 清理结果信息
                ((AppResponse) appResponse).clear(); // clear response in case of timeout.
                if (logger.isWarnEnabled()) {
                    logger.warn("invoke timed out. method: " + invocation.getMethodName() +
                            " url is " + invoker.getUrl() + ", invoke elapsed " + countDown.elapsedMillis() + " ms.");
                }
            }
        }
    }

    @Override
    public void onError(Throwable t, Invoker<?> invoker, Invocation invocation) {

    }
}
