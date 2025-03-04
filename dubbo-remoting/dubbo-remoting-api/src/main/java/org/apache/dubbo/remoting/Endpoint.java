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
package org.apache.dubbo.remoting;

import org.apache.dubbo.common.URL;

import java.net.InetSocketAddress;

/**
 * Endpoint. (API/SPI, Prototype, ThreadSafe)在 Dubbo 中会抽象出一个“端点（Endpoint）”的概念，
 * 我们可以通过一个 ip 和 port 唯一确定一个端点，两个端点之间会创建 TCP 连接，可以双向传输数据。
 * Dubbo 将 Endpoint 之间的 TCP 连接抽象为通道（Channel），
 * 将发起请求的 Endpoint 抽象为客户端（Client），
 * 将接收请求的 Endpoint 抽象为服务端（Server）。
 * 这些抽象出来的概念，也是整个 dubbo-remoting-api 模块的基础
 *
 * @see org.apache.dubbo.remoting.Channel
 * @see org.apache.dubbo.remoting.Client
 * @see RemotingServer
 */
public interface Endpoint {

    /**
     * get url.
     * 关联的 URL 信息
     *
     * @return url
     */
    URL getUrl();

    /**
     * get channel handler.
     * 底层 Channel 关联的 ChannelHandler
     *
     * @return channel handler
     */
    ChannelHandler getChannelHandler();

    /**
     * get local address.
     * Endpoint 的本地地址
     *
     * @return local address.
     */
    InetSocketAddress getLocalAddress();

    /**
     * send message.
     * send() 方法负责数据发送,注意 这里没有返回值
     * @param message
     * @throws RemotingException
     */
    void send(Object message) throws RemotingException;

    /**
     * send message.
     *
     * @param message
     * @param sent    already sent to socket?
     */
    void send(Object message, boolean sent) throws RemotingException;

    /**
     * close the channel.
     * close() 方法用于关闭底层 Channel
     */
    void close();

    /**
     * Graceful close the channel.
     */
    void close(int timeout);

    /**
     * startClose() 方法用于关闭底层 Channe
     */
    void startClose();

    /**
     * is closed.
     *
     * @return closed
     */
    boolean isClosed();

}