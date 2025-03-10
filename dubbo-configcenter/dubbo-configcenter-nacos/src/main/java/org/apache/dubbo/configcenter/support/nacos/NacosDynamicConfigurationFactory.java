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

package org.apache.dubbo.configcenter.support.nacos;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.configcenter.AbstractDynamicConfigurationFactory;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.constants.CommonConstants;

import static org.apache.dubbo.common.constants.CommonConstants.CONFIG_NAMESPACE_KEY;

/**
 * The nacos implementation of {@link AbstractDynamicConfigurationFactory}
 */
public class NacosDynamicConfigurationFactory extends AbstractDynamicConfigurationFactory {

    @Override
    protected DynamicConfiguration createDynamicConfiguration(URL url) {
        URL nacosURL = url;
        if (CommonConstants.DUBBO.equals(url.getParameter(CONFIG_NAMESPACE_KEY))) {
            // Nacos use empty string as default name space, replace default namespace "dubbo" to ""
            // Nacos默认的命名空间是空
            nacosURL = url.removeParameter(CONFIG_NAMESPACE_KEY);
        }
        return new NacosDynamicConfiguration(nacosURL);
    }
}
