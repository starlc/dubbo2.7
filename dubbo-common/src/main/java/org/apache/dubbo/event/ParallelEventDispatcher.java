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

import java.util.concurrent.ForkJoinPool;

/**
 * Parallel {@link EventDispatcher} implementation uses {@link ForkJoinPool#commonPool() JDK common thread pool}
 *
 * @see ForkJoinPool#commonPool()
 * @since 2.7.5
 *
 * 在 ParallelEventDispatcher 中通知 EventListener 的线程池是 ForkJoinPool，也就是并行模式；
 */
public class ParallelEventDispatcher extends AbstractEventDispatcher {
    public static final String NAME = "parallel";

    public ParallelEventDispatcher() {
        super(ForkJoinPool.commonPool());
    }
}
