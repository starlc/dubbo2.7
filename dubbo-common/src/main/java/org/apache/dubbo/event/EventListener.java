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

import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.lang.Prioritized;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;

import static org.apache.dubbo.common.utils.ReflectUtils.findParameterizedTypes;

/**
 * The {@link Event Dubbo Event} Listener that is based on Java standard {@link java.util.EventListener} interface supports
 * the generic {@link Event}.
 * <p>
 * The {@link #onEvent(Event) handle method} will be notified when the matched-type {@link Event Dubbo Event} is
 * published, whose priority could be changed by {@link #getPriority()} method.
 *
 * @param <E> the concrete class of {@link Event Dubbo Event}
 * @see Event 
 * @see java.util.EventListener
 * @since 2.7.5
 *
 * Dubbo 中 EventListener 接口的定义，其中关注三个方法：
 * onEvent() 方法、getPriority() 方法和 findEventType() 工具方法。
 */
@SPI
@FunctionalInterface
public interface EventListener<E extends Event> extends java.util.EventListener, Prioritized {

    /**
     * Handle a {@link Event Dubbo Event} when it's be published
     * // 当发生该EventListener对象关注的事件时，该EventListener的onEvent()方法会被调用
     * @param event a {@link Event Dubbo Event}
     */
    void onEvent(E event);

    /**
     * The priority of {@link EventListener current listener}.
     *
     * @return the value is more greater, the priority is more lower.
     * {@link Integer#MIN_VALUE} indicates the highest priority. The default value is {@link Integer#MAX_VALUE}.
     * The comparison rule , refer to {@link #compareTo}.
     * // 当前EventListener对象被调用的优先级
     */
    default int getPriority() {
        return NORMAL_PRIORITY;
    }

    /**
     * Find the {@link Class type} {@link Event Dubbo event} from the specified {@link EventListener Dubbo event listener}
     *
     * @param listener the {@link Class class} of {@link EventListener Dubbo event listener}
     * @return <code>null</code> if not found
     * // 获取传入的EventListener对象监听何种Event事件
     */
    static Class<? extends Event> findEventType(EventListener<?> listener) {
        return findEventType(listener.getClass());
    }

    /**
     * Find the {@link Class type} {@link Event Dubbo event} from the specified {@link EventListener Dubbo event listener}
     *
     * @param listenerClass the {@link Class class} of {@link EventListener Dubbo event listener}
     * @return <code>null</code> if not found
     *
     * // 检测传入listenerClass是否为Dubbo的EventListener接口实现
     */
    static Class<? extends Event> findEventType(Class<?> listenerClass) {
        Class<? extends Event> eventType = null;

        if (listenerClass != null && EventListener.class.isAssignableFrom(listenerClass)) {
            eventType = findParameterizedTypes(listenerClass)
                    .stream()
                    .map(EventListener::findEventType)// 获取listenerClass中定义的Event泛型
                    .filter(Objects::nonNull)
                    .findAny()
                    // 获取listenerClass父类中定义的Event泛型
                    .orElse((Class) findEventType(listenerClass.getSuperclass()));
        }

        return eventType;
    }

    /**
     * Find the type {@link Event Dubbo event} from the specified {@link ParameterizedType} presents
     * a class of {@link EventListener Dubbo event listener}
     *
     * @param parameterizedType the {@link ParameterizedType} presents a class of {@link EventListener Dubbo event listener}
     * @return <code>null</code> if not found
     */
    static Class<? extends Event> findEventType(ParameterizedType parameterizedType) {
        Class<? extends Event> eventType = null;

        Type rawType = parameterizedType.getRawType();
        if ((rawType instanceof Class) && EventListener.class.isAssignableFrom((Class) rawType)) {
            Type[] typeArguments = parameterizedType.getActualTypeArguments();
            for (Type typeArgument : typeArguments) {
                if (typeArgument instanceof Class) {
                    Class argumentClass = (Class) typeArgument;
                    if (Event.class.isAssignableFrom(argumentClass)) {
                        eventType = argumentClass;
                        break;
                    }
                }
            }
        }

        return eventType;
    }
}