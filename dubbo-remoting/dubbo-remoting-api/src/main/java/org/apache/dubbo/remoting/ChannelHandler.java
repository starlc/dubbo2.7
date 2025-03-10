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

import org.apache.dubbo.common.extension.SPI;


/**
 * ChannelHandler. (API, Prototype, ThreadSafe)
 *
 * @see org.apache.dubbo.remoting.Transporter#bind(org.apache.dubbo.common.URL, ChannelHandler)
 * @see org.apache.dubbo.remoting.Transporter#connect(org.apache.dubbo.common.URL, ChannelHandler)
 *
 * ChannelHandler 是注册在 Channel 上的消息处理器
 * 主要用来处理IO上面的事件connected 、disconnected 、sent等
 * AbstractServer、AbstractClient 以及 Channel 实现，都是通过 AbstractPeer 实现了 ChannelHandler 接口，
 * 但只是做了一层简单的委托（也可以说成是装饰器），将全部方法委托给了其底层关联的 ChannelHandler 对象。
 */
@SPI
public interface ChannelHandler {

    /**
     * on channel connected.
     * 已连接
     * @param channel channel.
     */
    void connected(Channel channel) throws RemotingException;

    /**
     * on channel disconnected.
     * 已断开
     * @param channel channel.
     */
    void disconnected(Channel channel) throws RemotingException;

    /**
     * on message sent.
     * 消息已发送
     * @param channel channel.
     * @param message message.
     */
    void sent(Channel channel, Object message) throws RemotingException;

    /**
     * on message received.
     * 消息已接收
     * @param channel channel.
     * @param message message.
     */
    void received(Channel channel, Object message) throws RemotingException;

    /**
     * on exception caught.
     * 异常已捕获
     * @param channel   channel.
     * @param exception exception.
     */
    void caught(Channel channel, Throwable exception) throws RemotingException;

}