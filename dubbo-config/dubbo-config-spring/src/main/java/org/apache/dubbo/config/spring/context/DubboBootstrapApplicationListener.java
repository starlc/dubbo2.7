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
package org.apache.dubbo.config.spring.context;

import org.apache.dubbo.config.DubboShutdownHook;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;

import com.alibaba.spring.context.OnceApplicationContextEventListener;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;

/**
 * The {@link ApplicationListener} for {@link DubboBootstrap}'s lifecycle when the {@link ContextRefreshedEvent}
 * and {@link ContextClosedEvent} raised
 *
 * 不仅是直接通过 API 启动 Provider 的方式会使用到 DubboBootstrap，
 * 在 Spring 与 Dubbo 集成的时候也是使用 DubboBootstrap 作为服务发布入口的，
 * 具体逻辑在 DubboBootstrapApplicationListener 这个 Spring Context 监听器中
 * @since 2.7.5
 */
public class DubboBootstrapApplicationListener extends OnceApplicationContextEventListener implements Ordered {

    /**
     * The bean name of {@link DubboBootstrapApplicationListener}
     *
     * @since 2.7.6
     */
    public static final String BEAN_NAME = "dubboBootstrapApplicationListener";

    private final DubboBootstrap dubboBootstrap;

    public DubboBootstrapApplicationListener() {
        // 初始化DubboBootstrap对象
        this.dubboBootstrap = DubboBootstrap.getInstance();
    }

    public DubboBootstrapApplicationListener(ApplicationContext applicationContext) {
        super(applicationContext);
        this.dubboBootstrap = DubboBootstrap.getInstance();// 初始化DubboBootstrap对象
        DubboBootstrapStartStopListenerSpringAdapter.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationContextEvent(ApplicationContextEvent event) {
        // 监听ContextRefreshedEvent事件和ContextClosedEvent事件
        if (DubboBootstrapStartStopListenerSpringAdapter.applicationContext == null) {
            DubboBootstrapStartStopListenerSpringAdapter.applicationContext = event.getApplicationContext();
        }
        if (event instanceof ContextRefreshedEvent) {
            onContextRefreshedEvent((ContextRefreshedEvent) event);
        } else if (event instanceof ContextClosedEvent) {
            onContextClosedEvent((ContextClosedEvent) event);
        }
    }

    private void onContextRefreshedEvent(ContextRefreshedEvent event) {
        // 启动DubboBootstrap
        dubboBootstrap.start();
    }

    private void onContextClosedEvent(ContextClosedEvent event) {
        DubboShutdownHook.getDubboShutdownHook().run();
    }

    @Override
    public int getOrder() {
        return LOWEST_PRECEDENCE;
    }
}
