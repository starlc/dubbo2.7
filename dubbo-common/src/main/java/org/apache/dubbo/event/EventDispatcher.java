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
package org.apache.dubbo.event;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;

import java.util.concurrent.Executor;

/**
 * {@link Event Dubbo Event} Dispatcher
 *
 * @see Event
 * @see EventListener
 * @see DirectEventDispatcher
 * @since 2.7.5
 */
@SPI(DirectEventDispatcher.NAME)
public interface EventDispatcher extends Listenable<EventListener<?>> {

    /**
     * Direct {@link Executor} uses sequential execution model
     * // 该线程池用于串行调用被触发的EventListener，也就是direct模式，调用线程是当前线程
     *
     * 等价于
     * Executor DIRECT_EXECUTOR = new Executor() {
     *     @Override
     *     public void execute(Runnable command) {
     *         command.run(); // 直接调用传入的 Runnable 的 run 方法
     *     }
     * };
     *
     * Executor DIRECT_EXECUTOR = (Runnable command) -> command.run();
     */
    Executor DIRECT_EXECUTOR = Runnable::run;

    /**
     * Dispatch a Dubbo event to the registered {@link EventListener Dubbo event listeners}
     * // 将被触发的事件分发给相应的EventListener对象
     * @param event a {@link Event Dubbo event}
     */
    void dispatch(Event event);

    /**
     * The {@link Executor} to dispatch a {@link Event Dubbo event}
     *
     * @return default implementation directly invoke {@link Runnable#run()} method, rather than multiple-threaded
     * {@link Executor}. If the return value is <code>null</code>, the behavior is same as default.
     * @see #DIRECT_EXECUTOR
     *
     * 获取direct模式中使用的线程池
     */
    default Executor getExecutor() {
        return DIRECT_EXECUTOR;
    }

    /**
     * The default extension of {@link EventDispatcher} is loaded by {@link ExtensionLoader}
     * // 工具方法，用于获取EventDispatcher接口的默认实现
     * @return the default extension of {@link EventDispatcher}
     */
    static EventDispatcher getDefaultExtension() {
        return ExtensionLoader.getExtensionLoader(EventDispatcher.class).getDefaultExtension();
    }
}
