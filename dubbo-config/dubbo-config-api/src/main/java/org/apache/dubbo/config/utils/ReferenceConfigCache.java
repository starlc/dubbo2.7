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
package org.apache.dubbo.config.utils;

import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.ReferenceConfigBase;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.service.Destroyable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.common.BaseServiceMetadata.buildServiceKey;

/**
 * A simple util class for cache {@link ReferenceConfigBase}.
 * <p>
 * {@link ReferenceConfigBase} is a heavy Object, it's necessary to cache these object
 * for the framework which create {@link ReferenceConfigBase} frequently.
 * <p>
 * You can implement and use your own {@link ReferenceConfigBase} cache if you need use complicate strategy.
 *
 * 服务引用的核心实现在 ReferenceConfig 之中，一个 ReferenceConfig 对象对应一个服务接口，
 * 每个 ReferenceConfig 对象中都封装了与注册中心的网络连接，以及与 Provider 的网络连接，这是一个非常重要的对象。
 *
 * 为了避免底层连接泄漏造成性能问题，从 Dubbo 2.4.0 版本开始，
 * Dubbo 提供了 ReferenceConfigCache 用于缓存 ReferenceConfig 实例。
 *
 * 在 ReferenceConfigCache 中维护了一个静态的 Map（CACHE_HOLDER）字段，
 * 其中 Key 是由 Group、服务接口和 version 构成，Value 是一个 ReferenceConfigCache 对象。
 */
public class ReferenceConfigCache {
    public static final String DEFAULT_NAME = "_DEFAULT_";
    /**
     * Create the key with the <b>Group</b>, <b>Interface</b> and <b>version</b> attribute of {@link ReferenceConfigBase}.
     * <p>
     * key example: <code>group1/org.apache.dubbo.foo.FooService:1.0.0</code>.
     * Key 是由 Group、服务接口和 version 构成
     * // Key的格式是group/interface:version
     */
    public static final KeyGenerator DEFAULT_KEY_GENERATOR = referenceConfig -> {
        String iName = referenceConfig.getInterface();
        if (StringUtils.isBlank(iName)) {
            // 获取服务接口名称
            Class<?> clazz = referenceConfig.getInterfaceClass();
            iName = clazz.getName();
        }
        if (StringUtils.isBlank(iName)) {
            throw new IllegalArgumentException("No interface info in ReferenceConfig" + referenceConfig);
        }

        return buildServiceKey(iName, referenceConfig.getGroup(), referenceConfig.getVersion());
    };

    static final ConcurrentMap<String, ReferenceConfigCache> CACHE_HOLDER = new ConcurrentHashMap<String, ReferenceConfigCache>();
    private final String name;
    private final KeyGenerator generator;

    /**
     * 该集合用来存储已经被处理的 ReferenceConfig 对象。
     */
    private final ConcurrentMap<String, ReferenceConfigBase<?>> referredReferences = new ConcurrentHashMap<>();

    /**
     * 该集合用来存储服务接口的全部代理对象，其中第一层 Key 是服务接口的类型，
     * 第二层 Key 是上面介绍的 KeyGenerator 为不同服务提供方生成的 Key，Value 是服务的代理对象。
     */
    private final ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> proxies = new ConcurrentHashMap<>();

    private ReferenceConfigCache(String name, KeyGenerator generator) {
        this.name = name;
        this.generator = generator;
    }

    /**
     * Get the cache use default name and {@link #DEFAULT_KEY_GENERATOR} to generate cache key.
     * Create cache if not existed yet.
     */
    public static ReferenceConfigCache getCache() {
        return getCache(DEFAULT_NAME);
    }

    /**
     * Get the cache use specified name and {@link KeyGenerator}.
     * Create cache if not existed yet.
     */
    public static ReferenceConfigCache getCache(String name) {
        return getCache(name, DEFAULT_KEY_GENERATOR);
    }

    /**
     * Get the cache use specified {@link KeyGenerator}.
     * Create cache if not existed yet.
     */
    public static ReferenceConfigCache getCache(String name, KeyGenerator keyGenerator) {
        return CACHE_HOLDER.computeIfAbsent(name, k -> new ReferenceConfigCache(k, keyGenerator));
    }

    @SuppressWarnings("unchecked")
    public <T> T get(ReferenceConfigBase<T> referenceConfig) {
        // 生成服务提供方对应的Key
        String key = generator.generateKey(referenceConfig);
        // 获取接口类型
        Class<?> type = referenceConfig.getInterfaceClass();

        // 获取该接口对应代理对象集合
        proxies.computeIfAbsent(type, _t -> new ConcurrentHashMap<>());

        ConcurrentMap<String, Object> proxiesOfType = proxies.get(type);
        // 根据Key获取服务提供方对应的代理对象
        proxiesOfType.computeIfAbsent(key, _k -> {
            // 服务引用
            Object proxy = referenceConfig.get();
            // 将ReferenceConfig记录到referredReferences集合
            referredReferences.put(key, referenceConfig);
            return proxy;
        });

        return (T) proxiesOfType.get(key);
    }

    /**
     * Fetch cache with the specified key. The key is decided by KeyGenerator passed-in. If the default KeyGenerator is
     * used, then the key is in the format of <code>group/interfaceClass:version</code>
     *
     * @param key  cache key
     * @param type object class
     * @param <T>  object type
     * @return object from the cached ReferenceConfigBase
     * @see KeyGenerator#generateKey(ReferenceConfigBase)
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Map<String, Object> proxiesOfType = proxies.get(type);
        if (CollectionUtils.isEmptyMap(proxiesOfType)) {
            return null;
        }
        return (T) proxiesOfType.get(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        ReferenceConfigBase<?> rc = referredReferences.get(key);
        if (rc == null) {
            return null;
        }

        return (T) get(key, rc.getInterfaceClass());
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getAll(Class<T> type) {
        Map<String, Object> proxiesOfType = proxies.get(type);
        if (CollectionUtils.isEmptyMap(proxiesOfType)) {
            return Collections.emptyList();
        }

        List<T> proxySet = new ArrayList<>();
        proxiesOfType.values().forEach(obj -> proxySet.add((T) obj));
        return proxySet;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        Map<String, Object> proxiesOfType = proxies.get(type);
        if (CollectionUtils.isEmptyMap(proxiesOfType)) {
            return null;
        }

        return (T) proxiesOfType.values().iterator().next();
    }

    public void destroy(String key, Class<?> type) {
        ReferenceConfigBase<?> rc = referredReferences.remove(key);
        if (rc == null) {
            return;
        }

        ApplicationModel.getConfigManager().removeConfig(rc);
        rc.destroy();

        Map<String, Object> proxiesOftype = proxies.get(type);
        if (CollectionUtils.isNotEmptyMap(proxiesOftype)) {
            Destroyable proxy = (Destroyable) proxiesOftype.remove(key);
            proxy.$destroy();
            if (proxiesOftype.isEmpty()) {
                proxies.remove(type);
            }
        }
    }

    public void destroy(Class<?> type) {
        Map<String, Object> proxiesOfType = proxies.remove(type);
        proxiesOfType.forEach((k, v) -> {
            ReferenceConfigBase rc = referredReferences.remove(k);
            rc.destroy();
        });
    }

    /**
     * clear and destroy one {@link ReferenceConfigBase} in the cache.
     *
     * @param referenceConfig use for create key.
     */
    public <T> void destroy(ReferenceConfigBase<T> referenceConfig) {
        String key = generator.generateKey(referenceConfig);
        Class<?> type = referenceConfig.getInterfaceClass();

        destroy(key, type);
    }

    /**
     * clear and destroy all {@link ReferenceConfigBase} in the cache.
     */
    public void destroyAll() {
        if (CollectionUtils.isEmptyMap(referredReferences)) {
            return;
        }

        referredReferences.forEach((_k, referenceConfig) -> {
            referenceConfig.destroy();
            ApplicationModel.getConfigManager().removeConfig(referenceConfig);
        });

        proxies.forEach((_type, proxiesOfType) -> {
            proxiesOfType.forEach((_k, v) -> {
                Destroyable proxy = (Destroyable) v;
                proxy.$destroy();
            });
        });

        referredReferences.clear();
        proxies.clear();
    }

    public ConcurrentMap<String, ReferenceConfigBase<?>> getReferredReferences() {
        return referredReferences;
    }

    public ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> getProxies() {
        return proxies;
    }

    @Override
    public String toString() {
        return "ReferenceConfigCache(name: " + name
                + ")";
    }

    public interface KeyGenerator {
        String generateKey(ReferenceConfigBase<?> referenceConfig);
    }
}
