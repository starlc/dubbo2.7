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
package org.apache.dubbo.common.serialize;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.Adaptive;
import org.apache.dubbo.common.extension.SPI;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Serialization strategy interface that specifies a serializer. (SPI, Singleton, ThreadSafe)
 *
 * The default extension is hessian2 and the default serialization implementation of the dubbo protocol.
 * <pre>
 *     e.g. &lt;dubbo:protocol serialization="xxx" /&gt;
 * </pre>
 * 最核心的是 Serialization 这个接口，它是一个扩展接口，被 @SPI 接口修饰，默认扩展实现是 Hessian2Serialization。
 */
@SPI("hessian2")
public interface Serialization {

    /**
     * Get content type unique id, recommended that custom implementations use values different with
     * any value of {@link Constants} and don't greater than ExchangeCodec.SERIALIZATION_MASK (31) 
     * because dubbo protocol use 5 bits to record serialization ID in header.
     * // 获取ContentType的ID值，是一个byte类型的值，唯一确定一个算法
     * @return content type id
     */
    byte getContentTypeId();

    /**
     * Get content type
     * // 每一种序列化算法都对应一个ContentType，该方法用于获取ContentType
     * @return content type
     */
    String getContentType();

    /**
     * Get a serialization implementation instance
     * // 创建一个ObjectOutput对象，ObjectOutput负责实现序列化的功能，即将Java对象转化为字节序列
     * @param url URL address for the remote service
     * @param output the underlying output stream
     * @return serializer
     * @throws IOException
     */
    @Adaptive
    ObjectOutput serialize(URL url, OutputStream output) throws IOException;

    /**
     * Get a deserialization implementation instance
     * 创建一个ObjectInput对象，ObjectInput负责实现反序列化的功能，即将字节序列转换成Java对象
     *
     * @param url URL address for the remote service
     * @param input the underlying input stream
     * @return deserializer
     * @throws IOException
     */
    @Adaptive
    ObjectInput deserialize(URL url, InputStream input) throws IOException;

}
