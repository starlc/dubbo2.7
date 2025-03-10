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

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;


/**
 * (API, Prototype, NonThreadSafe)
 *
 * An RPC {@link Result}.
 *
 * Known implementations are:
 * 1. {@link AsyncRpcResult}, it's a {@link CompletionStage} whose underlying value signifies the return value of an RPC call.
 * 2. {@link AppResponse}, it inevitably inherits {@link CompletionStage} and {@link Future}, but you should never treat AppResponse as a type of Future,
 *    instead, it is a normal concrete type.
 * Result 接口是 Invoker.invoke() 方法的返回值，抽象了一次调用的返回值，
 * 其中包含了被调用方返回值（或是异常）以及附加信息，我们也可以添加回调方法，
 * 在 RPC 调用方法结束时会触发这些回调。
 * @serial Don't change the class name and package name.
 * @see org.apache.dubbo.rpc.Invoker#invoke(Invocation)
 * @see AppResponse
 */
public interface Result extends Serializable {

    /**
     * Get invoke result.
     * 获取/设置此次调用的返回值
     * @return result. if no result return null.
     */
    Object getValue();

    void setValue(Object value);

    /**
     * Get exception.
     * 如果此次调用发生异常，则可以通过下面三个方法获取
     * @return exception. if no exception return null.
     */
    Throwable getException();

    void setException(Throwable t);

    /**
     * Has exception.
     *
     * @return has exception.
     */
    boolean hasException();

    /**
     * Recreate.
     * <p>
     * <code>
     * if (hasException()) {
     * throw getException();
     * } else {
     * return getValue();
     * }
     * </code>
     * recreate()方法是一个复合操作，如果此次调用发生异常，则直接抛出异常，如果没有异常，则返回结果
     * @return result.
     * @throws if has exception throw it.
     */
    Object recreate() throws Throwable;

    /**
     * get attachments.
     * Result中同样可以携带附加信息
     * @return attachments.
     */
    Map<String, String> getAttachments();

    /**
     * get attachments.
     *
     * @return attachments.
     */
    @Experimental("Experiment api for supporting Object transmission")
    Map<String, Object> getObjectAttachments();

    /**
     * Add the specified map to existing attachments in this instance.
     *
     * @param map
     */
    void addAttachments(Map<String, String> map);

    /**
     * Add the specified map to existing attachments in this instance.
     *
     * @param map
     */
    @Experimental("Experiment api for supporting Object transmission")
    void addObjectAttachments(Map<String, Object> map);

    /**
     * Replace the existing attachments with the specified param.
     *
     * @param map
     */
    void setAttachments(Map<String, String> map);

    /**
     * Replace the existing attachments with the specified param.
     *
     * @param map
     */
    @Experimental("Experiment api for supporting Object transmission")
    void setObjectAttachments(Map<String, Object> map);

    /**
     * get attachment by key.
     *
     * @return attachment value.
     */
    String getAttachment(String key);

    /**
     * get attachment by key.
     *
     * @return attachment value.
     */
    @Experimental("Experiment api for supporting Object transmission")
    Object getObjectAttachment(String key);

    /**
     * get attachment by key with default value.
     *
     * @return attachment value.
     */
    String getAttachment(String key, String defaultValue);

    /**
     * get attachment by key with default value.
     *
     * @return attachment value.
     */
    @Experimental("Experiment api for supporting Object transmission")
    Object getObjectAttachment(String key, Object defaultValue);

    void setAttachment(String key, String value);

    @Experimental("Experiment api for supporting Object transmission")
    void setAttachment(String key, Object value);

    @Experimental("Experiment api for supporting Object transmission")
    void setObjectAttachment(String key, Object value);

    /**
     * Add a callback which can be triggered when the RPC call finishes.
     * <p>
     * Just as the method name implies, this method will guarantee the callback being triggered under the same context as when the call was started,
     * see implementation in {@link Result#whenCompleteWithContext(BiConsumer)}
     * 添加一个回调，当RPC调用完成时，会触发这里添加的回调
     * @param fn
     * @return
     */
    Result whenCompleteWithContext(BiConsumer<Result, Throwable> fn);

    <U> CompletableFuture<U> thenApply(Function<Result, ? extends U> fn);

    /**
     * 阻塞线程，等待此次RPC调用完成(或是超时)
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    Result get() throws InterruptedException, ExecutionException;

    Result get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException;
}