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
package org.apache.dubbo.rpc;

import org.apache.dubbo.common.Experimental;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Invocation. (API, Prototype, NonThreadSafe)
 * Invocation 接口是 Invoker.invoke() 方法的参数，抽象了一次 RPC 调用的目标服务和方法信息、相关参数信息、具体的参数值以及一些附加信息
 * @serial Don't change the class name and package name.
 * @see org.apache.dubbo.rpc.Invoker#invoke(Invocation)
 * @see org.apache.dubbo.rpc.RpcInvocation
 */
public interface Invocation {
    // 调用Service的唯一标识
    String getTargetServiceUniqueName();

    String getProtocolServiceKey();

    /**
     * get method name.
     * 调用的方法名称
     * @return method name.
     * @serial
     */
    String getMethodName();


    /**
     * get the interface name
     * 调用的服务名称
     * @return
     */
    String getServiceName();

    /**
     * get parameter types.
     * 参数类型集合
     * @return parameter types.
     * @serial
     */
    Class<?>[] getParameterTypes();

    /**
     * get parameter's signature, string representation of parameter types.
     * 参数签名集合
     * @return parameter's signature
     */
    default String[] getCompatibleParamSignatures() {
        return Stream.of(getParameterTypes())
                .map(Class::getName)
                .toArray(String[]::new);
    }

    /**
     * get arguments.
     * 此次调用具体的参数值
     * @return arguments.
     * @serial
     */
    Object[] getArguments();

    /**
     * get attachments.
     * Invocation可以携带一个KV信息作为附加信息，一并传递给Provider，
     * 注意与 attribute 的区分
     * @return attachments.
     * @serial
     */
    Map<String, String> getAttachments();

    @Experimental("Experiment api for supporting Object transmission")
    Map<String, Object> getObjectAttachments();

    void setAttachment(String key, String value);

    @Experimental("Experiment api for supporting Object transmission")
    void setAttachment(String key, Object value);

    @Experimental("Experiment api for supporting Object transmission")
    void setObjectAttachment(String key, Object value);

    void setAttachmentIfAbsent(String key, String value);

    @Experimental("Experiment api for supporting Object transmission")
    void setAttachmentIfAbsent(String key, Object value);

    @Experimental("Experiment api for supporting Object transmission")
    void setObjectAttachmentIfAbsent(String key, Object value);

    /**
     * get attachment by key.
     *
     * @return attachment value.
     * @serial
     */
    String getAttachment(String key);

    @Experimental("Experiment api for supporting Object transmission")
    Object getObjectAttachment(String key);

    /**
     * get attachment by key with default value.
     *
     * @return attachment value.
     * @serial
     */
    String getAttachment(String key, String defaultValue);

    @Experimental("Experiment api for supporting Object transmission")
    Object getObjectAttachment(String key, Object defaultValue);

    /**
     * get the invoker in current context.
     * 此次调用关联的Invoker对象
     * @return invoker.
     * @transient
     */
    Invoker<?> getInvoker();
    // Invoker对象可以设置一些KV属性，这些属性并不会传递给Provider
    Object put(Object key, Object value);

    Object get(Object key);

    Map<Object, Object> getAttributes();
}