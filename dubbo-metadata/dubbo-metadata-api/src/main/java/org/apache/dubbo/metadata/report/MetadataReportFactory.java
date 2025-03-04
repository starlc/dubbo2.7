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
package org.apache.dubbo.metadata.report;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

/**
 * MetadataReportFactory 是用来创建 MetadataReport 实例的工厂
 *
 * 这些 MetadataReportFactory 实现都继承了 AbstractMetadataReportFactory，
 * 在 AbstractMetadataReportFactory 提供了缓存 MetadataReport 实现的功能，
 * 并定义了一个 createMetadataReport() 抽象方法供子类实现。
 * 另外，AbstractMetadataReportFactory 实现了 MetadataReportFactory 接口的 getMetadataReport() 方法
 */
@SPI("redis")
public interface MetadataReportFactory {

    @Adaptive({"protocol"})
    MetadataReport getMetadataReport(URL url);
}
