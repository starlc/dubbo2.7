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
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.ListenableFilter;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/**
 * @see org.apache.dubbo.rpc.protocol.ProtocolFilterWrapper
 * 将原本在org.apache.dubbo.rpc.protocol.ProtocolFilterWrapper#buildInvokerChain(org.apache.dubbo.rpc.Invoker, java.lang.String, java.lang.String)
 * 中的逻辑封装成了一个类，主要提供invoke实现
 */
class FilterNode<T> implements Invoker<T>{
    private final Invoker<T> invoker;
    private final Invoker<T> next;
    private final Filter filter;
    
    public FilterNode(final Invoker<T> invoker, final Invoker<T> next, final Filter filter) {
        this.invoker = invoker;
        this.next = next;
        this.filter = filter;
    }

    @Override
    public Class<T> getInterface() {
        return invoker.getInterface();
    }

    @Override
    public URL getUrl() {
        return invoker.getUrl();
    }

    @Override
    public boolean isAvailable() {
        return invoker.isAvailable();
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        Result asyncResult;
        try {
            // 调用 Filter 的 invoke() 方法执行 Filter 的逻辑，
            // 然后由 Filter 内部的逻辑决定是否将调用传递到下一个 Filter 执行
            asyncResult = filter.invoke(next, invocation);
        } catch (Exception e) {

            // 当 invoke() 方法执行出现异常时，会调用该 Listener 的 onError() 方法进行通知。
            if (filter instanceof ListenableFilter) {
                ListenableFilter listenableFilter = ((ListenableFilter) filter);
                try {
                    Filter.Listener listener = listenableFilter.listener(invocation);
                    if (listener != null) {
                        listener.onError(e, invoker, invocation);
                    }
                } finally {
                    listenableFilter.removeListener(invocation);
                }
            } else if (filter instanceof Filter.Listener) {
                Filter.Listener listener = (Filter.Listener) filter;
                listener.onError(e, invoker, invocation);
            }
            throw e;
        } finally {

        }
        return asyncResult.whenCompleteWithContext((r, t) -> {// 省略监听器的处理逻辑
            // 当 invoke() 方法执行正常结束时，会调用该 Listener 的 onResponse() 方法进行通知；
            if (filter instanceof ListenableFilter) {
                ListenableFilter listenableFilter = ((ListenableFilter) filter);
                Filter.Listener listener = listenableFilter.listener(invocation);
                try {
                    if (listener != null) {
                        if (t == null) {
                            listener.onResponse(r, invoker, invocation);
                        } else {
                            listener.onError(t, invoker, invocation);
                        }
                    }
                } finally {
                    listenableFilter.removeListener(invocation);
                }
            } else if (filter instanceof Filter.Listener) {
                Filter.Listener listener = (Filter.Listener) filter;
                if (t == null) {
                    listener.onResponse(r, invoker, invocation);
                } else {
                    listener.onError(t, invoker, invocation);
                }
            }
        });
    }

    @Override
    public void destroy() {
        invoker.destroy();
    }

    @Override
    public String toString() {
        return invoker.toString();
    }

}
