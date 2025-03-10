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

/**
 * Direct {@link EventDispatcher} implementation uses current thread execution model
 *
 * @see EventDispatcher
 * @since 2.7.5
 * 并行通知模式
 */
public final class DirectEventDispatcher extends AbstractEventDispatcher {
    public static final String NAME = "direct";

    public DirectEventDispatcher() {
        super(DIRECT_EXECUTOR);
    }
}
