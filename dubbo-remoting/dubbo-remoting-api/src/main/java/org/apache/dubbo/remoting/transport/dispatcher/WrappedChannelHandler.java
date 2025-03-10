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
package org.apache.dubbo.remoting.transport.dispatcher;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.threadpool.manager.ExecutorRepository;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.transport.ChannelHandlerDelegate;

import java.util.concurrent.ExecutorService;

/**
 * WrappedChannelHandler 仅仅是封装了另一个 ChannelHandler 对象
 * 其子类主要是决定了 Dubbo 以何种线程模型处理收到的事件和消息，就是所谓的“消息派发机制”，
 * 与前面介绍的 ThreadPool 有紧密的联系。
 *
 * 默认到这一层的handler有关的方法都是IO线程在执行，其子类将对应的事件放到线程池中执行
 */
public class WrappedChannelHandler implements ChannelHandlerDelegate {

    protected static final Logger logger = LoggerFactory.getLogger(WrappedChannelHandler.class);

    protected final ChannelHandler handler;

    protected final URL url;

    public WrappedChannelHandler(ChannelHandler handler, URL url) {
        this.handler = handler;
        this.url = url;
    }

    public void close() {

    }

    @Override
    public void connected(Channel channel) throws RemotingException {
        handler.connected(channel);
    }

    @Override
    public void disconnected(Channel channel) throws RemotingException {
        handler.disconnected(channel);
    }

    @Override
    public void sent(Channel channel, Object message) throws RemotingException {
        handler.sent(channel, message);
    }

    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        handler.received(channel, message);
    }

    @Override
    public void caught(Channel channel, Throwable exception) throws RemotingException {
        handler.caught(channel, exception);
    }

    protected void sendFeedback(Channel channel, Request request, Throwable t) throws RemotingException {
        if (request.isTwoWay()) {
            String msg = "Server side(" + url.getIp() + "," + url.getPort()
                    + ") thread pool is exhausted, detail msg:" + t.getMessage();
            Response response = new Response(request.getId(), request.getVersion());
            response.setStatus(Response.SERVER_THREADPOOL_EXHAUSTED_ERROR);
            response.setErrorMessage(msg);
            channel.send(response);
            return;
        }
    }

    @Override
    public ChannelHandler getHandler() {
        if (handler instanceof ChannelHandlerDelegate) {
            return ((ChannelHandlerDelegate) handler).getHandler();
        } else {
            return handler;
        }
    }

    public URL getUrl() {
        return url;
    }

    /**
     * Currently, this method is mainly customized to facilitate the thread model on consumer side.
     * 1. Use ThreadlessExecutor, aka., delegate callback directly to the thread initiating the call.
     * 2. Use shared executor to execute the callback.
     *方法对响应做了特殊处理：如果请求在发送的时候指定了关联的线程池，在收到对应的响应消息的时候，会优先根据请求的 ID 查找请求关联的线程池处理响应。
     * @param msg
     * @return
     */
    public ExecutorService getPreferredExecutorService(Object msg) {
        if (msg instanceof Response) {
            Response response = (Response) msg;
            DefaultFuture responseFuture = DefaultFuture.getFuture(response.getId());// 获取请求关联的DefaultFuture
            // a typical scenario is the response returned after timeout, the timeout response may has completed the future
            if (responseFuture == null) {
                return getSharedExecutorService();
            } else { // 如果请求关联了线程池，则会获取相关的线程来处理响应
                ExecutorService executor = responseFuture.getExecutor();
                if (executor == null || executor.isShutdown()) {
                    executor = getSharedExecutorService();
                }
                return executor;
            }
        } else {// 如果是请求消息，则直接使用公共的线程池处理
            return getSharedExecutorService();
        }
    }

    /**
     * get the shared executor for current Server or Client
     *
     * @return
     */
    public ExecutorService getSharedExecutorService() {
        ExecutorRepository executorRepository =
                ExtensionLoader.getExtensionLoader(ExecutorRepository.class).getDefaultExtension();
        ExecutorService executor = executorRepository.getExecutor(url);
        if (executor == null) {
            executor = executorRepository.createExecutorIfAbsent(url);
        }
        return executor;
    }

    @Deprecated
    public ExecutorService getExecutorService() {
        //按照当前端点（Server/Client）的 URL 从 ExecutorRepository 中获取相应的公共线程池。
        return getSharedExecutorService();
    }


}
