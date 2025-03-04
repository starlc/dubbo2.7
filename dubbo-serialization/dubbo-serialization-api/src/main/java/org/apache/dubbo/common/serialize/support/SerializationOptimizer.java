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
package org.apache.dubbo.common.serialize.support;

import java.util.Collection;

/**
 * Interface defining serialization optimizer, there are nothing implementations for now.
 * 定义需要序列化的类
 * 这里先介绍一个基础知识，在使用某些序列化算法（例如， Kryo、FST 等）时，为了让其能发挥出最佳的性能，
 * 最好将那些需要被序列化的类提前注册到 Dubbo 系统中。
 * 例如，我们可以通过一个实现了 SerializationOptimizer 接口的优化器，并在配置中指定该优化器
 */
public interface SerializationOptimizer {

    /**
     * Get serializable classes
     *
     * @return serializable classes
     * */
    Collection<Class<?>> getSerializableClasses();
}
