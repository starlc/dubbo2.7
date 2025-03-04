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
package org.apache.dubbo.common.config;

import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.context.FrameworkExt;
import org.apache.dubbo.common.context.LifecycleAdapter;
import org.apache.dubbo.common.extension.Inject;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.ConfigCenterConfig;
import org.apache.dubbo.config.context.ConfigConfigurationAdapter;
import org.apache.dubbo.config.context.ConfigManager;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 在 Environment 中维护了多个 Configuration 对象
 */
public class Environment extends LifecycleAdapter implements FrameworkExt {
    private static final Logger logger = LoggerFactory.getLogger(Environment.class);
    public static final String NAME = "environment";

    /**
     * 全部 OrderedPropertiesProvider 实现提供的配置以及环境变量或是 -D 参数中指定配置文件的相关配置信息。
     */
    private final PropertiesConfiguration propertiesConfiguration;
    /**
     * -D 参数配置直接添加的配置信息。
     */
    private final SystemConfiguration systemConfiguration;
    /**
     * 环境变量中直接添加的配置信息。
     */
    private final EnvironmentConfiguration environmentConfiguration;
    /**
     * 使用 Spring 框架且将 include-spring-env 配置为 true 时，会自动从 Spring Environment 中读取配置。
     * 默认依次读取 key 为 dubbo.properties 和 application.dubbo.properties 到这里两个 InmemoryConfiguration 对象中。
     */
    private final InmemoryConfiguration externalConfiguration;
    private final InmemoryConfiguration appExternalConfiguration;

    /**
     * 用于组合上述各个配置来源。
     */
    private CompositeConfiguration globalConfiguration;
    private CompositeConfiguration dynamicGlobalConfiguration;


    private Map<String, String> externalConfigurationMap = new HashMap<>();
    private Map<String, String> appExternalConfigurationMap = new HashMap<>();

    /**
     * 用于标识配置中心的配置是否为最高优先级。
     */
    private boolean configCenterFirst = true;

    /**
     * 用于组合当前全部的配置中心对应的 DynamicConfiguration。
     */
    private DynamicConfiguration dynamicConfiguration;

    public Environment() {
        this.propertiesConfiguration = new PropertiesConfiguration();
        this.systemConfiguration = new SystemConfiguration();
        this.environmentConfiguration = new EnvironmentConfiguration();
        this.externalConfiguration = new InmemoryConfiguration();
        this.appExternalConfiguration = new InmemoryConfiguration();
    }

    /**
     * 在 initialize() 方法中会将从 Spring Environment 中读取到的配置填充到
     * externalConfiguration 以及 appExternalConfiguration 中。
     * @throws IllegalStateException
     */
    @Override
    public void initialize() throws IllegalStateException {
        // 读取对应配置，填充上述Configuration对象
        ConfigManager configManager = ApplicationModel.getConfigManager();
        Optional<Collection<ConfigCenterConfig>> defaultConfigs = configManager.getDefaultConfigCenter();
        defaultConfigs.ifPresent(configs -> {
            for (ConfigCenterConfig config : configs) {
                this.setExternalConfigMap(config.getExternalConfiguration());
                this.setAppExternalConfigMap(config.getAppExternalConfiguration());
            }
        });

        this.externalConfiguration.setProperties(externalConfigurationMap);
        this.appExternalConfiguration.setProperties(appExternalConfigurationMap);
    }

    @Inject(enable = false)
    public void setExternalConfigMap(Map<String, String> externalConfiguration) {
        if (externalConfiguration != null) {
            this.externalConfigurationMap = externalConfiguration;
        }
    }

    @Inject(enable = false)
    public void setAppExternalConfigMap(Map<String, String> appExternalConfiguration) {
        if (appExternalConfiguration != null) {
            this.appExternalConfigurationMap = appExternalConfiguration;
        }
    }

    public Map<String, String> getExternalConfigurationMap() {
        return externalConfigurationMap;
    }

    public Map<String, String> getAppExternalConfigurationMap() {
        return appExternalConfigurationMap;
    }

    public void updateExternalConfigurationMap(Map<String, String> externalMap) {
        this.externalConfigurationMap.putAll(externalMap);
    }

    public void updateAppExternalConfigurationMap(Map<String, String> externalMap) {
        this.appExternalConfigurationMap.putAll(externalMap);
    }

    /**
     * At start-up, Dubbo is driven by various configuration, such as Application, Registry, Protocol, etc.
     * All configurations will be converged into a data bus - URL, and then drive the subsequent process.
     * <p>
     * At present, there are many configuration sources, including AbstractConfig (API, XML, annotation), - D, config center, etc.
     * This method helps us to filter out the most priority values from various configuration sources.
     * 关注一下 Environment.getPrefixedConfiguration() 方法，
     * 该方法会将 Environment 中已有的 Configuration 对象以及当前的 ConfigCenterConfig 按照顺序合并，
     * 得到一个 CompositeConfiguration 对象，用于确定配置中心的最终配置信息。
     * @param config
     * @return
     */
    public synchronized CompositeConfiguration getPrefixedConfiguration(AbstractConfig config) {
        // 创建CompositeConfiguration对象，这里的prefix和id是根据ConfigCenterConfig确定的
        CompositeConfiguration prefixedConfiguration = new CompositeConfiguration(config.getPrefix(), config.getId());
        // 将ConfigCenterConfig封装成ConfigConfigurationAdapter
        Configuration configuration = new ConfigConfigurationAdapter(config);
        if (this.isConfigCenterFirst()) {// 根据配置确定ConfigCenterConfig配置的位置
            // The sequence would be: SystemConfiguration -> AppExternalConfiguration -> ExternalConfiguration -> AbstractConfig -> PropertiesConfiguration
            // Config center has the highest priority
            prefixedConfiguration.addConfiguration(systemConfiguration);
            prefixedConfiguration.addConfiguration(environmentConfiguration);
            prefixedConfiguration.addConfiguration(appExternalConfiguration);
            prefixedConfiguration.addConfiguration(externalConfiguration);
            prefixedConfiguration.addConfiguration(configuration);
            prefixedConfiguration.addConfiguration(propertiesConfiguration);
        } else {
            // The sequence would be: SystemConfiguration -> AbstractConfig -> AppExternalConfiguration -> ExternalConfiguration -> PropertiesConfiguration
            // Config center has the highest priority
            // 配置优先级如下：SystemConfiguration -> AbstractConfig -> AppExternalConfiguration -> ExternalConfiguration -> PropertiesConfiguration
            prefixedConfiguration.addConfiguration(systemConfiguration);
            prefixedConfiguration.addConfiguration(environmentConfiguration);
            prefixedConfiguration.addConfiguration(configuration);
            prefixedConfiguration.addConfiguration(appExternalConfiguration);
            prefixedConfiguration.addConfiguration(externalConfiguration);
            prefixedConfiguration.addConfiguration(propertiesConfiguration);
        }
        return prefixedConfiguration;
    }

    /**
     * There are two ways to get configuration during exposure / reference or at runtime:
     * 1. URL, The value in the URL is relatively fixed. we can get value directly.
     * 2. The configuration exposed in this method is convenient for us to query the latest values from multiple
     * prioritized sources, it also guarantees that configs changed dynamically can take effect on the fly.
     */
    public Configuration getConfiguration() {
        if (globalConfiguration == null) {
            globalConfiguration = new CompositeConfiguration();
            globalConfiguration.addConfiguration(systemConfiguration);
            globalConfiguration.addConfiguration(environmentConfiguration);
            globalConfiguration.addConfiguration(appExternalConfiguration);
            globalConfiguration.addConfiguration(externalConfiguration);
            globalConfiguration.addConfiguration(propertiesConfiguration);
        }
        return globalConfiguration;
    }

    public Configuration getDynamicGlobalConfiguration() {
        if (dynamicGlobalConfiguration == null) {
            if (dynamicConfiguration == null) {
                if (logger.isWarnEnabled()) {
                    logger.warn("dynamicConfiguration is null , return globalConfiguration.");
                }
                return globalConfiguration;
            }
            dynamicGlobalConfiguration = new CompositeConfiguration();
            dynamicGlobalConfiguration.addConfiguration(dynamicConfiguration);
            dynamicGlobalConfiguration.addConfiguration(getConfiguration());
        }
        return dynamicGlobalConfiguration;
    }

    public boolean isConfigCenterFirst() {
        return configCenterFirst;
    }

    @Inject(enable = false)
    public void setConfigCenterFirst(boolean configCenterFirst) {
        this.configCenterFirst = configCenterFirst;
    }

    public Optional<DynamicConfiguration> getDynamicConfiguration() {
        return Optional.ofNullable(dynamicConfiguration);
    }

    @Inject(enable = false)
    public void setDynamicConfiguration(DynamicConfiguration dynamicConfiguration) {
        this.dynamicConfiguration = dynamicConfiguration;
    }

    @Override
    public void destroy() throws IllegalStateException {
        clearExternalConfigs();
        clearAppExternalConfigs();
        clearDynamicConfiguration();
    }

    public PropertiesConfiguration getPropertiesConfiguration() {
        return propertiesConfiguration;
    }

    public SystemConfiguration getSystemConfiguration() {
        return systemConfiguration;
    }

    public EnvironmentConfiguration getEnvironmentConfiguration() {
        return environmentConfiguration;
    }

    public InmemoryConfiguration getExternalConfiguration() {
        return externalConfiguration;
    }

    public InmemoryConfiguration getAppExternalConfiguration() {
        return appExternalConfiguration;
    }

    // For test
    public void clearExternalConfigs() {
        this.externalConfiguration.clear();
        this.externalConfigurationMap.clear();
    }

    // For test
    public void clearAppExternalConfigs() {
        this.appExternalConfiguration.clear();
        this.appExternalConfigurationMap.clear();
    }

    public void clearDynamicConfiguration() {
        this.dynamicConfiguration = null;
    }
}
