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

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * This is an abstraction specially customized for the sequence Dubbo retrieves properties.
 * CompositeConfiguration 是一个复合的 Configuration 对象，
 * 其核心就是将多个 Configuration 对象组合起来，对外表现为一个 Configuration 对象。
 *
 * CompositeConfiguration 组合的 Configuration 对象都保存在 configList 字段中（LinkedList<Configuration> 集合），
 * CompositeConfiguration 提供了 addConfiguration() 方法用于向 configList 集合中添加 Configuration 对象
 */
public class CompositeConfiguration implements Configuration {
    private Logger logger = LoggerFactory.getLogger(CompositeConfiguration.class);

    /**
     * 维护了一个 prefix 字段和 id 字段，两者可以作为 Key 的前缀进行查询
     */
    private String id;
    /**
     * 维护了一个 prefix 字段和 id 字段，两者可以作为 Key 的前缀进行查询
     */
    private String prefix;

    /**
     * List holding all the configuration
     * 持有的所有配置
     */
    private List<Configuration> configList = new LinkedList<Configuration>();

    //FIXME, consider change configList to SortedMap to replace this boolean status.
    private boolean dynamicIncluded;

    public CompositeConfiguration() {
        this(null, null);
    }

    public CompositeConfiguration(String prefix, String id) {
        if (StringUtils.isNotEmpty(prefix) && !prefix.endsWith(".")) {
            this.prefix = prefix + ".";
        } else {
            this.prefix = prefix;
        }
        this.id = id;
    }

    public CompositeConfiguration(Configuration... configurations) {
        this();
        if (configurations != null && configurations.length > 0) {
            Arrays.stream(configurations).filter(config -> !configList.contains(config)).forEach(configList::add);
        }
    }

    public void setDynamicIncluded(boolean dynamicIncluded) {
        this.dynamicIncluded = dynamicIncluded;
    }

    //FIXME, consider change configList to SortedMap to replace this boolean status.
    public boolean isDynamicIncluded() {
        return dynamicIncluded;
    }

    public void addConfiguration(Configuration configuration) {
        if (configList.contains(configuration)) {
            // 不会重复添加同一个Configuration对象
            return;
        }
        this.configList.add(configuration);
    }

    public void addConfigurationFirst(Configuration configuration) {
        this.addConfiguration(0, configuration);
    }

    public void addConfiguration(int pos, Configuration configuration) {
        this.configList.add(pos, configuration);
    }

    @Override
    public Object getInternalProperty(String key) {
        Configuration firstMatchingConfiguration = null;
        for (Configuration config : configList) {// 遍历所有Configuration对象
            try {
                if (config.containsKey(key)) {// 得到第一个包含指定Key的Configuration对象
                    firstMatchingConfiguration = config;
                    break;
                }
            } catch (Exception e) {
                logger.error("Error when trying to get value for key " + key + " from " + config + ", will continue to try the next one.");
            }
        }
        if (firstMatchingConfiguration != null) {// 通过该Configuration查询Key并返回配置值
            return firstMatchingConfiguration.getProperty(key);
        } else {
            return null;
        }
    }

    @Override
    public boolean containsKey(String key) {
        return configList.stream().anyMatch(c -> c.containsKey(key));
    }

    @Override
    public Object getProperty(String key, Object defaultValue) {
        Object value = null;
        if (StringUtils.isNotEmpty(prefix)) {// 检查prefix
            if (StringUtils.isNotEmpty(id)) {// 检查id
                // prefix和id都作为前缀，然后拼接key进行查询
                value = getInternalProperty(prefix + id + "." + key);
            }
            if (value == null) {
                // 只把prefix作为前缀，拼接key进行查询
                value = getInternalProperty(prefix + key);
            }
        } else {
            // 若prefix为空，则直接用key进行查询
            value = getInternalProperty(key);
        }
        return value != null ? value : defaultValue;
    }
}