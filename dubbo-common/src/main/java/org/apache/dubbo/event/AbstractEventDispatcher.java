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

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Collections.sort;
import static java.util.Collections.unmodifiableList;
import static org.apache.dubbo.event.EventListener.findEventType;

/**
 * The abstract {@link EventDispatcher} providers the common implementation.
 *
 * @see EventDispatcher
 * @see Listenable
 * @see ServiceLoader
 * @see EventListener
 * @see Event
 * @since 2.7.5
 * AbstractEventDispatcher 已经实现了 EventDispatcher 分发 Event 事件、
 * 通知 EventListener 的核心逻辑，然后在 ParallelEventDispatcher
 * 和 DirectEventDispatcher 确定是并行通知模式还是串行通知模式即可
 */
public abstract class AbstractEventDispatcher implements EventDispatcher {

    private final Object mutex = new Object();

    /**
     * 用于记录监听各类型事件的 EventListener 集合。在 AbstractEventDispatcher 初始化时，
     * 会加载全部 EventListener 实现并调用 addEventListener() 方法添加到 listenersCache 集合中。
     */
    private final ConcurrentMap<Class<? extends Event>, List<EventListener>> listenersCache = new ConcurrentHashMap<>();

    /**
     * 该线程池在 AbstractEventDispatcher 的构造函数中初始化。在 AbstractEventDispatcher
     * 收到相应事件时，由该线程池来触发对应的 EventListener 集合。
     */
    private final Executor executor;

    /**
     * Constructor with an instance of {@link Executor}
     *
     * @param executor {@link Executor}
     * @throws NullPointerException <code>executor</code> is <code>null</code>
     */
    protected AbstractEventDispatcher(Executor executor) {
        if (executor == null) {
            throw new NullPointerException("executor must not be null");
        }
        this.executor = executor;
        this.loadEventListenerInstances();
    }

    @Override
    public void addEventListener(EventListener<?> listener) throws NullPointerException, IllegalArgumentException {
        Listenable.assertListener(listener);
        doInListener(listener, listeners -> {
            addIfAbsent(listeners, listener);
        });
    }

    @Override
    public void removeEventListener(EventListener<?> listener) throws NullPointerException, IllegalArgumentException {
        Listenable.assertListener(listener);
        doInListener(listener, listeners -> listeners.remove(listener));
    }

    @Override
    public List<EventListener<?>> getAllEventListeners() {
        List<EventListener<?>> listeners = new LinkedList<>();

        sortedListeners().forEach(listener -> {
            addIfAbsent(listeners, listener);
        });

        return unmodifiableList(listeners);
    }

    protected Stream<EventListener> sortedListeners() {
        return sortedListeners(e -> true);
    }


    /**
     * // 这里的sortedListeners方法会对listenerCache进行过滤和排序
     * @param predicate
     * @return
     */
    protected Stream<EventListener> sortedListeners(Predicate<Map.Entry<Class<? extends Event>, List<EventListener>>> predicate) {
        return listenersCache
                .entrySet()
                .stream()
                .filter(predicate)
                .map(Map.Entry::getValue)
                .flatMap(Collection::stream)
                .sorted();
    }

    private <E> void addIfAbsent(Collection<E> collection, E element) {
        if (!collection.contains(element)) {
            collection.add(element);
        }
    }

    /**
     * AbstractEventDispatcher 中另一个要关注的方法是 dispatch() 方法，该方法会从 listenersCache
     * 集合中过滤出符合条件的 EventListener 对象，并按照串行或是并行模式进行通知
     * @param event a {@link Event Dubbo event}
     */
    @Override
    public void dispatch(Event event) {

        // 获取通知EventListener的线程池，默认为串行模式，也就是direct实现
        Executor executor = getExecutor();

        // execute in sequential or parallel execution model
        executor.execute(() -> {
            sortedListeners(entry -> entry.getKey().isAssignableFrom(event.getClass()))
                    .forEach(listener -> {
                        if (listener instanceof ConditionalEventListener) {
                            ConditionalEventListener predicateEventListener = (ConditionalEventListener) listener;
                            if (!predicateEventListener.accept(event)) { // No accept
                                return;
                            }
                        }
                        // Handle the event// 通知EventListener
                        listener.onEvent(event);
                    });
        });
    }

    /**
     * @return the non-null {@link Executor}
     */
    @Override
    public final Executor getExecutor() {
        return executor;
    }

    protected void doInListener(EventListener<?> listener, Consumer<Collection<EventListener>> consumer) {
        Class<? extends Event> eventType = findEventType(listener);
        if (eventType != null) {
            synchronized (mutex) {
                List<EventListener> listeners = listenersCache.computeIfAbsent(eventType, e -> new LinkedList<>());
                // consume
                consumer.accept(listeners);
                // sort
                sort(listeners);
            }
        }
    }

    /**
     * Default, load the instances of {@link EventListener event listeners} by {@link ServiceLoader}
     * <p>
     * It could be override by the sub-class
     *
     * @see EventListener
     * @see ServiceLoader#load(Class)
     */
    protected void loadEventListenerInstances() {
        ExtensionLoader<EventListener> loader = ExtensionLoader.getExtensionLoader(EventListener.class);
        loader.getSupportedExtensionInstances().forEach(this::addEventListener);
    }
}
