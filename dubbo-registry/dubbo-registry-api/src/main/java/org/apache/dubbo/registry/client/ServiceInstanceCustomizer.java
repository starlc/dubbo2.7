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
package org.apache.dubbo.registry.client;

import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.lang.Prioritized;
import org.apache.dubbo.registry.client.event.ServiceInstancePreRegisteredEvent;

/**
 * The interface to customize {@link ServiceInstance the service instance} on {@link ServiceInstancePreRegisteredEvent}
 *
 * @see ServiceInstance#getMetadata()
 * @since 2.7.5
 * 关于 ServiceInstanceCustomizer 接口，这里需要关注三个点：
 * ①该接口被 @SPI 注解修饰，是一个扩展点；
 * ②该接口继承了 Prioritized 接口；
 * ③该接口中定义的 customize() 方法可以用来自定义 ServiceInstance 信息，其中就包括控制 metadata 集合中的数据。
 *
 * 也就说，ServiceInstanceCustomizer 的多个实现可以按序调用，实现 ServiceInstance 的自定义。
 */
@SPI
public interface ServiceInstanceCustomizer extends Prioritized {

    /**
     * Customizes {@link ServiceInstance the service instance}
     *
     * @param serviceInstance {@link ServiceInstance the service instance}
     */
    void customize(ServiceInstance serviceInstance);
}
