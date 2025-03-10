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
package org.apache.dubbo.rpc.listener;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

import java.util.List;

/**
 * ListenerInvoker
 *
 * ListenerInvokerWrapper 是 Invoker 的装饰器，
 * 其构造方法参数列表中除了被修饰的 Invoker 外，还有 InvokerListener 列表，
 * 在构造方法内部会遍历整个 InvokerListener 列表，
 * 并调用每个 InvokerListener 的 referred() 方法，通知它们 Invoker 被引用的事件
 *
 *
 * 在 ListenerInvokerWrapper.destroy() 方法中，
 * 首先会调用被修饰 Invoker 对象的 destroy() 方法，
 * 之后循环调用全部 InvokerListener 的 destroyed() 方法，
 * 通知它们该 Invoker 被销毁的事件
 */
public class ListenerInvokerWrapper<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory.getLogger(ListenerInvokerWrapper.class);

    // 底层被修饰的Invoker对象
    private final Invoker<T> invoker;

    // 监听器集合
    private final List<InvokerListener> listeners;

    public ListenerInvokerWrapper(Invoker<T> invoker, List<InvokerListener> listeners) {
        if (invoker == null) {
            throw new IllegalArgumentException("invoker == null");
        }
        this.invoker = invoker;// 底层被修饰的Invoker对象
        this.listeners = listeners;// 监听器集合
        if (CollectionUtils.isNotEmpty(listeners)) {
            for (InvokerListener listener : listeners) {
                if (listener != null) {
                    try {
                        listener.referred(invoker);// 在服务引用过程中触发全部InvokerListener监听器
                    } catch (Throwable t) {
                        logger.error(t.getMessage(), t);
                    }
                }
            }
        }
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
        return invoker.invoke(invocation);
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + (getUrl() == null ? " " : getUrl().toString());
    }

    /**
     * 首先会调用被修饰 Invoker 对象的 destroy() 方法，之后循环调用全部 InvokerListener 的 destroyed() 方法，通知它们该 Invoker 被销毁的事件
     */
    @Override
    public void destroy() {
        try {
            //被修饰 Invoker 对象的 destroy() 方法
            invoker.destroy();
        } finally {
            if (CollectionUtils.isNotEmpty(listeners)) {
                for (InvokerListener listener : listeners) {
                    if (listener != null) {
                        try {
                            listener.destroyed(invoker);
                        } catch (Throwable t) {
                            logger.error(t.getMessage(), t);
                        }
                    }
                }
            }
        }
    }

}
