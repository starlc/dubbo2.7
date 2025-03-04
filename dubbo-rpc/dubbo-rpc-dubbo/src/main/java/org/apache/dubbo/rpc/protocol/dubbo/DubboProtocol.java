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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.LAZY_CONNECT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.METHODS_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.STUB_EVENT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.remoting.Constants.CHANNEL_READONLYEVENT_SENT_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_HEARTBEAT;
import static org.apache.dubbo.remoting.Constants.DEFAULT_REMOTING_CLIENT;
import static org.apache.dubbo.remoting.Constants.HEARTBEAT_KEY;
import static org.apache.dubbo.remoting.Constants.SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_REMOTING_SERVER;
import static org.apache.dubbo.rpc.Constants.DEFAULT_STUB_EVENT;
import static org.apache.dubbo.rpc.Constants.IS_SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_METHODS_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.CALLBACK_SERVICE_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DEFAULT_SHARE_CONNECTIONS;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.IS_CALLBACK_SERVICE;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_CONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_DISCONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.OPTIMIZER_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.SHARE_CONNECTIONS_KEY;


/**
 * dubbo protocol support.
 * Dubbo 默认使用的 Protocol 实现类—— DubboProtocol 实现。
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    public static final int DEFAULT_PORT = 20880;
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    private static volatile DubboProtocol INSTANCE;
    private static Object MONITOR = new Object();

    /**
     * <host:port,Exchanger>
     * {@link Map<String, List<ReferenceCountExchangeClient>}
     */
    private final Map<String, Object> referenceClientMap = new ConcurrentHashMap<>();
    private static final Object PENDING_OBJECT = new Object();
    private final Set<String> optimizers = new ConcurrentHashSet<>();

    /**
     * 实现了 ExchangeHandlerAdapter 抽象类的匿名内部类的实例，间接实现了 ExchangeHandler 接口，
     * 其核心是 reply() 方法
     */
    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {

            if (!(message instanceof Invocation)) {
                throw new RemotingException(channel, "Unsupported request: "
                        + (message == null ? null : (message.getClass().getName() + ": " + message))
                        + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
            }
            // 这里省略了检查message类型的逻辑，通过前面Handler的处理，这里收到的message必须是Invocation类型的对象
            Invocation inv = (Invocation) message;
            // 获取此次调用Invoker对象
            Invoker<?> invoker = getInvoker(channel, inv);
            // 针对客户端回调的内容，在后面详细介绍，这里不再展开分析
            // need to consider backward-compatibility if it's a callback
            if (Boolean.TRUE.toString().equals(inv.getObjectAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                String methodsStr = invoker.getUrl().getParameters().get(METHODS_KEY);
                boolean hasMethod = false;
                if (methodsStr == null || !methodsStr.contains(COMMA_SEPARATOR)) {
                    hasMethod = inv.getMethodName().equals(methodsStr);
                } else {
                    String[] methods = methodsStr.split(COMMA_SEPARATOR);
                    for (String method : methods) {
                        if (inv.getMethodName().equals(method)) {
                            hasMethod = true;
                            break;
                        }
                    }
                }
                if (!hasMethod) {
                    logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                            + " not found in callback service interface ,invoke will be ignored."
                            + " please update the api interface. url is:"
                            + invoker.getUrl()) + " ,invocation is :" + inv);
                    return null;
                }
            }

            // 将客户端的地址记录到RpcContext中
            RpcContext.getContext().setRemoteAddress(channel.getRemoteAddress());
            // 执行真正的调用
            Result result = invoker.invoke(inv);
            // 返回结果
            return result.thenApply(Function.identity());
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);

            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isDebugEnabled()) {
                logger.debug("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        /**
         * FIXME channel.getUrl() always binds to a fixed service, and this service is random.
         * we can choose to use a common service to carry onConnect event if there's no easy way to get the specific
         * service this connection is binding to.
         * @param channel
         * @param url
         * @param methodKey
         * @return
         */
        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }

            RpcInvocation invocation = new RpcInvocation(method, url.getParameter(INTERFACE_KEY), "", new Class<?>[0], new Object[0]);
            invocation.setAttachment(PATH_KEY, url.getPath());
            invocation.setAttachment(GROUP_KEY, url.getParameter(GROUP_KEY));
            invocation.setAttachment(INTERFACE_KEY, url.getParameter(INTERFACE_KEY));
            invocation.setAttachment(VERSION_KEY, url.getParameter(VERSION_KEY));
            if (url.getParameter(STUB_EVENT_KEY, false)) {
                invocation.setAttachment(STUB_EVENT_KEY, Boolean.TRUE.toString());
            }

            return invocation;
        }
    };

    public DubboProtocol() {
    }

    public static DubboProtocol getDubboProtocol() {
        if (null == INSTANCE) {
            synchronized (MONITOR) {
                if (null == INSTANCE) {
                    INSTANCE = (DubboProtocol) ExtensionLoader.getExtensionLoader(Protocol.class).getOriginalInstance(DubboProtocol.NAME);
                }
            }
        }
        return INSTANCE;
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        int port = channel.getLocalAddress().getPort();
        String path = (String) inv.getObjectAttachments().get(PATH_KEY);
        // 省略对客户端Callback以及stub的处理逻辑，后面单独介绍
        //if it's stub service on client side(after enable stubevent, usually is set up onconnect or ondisconnect method)
        //如果是客户端的代理服务（存根服务）将端口设置为0
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getObjectAttachments().get(STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            //when a stub service export to local, it usually can't be exposed to port
            port = 0;
        }

        // if it's callback service on client side
        // 客户端的回调服务 地址修改为服务器地址
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path += "." + inv.getObjectAttachments().get(CALLBACK_SERVICE_KEY);
            inv.getObjectAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }

        //重新构建serviceKey 拿到真正的Invoker对象
        String serviceKey = serviceKey(
                port,
                path,
                (String) inv.getObjectAttachments().get(VERSION_KEY),
                (String) inv.getObjectAttachments().get(GROUP_KEY)
        );
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.getExport(serviceKey);

        //  查找不到相应的DubboExporter对象时，会直接抛出异常
        if (exporter == null) {
            throw new RemotingException(channel,
                    "Not found exported service: " + serviceKey + " in " + exporterMap.getExporterMap().keySet() + ", may be version or group mismatch " +
                            ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() +
                            ", message:" + getInvocationWithoutData(inv));
        }
        // 获取exporter中获取Invoker对象
        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        URL url = invoker.getUrl();
        // 创建ServiceKey，其核心实现在前文已经详细分析过了，这里不再重复 group + serviceName :version :port
        // export service.
        String key = serviceKey(url);
        // 将上层传入的Invoker对象封装成DubboExporter对象，然后记录到exporterMap集合中
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
        exporterMap.addExportMap(key, exporter);

        //export an stub service for dispatching event
        Boolean isStubSupportEvent = url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }

            }
        }
        // 启动ProtocolServer
        openServer(url);
        // 进行序列化的优化处理
        optimizeSerialization(url);

        return exporter;
    }

    private void openServer(URL url) {
        // find server.
        String key = url.getAddress();// 获取host:port这个地址
        //client can export a service which's only for server to invoke
        boolean isServer = url.getParameter(IS_SERVER_KEY, true);
        if (isServer) {// 只有Server端才能启动Server对象
            ProtocolServer server = serverMap.get(key);
            if (server == null) {// 无ProtocolServer监听该地址
                synchronized (this) {// DoubleCheck，防止并发问题
                    server = serverMap.get(key);
                    if (server == null) {
                        // 调用createServer()方法创建ProtocolServer对象
                        serverMap.put(key, createServer(url));
                    }
                }
            } else {
                // server supports reset, use together with override
                // 如果已有ProtocolServer实例，则尝试根据URL信息重置ProtocolServer
                server.reset(url);
            }
        }
    }

    /**
     * createServer() 方法首先会为 URL 添加一些默认值，同时会进行一些参数值的检测，主要有五个。
     *
     * HEARTBEAT_KEY 参数值，默认值为 60000，表示默认的心跳时间间隔为 60 秒。
     *
     * CHANNEL_READONLYEVENT_SENT_KEY 参数值，默认值为 true，表示 ReadOnly 请求需要阻塞等待响应返回。
     * 在 Server 关闭的时候，只能发送 ReadOnly 请求，这些 ReadOnly 请求由这里设置的 CHANNEL_READONLYEVENT_SENT_KEY 参数值决定是否需要等待响应返回。
     *
     * CODEC_KEY 参数值，默认值为 dubbo。你可以回顾 Codec2 接口中 @Adaptive 注解的参数，都是获取该 URL 中的 CODEC_KEY 参数值。
     *
     * 检测 SERVER_KEY 参数指定的扩展实现名称是否合法，默认值为 netty。
     * 你可以回顾 Transporter 接口中 @Adaptive 注解的参数，它决定了 Transport 层使用的网络库实现，默认使用 Netty 4 实现。
     *
     * 检测 CLIENT_KEY 参数指定的扩展实现名称是否合法。同 SERVER_KEY 参数的检查流程。
     * @param url
     * @return
     */
    private ProtocolServer createServer(URL url) {
        url = URLBuilder.from(url)
                // send readonly event when server closes, it's enabled by default
                // ReadOnly请求是否阻塞等待
                .addParameterIfAbsent(CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())//默认值为 true，表示 ReadOnly 请求需要阻塞等待响应返回
                // enable heartbeat by default
                // 心跳间隔
                .addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT))//默认值为 60000，表示默认的心跳时间间隔为 60 秒。
                .addParameter(CODEC_KEY, DubboCodec.NAME)//Codec2扩展实现 参数值，默认值为 dubbo
                .build();
        // 检测SERVER_KEY参数指定的Transporter扩展实现是否合法
        String str = url.getParameter(SERVER_KEY, DEFAULT_REMOTING_SERVER);//指定的扩展实现名称是否合法，默认值为 netty

        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }

        ExchangeServer server;
        try {
            //通过Exchangers门面类，创建ExchangeServer对象
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        // 检测 CLIENT_KEY 参数指定的扩展实现名称是否合法
        str = url.getParameter(CLIENT_KEY);
        if (str != null && str.length() > 0) {
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }
        // 将ExchangeServer封装成DubboProtocolServer返回
        return new DubboProtocolServer(server);
    }

    /**
     * 这里先介绍一个基础知识，在使用某些序列化算法（例如， Kryo、FST 等）时，
     * 为了让其能发挥出最佳的性能，最好将那些需要被序列化的类提前注册到 Dubbo 系统中。
     * 例如，我们可以通过一个实现了 SerializationOptimizer 接口的优化器，并在配置中指定该优化器
     *
     * 在 DubboProtocol.optimizeSerialization() 方法中，就会获取该优化器中注册的类，
     * 通知底层的序列化算法进行优化，序列化的性能将会被大大提升。
     *
     * 当然，在进行序列化的时候，难免会级联到很多 Java 内部的类（例如，数组、各种集合类型等），
     * Kryo、FST 等序列化算法已经自动将JDK 中的常用类进行了注册，所以无须重复注册它们。
     * @param url
     * @throws RpcException
     */
    private void optimizeSerialization(URL url) throws RpcException {
        // 根据URL中的optimizer参数值，确定SerializationOptimizer接口的实现
        String className = url.getParameter(OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException(
                        "The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }
            // 创建SerializationOptimizer实现类的对象
            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }
            // 调用getSerializableClasses()方法获取需要注册的类
            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            optimizers.add(className);

        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);

        } catch (InstantiationException | IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);

        }
    }

    /**
     * 消费端 创建一个DubboInvoker对象
     * @param serviceType
     * @param url
     * @return
     * @param <T>
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {
        // 进行序列化优化，注册需要优化的类
        optimizeSerialization(url);

        // create rpc invoker.
        // 创建 DubboInvoker 对象
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);
        // 将上面创建DubboInvoker对象添加到invoker集合之中
        invokers.add(invoker);

        return invoker;
    }

    /**
     * 它创建了底层发送请求和接收响应的 Client 集合，其核心分为了两个部分，一个是针对共享连接的处理，另一个是针对独享连接的处理
     * 消费端
     * @param url
     * @return
     */
    private ExchangeClient[] getClients(URL url) {
        // whether to share connection
        // 是否使用共享连接
        // CONNECTIONS_KEY参数值决定了后续建立连接的数量
        int connections = url.getParameter(CONNECTIONS_KEY, 0);
        // if not configured, connection is shared, otherwise, one connection for one service
        if (connections == 0) {// 如果没有连接数的相关配置，默认使用共享连接的方式
            /*
             * The xml configuration should have a higher priority than properties.
             *  // 确定建立共享连接的条数，默认只建立一条共享连接 shareconnections 1
             */
            String shareConnectionsStr = url.getParameter(SHARE_CONNECTIONS_KEY, (String) null);
            connections = Integer.parseInt(StringUtils.isBlank(shareConnectionsStr) ? ConfigUtils.getProperty(SHARE_CONNECTIONS_KEY,
                    DEFAULT_SHARE_CONNECTIONS) : shareConnectionsStr);
            // 创建公共ExchangeClient集合
            return getSharedClient(url, connections).toArray(new ExchangeClient[0]);
        } else {
            // 整理要返回的ExchangeClient集合
            ExchangeClient[] clients = new ExchangeClient[connections];
            for (int i = 0; i < clients.length; i++) {
                // 不使用公共连接的情况下，会创建单独的ExchangeClient实例
                //当使用独享连接的时候，对每个 Service 建立固定数量的 Client，每个 Client 维护一个底层连接。
                clients[i] = initClient(url);
            }
            return clients;
        }

    }

    /**
     * Get shared connection
     * 当使用共享连接的时候，会区分不同的网络地址（host:port），一个地址只建立固定数量的共享连接。
     * 如下图所示，Provider 1 暴露了多个服务，Consumer 引用了 Provider 1 中的多个服务，
     * 共享连接是说 Consumer 调用 Provider 1 中的多个服务时，是通过固定数量的共享 TCP 长连接进行数据传输，
     * 这样就可以达到减少服务端连接数的目的。
     * @param url
     * @param connectNum connectNum must be greater than or equal to 1
     */
    @SuppressWarnings("unchecked")
    private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {
        String key = url.getAddress();// 获取对端的地址(host:port)
        // 从referenceClientMap集合中，获取与该地址连接的ReferenceCountExchangeClient集合
        Object clients = referenceClientMap.get(key);
        if (clients instanceof List) {
            List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;
            // checkClientCanUse()方法中会检测clients集合中的客户端是否全部可用
            if (checkClientCanUse(typedClients)) {
                batchClientRefIncr(typedClients); // 客户端全部可用时
                return typedClients;
            }
        }

        List<ReferenceCountExchangeClient> typedClients = null;

        synchronized (referenceClientMap) {// 针对指定地址的客户端进行加锁，分区加锁可以提高并发度
            for (; ; ) {
                clients = referenceClientMap.get(key);

                if (clients instanceof List) {
                    typedClients = (List<ReferenceCountExchangeClient>) clients;
                    if (checkClientCanUse(typedClients)) {// double check，再次检测客户端是否全部可用
                        batchClientRefIncr(typedClients);// 增加应用Client的次数
                        return typedClients;
                    } else {
                        referenceClientMap.put(key, PENDING_OBJECT);
                        break;
                    }
                } else if (clients == PENDING_OBJECT) {
                    try {
                        referenceClientMap.wait();
                    } catch (InterruptedException ignored) {
                    }
                } else {
                    referenceClientMap.put(key, PENDING_OBJECT);
                    break;
                }
            }
        }

        try {
            // connectNum must be greater than or equal to 1
            // 至少一个共享连接
            connectNum = Math.max(connectNum, 1);

            // If the clients is empty, then the first initialization is
            // 如果当前Clients集合为空，则直接通过initClient()方法初始化所有共享客户端
            if (CollectionUtils.isEmpty(typedClients)) {
                //创建指定个数的ReferenceCountExchangeClient
                typedClients = buildReferenceCountExchangeClientList(url, connectNum);
            } else {// 如果只有部分共享客户端不可用，则只需要处理这些不可用的客户端
                for (int i = 0; i < typedClients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = typedClients.get(i);
                    // If there is a client in the list that is no longer available, create a new one to replace him.
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        typedClients.set(i, buildReferenceCountExchangeClient(url));
                        continue;
                    }
                    // 增加引用
                    referenceCountExchangeClient.incrementAndGetCount();
                }
            }
        } finally {
            synchronized (referenceClientMap) {
                // 清理locks集合中的锁对象，防止内存泄漏，如果key对应的服务宕机或是下线，
                // 这里不进行清理的话，这个用于加锁的Object对象是无法被GC的，从而出现内存泄
                if (typedClients == null) {
                    referenceClientMap.remove(key);
                } else {
                    referenceClientMap.put(key, typedClients);
                }
                referenceClientMap.notifyAll();
            }
        }
        return typedClients;

    }

    /**
     * Check if the client list is all available
     *
     * @param referenceCountExchangeClients
     * @return true-available，false-unavailable
     */
    private boolean checkClientCanUse(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return false;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            // As long as one client is not available, you need to replace the unavailable client with the available one.
            if (referenceCountExchangeClient == null || referenceCountExchangeClient.getCount() <= 0 ||
                    referenceCountExchangeClient.isClosed()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Increase the reference Count if we create new invoker shares same connection, the connection will be closed without any reference.
     * 会遍历传入的集合，将其中的每个 ReferenceCountExchangeClient 对象的引用加一
     * @param referenceCountExchangeClients
     */
    private void batchClientRefIncr(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            if (referenceCountExchangeClient != null) {
                referenceCountExchangeClient.incrementAndGetCount();
            }
        }
    }

    /**
     * Bulk build client
     *
     * @param url
     * @param connectNum
     * @return
     */
    private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(URL url, int connectNum) {
        List<ReferenceCountExchangeClient> clients = new ArrayList<>();

        for (int i = 0; i < connectNum; i++) {
            clients.add(buildReferenceCountExchangeClient(url));
        }

        return clients;
    }

    /**
     * Build a single client
     *调用上面介绍的 initClient() 创建 Client 对象，然后再包装一层 ReferenceCountExchangeClient 进行修饰，
     * 最后返回。该方法主要用于创建共享 Client。
     * @param url
     * @return
     */
    private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {
        ExchangeClient exchangeClient = initClient(url);

        return new ReferenceCountExchangeClient(exchangeClient);
    }

    /**
     * Create new connection
     * 当使用独享连接的时候，对每个 Service 建立固定数量的 Client，每个 Client 维护一个底层连接。
     * 这里应该是属于消费端独调用的方法
     * @param url
     */
    private ExchangeClient initClient(URL url) {

        // client type setting.
        // 获取客户端扩展名并进行检查，省略检测的逻辑
        String str = url.getParameter(CLIENT_KEY, url.getParameter(SERVER_KEY, DEFAULT_REMOTING_CLIENT));
        // 设置Codec2的扩展名
        url = url.addParameter(CODEC_KEY, DubboCodec.NAME);
        // enable heartbeat by default
        // 设置默认的心跳间隔 60s
        url = url.addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT));

        // BIO is not allowed since it has severe performance issue.
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " +
                    StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // connection should be lazy
            // 如果配置了延迟创建连接的特性，则创建LazyConnectExchangeClient
            //注意 dubbo默认在DubboRegistryFactory.getRegistryURL 设置了此参数LAZY_CONNECT_KEY为true
            if (url.getParameter(LAZY_CONNECT_KEY, false)) {
                client = new LazyConnectExchangeClient(url, requestHandler);

            } else {
                // 未使用延迟连接功能，则直接创建HeaderExchangeClient
                client = Exchangers.connect(url, requestHandler);
            }

        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }

        return client;
    }

    /**
     * 在 DubboProtocol 销毁的时候，会调用 destroy() 方法释放底层资源，其中就涉及 export
     * 流程中创建的 ProtocolServer 对象以及 refer 流程中创建的 Client。
     */
    @Override
    @SuppressWarnings("unchecked")
    public void destroy() {
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ProtocolServer protocolServer = serverMap.remove(key);

            if (protocolServer == null) {
                continue;
            }

            RemotingServer server = protocolServer.getRemotingServer();

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Close dubbo server: " + server.getLocalAddress());
                }
                // 在close()方法中，发送ReadOnly请求、阻塞指定时间、关闭底层的定时任务、关闭相关线程池，
                // 最终，会断开所有连接，关闭Server。这些逻辑在前文介绍HeaderExchangeServer、NettyServer等实现的时候，已经详细分析过了，这里不再展开

                //ConfigurationUtils.getServerShutdownTimeout() 方法返回的阻塞时长默认是 10 秒，
                // 我们可以通过 dubbo.service.shutdown.wait 或是 dubbo.service.shutdown.wait.seconds 进行配置。
                server.close(ConfigurationUtils.getServerShutdownTimeout());

            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

        //ReferenceCountExchangeClient 的存在，只有引用减到 0，底层的 Client 才会真正销毁
        for (String key : new ArrayList<>(referenceClientMap.keySet())) {
            Object clients = referenceClientMap.remove(key);
            if (clients instanceof List) {
                List<ReferenceCountExchangeClient> typedClients = (List<ReferenceCountExchangeClient>) clients;

                if (CollectionUtils.isEmpty(typedClients)) {
                    continue;
                }

                for (ReferenceCountExchangeClient client : typedClients) {
                    closeReferenceCountExchangeClient(client);
                }
            }
        }

        super.destroy();
    }

    /**
     * close ReferenceCountExchangeClient
     *
     * @param client
     */
    private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
        if (client == null) {
            return;
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
            }

            client.close(ConfigurationUtils.getServerShutdownTimeout());

            // TODO
            /*
             * At this time, ReferenceCountExchangeClient#client has been replaced with LazyConnectExchangeClient.
             * Do you need to call client.close again to ensure that LazyConnectExchangeClient is also closed?
             */

        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    /**
     * only log body in debugger mode for size & security consideration.
     *
     * @param invocation
     * @return
     */
    private Invocation getInvocationWithoutData(Invocation invocation) {
        if (logger.isDebugEnabled()) {
            return invocation;
        }
        if (invocation instanceof RpcInvocation) {
            RpcInvocation rpcInvocation = (RpcInvocation) invocation;
            rpcInvocation.setArguments(null);
            return rpcInvocation;
        }
        return invocation;
    }
}
