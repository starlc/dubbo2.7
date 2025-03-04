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
package org.apache.dubbo.registry.zookeeper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.URLStrParser;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.support.FailbackRegistry;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;
import org.apache.dubbo.rpc.RpcException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_SEPARATOR_ENCODED;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONFIGURATORS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.CONSUMERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDERS_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.ROUTERS_CATEGORY;

/**
 * ZookeeperRegistry
 */
public class ZookeeperRegistry extends FailbackRegistry {

    private static final String DEFAULT_ROOT = "dubbo";

    private final String root;

    private final Set<String> anyServices = new ConcurrentHashSet<>();

    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<>();

    private final ZookeeperClient zkClient;

    /**
     * 在 ZookeeperRegistry 的构造方法中，会通过 ZookeeperTransporter 创建 ZookeeperClient 实例并连接到 Zookeeper 集群，同时还会添加一个连接状态的监听器。
     * @param url
     * @param zookeeperTransporter
     */
    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        if (url.isAnyHost()) {
            throw new IllegalStateException("registry address == null");
        }
        String group = url.getParameter(GROUP_KEY, DEFAULT_ROOT);
        if (!group.startsWith(PATH_SEPARATOR)) {
            group = PATH_SEPARATOR + group;
        }
        this.root = group;
        //通过 ZookeeperTransporter 创建 ZookeeperClient 实例并连接到 Zookeeper 集群
        zkClient = zookeeperTransporter.connect(url);
        //添加连接状态监听
        //在该监听器中主要关注RECONNECTED 状态和 NEW_SESSION_CREATED 状态，
        // 在当前 Dubbo 节点与 Zookeeper 的连接恢复或是 Session 恢复的时候，会重新进行注册/订阅，防止数据丢失。
        zkClient.addStateListener((state) -> {
            if (state == StateListener.RECONNECTED) {
                logger.warn("Trying to fetch the latest urls, in case there're provider changes during connection loss.\n" +
                        " Since ephemeral ZNode will not get deleted for a connection lose, " +
                        "there's no need to re-register url of this instance.");
                //重新连接，意味着连接没有丢失 通过添加订阅失败重试任务再次获取
                ZookeeperRegistry.this.fetchLatestAddresses();
            } else if (state == StateListener.NEW_SESSION_CREATED) {
                //建立了新链接，通过重试任务完全对所有URL的重新注册/订阅
                logger.warn("Trying to re-register urls and re-subscribe listeners of this instance to registry...");
                try {
                    ZookeeperRegistry.this.recover();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            } else if (state == StateListener.SESSION_LOST) {
                logger.warn("Url of this instance will be deleted from registry soon. " +
                        "Dubbo client will try to re-register once a new session is created.");
            } else if (state == StateListener.SUSPENDED) {

            } else if (state == StateListener.CONNECTED) {

            }
        });
    }

    @Override
    public boolean isAvailable() {
        return zkClient.isConnected();
    }

    @Override
    public void destroy() {
        super.destroy();
        try {
            zkClient.close();
        } catch (Exception e) {
            logger.warn("Failed to close zookeeper client " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doRegister(URL url) {
        try {
            //默认创建临时性节点，除非指定了dynamic 为false
            //通过 ZookeeperClient 找到合适的路径，然后创建（或删除）相应的 ZNode 节点
            zkClient.create(toUrlPath(url), url.getParameter(DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    @Override
    public void doUnregister(URL url) {
        try {
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * doSubscribe() 方法的核心是通过 ZookeeperClient 在指定的 path 上添加 ChildListener 监听器，
     * 当订阅的节点发现变化的时候，会通过 ChildListener 监听器触发 notify() 方法，
     * 在 notify() 方法中会触发传入的 NotifyListener 监听器。
     *
     * doSubscribe() 方法的逻辑分为了两个大的分支。
     *
     * 订阅 URL 中明确指定了 Service 层接口的订阅请求。该分支会从 URL 拿到 Consumer 关注的 category 节点集合，
     * 然后在每个 category 节点上添加 ChildListener 监听器。
     * 下面是 Demo 示例中 Consumer 订阅的三个 path，图中展示了构造 path 各个部分的相关方法：
     *
     * doSubscribe() 方法的另一个分支是处理：监听所有 Service 层节点的订阅请求，
     * 例如，Monitor 就会发出这种订阅请求，因为它需要监控所有 Service 节点的变化。
     * 这个分支的处理逻辑是在根节点上添加一个 ChildListener 监听器，当有 Service 层的节点出现的时候，
     * 会触发这个 ChildListener，其中会重新触发 doSubscribe() 方法执行上一个分支的逻辑（即前面分析的针对确定的 Service 层接口订阅分支）。
     *
     * dubbo/interfaceName(servicePath)/providers(categoriea)
     * 节点层级结构：
     * /dubbo (root)
     *   /com.foo.BarService (service)
     *     /providers
     *       /url1
     *       /url2
     *     /configurators
     *     /routers
     *
     * 4. 完整订阅流程 ：
     * - 监听根节点 → 发现服务节点 → 订阅服务节点
     * - 监听服务节点 → 发现分类节点 → 订阅分类节点
     * - 监听分类节点 → 获取提供者URL → 通知消费者
     *
     * 使用了两种不同的 ChildListener：
     * 1. 匿名内部类 ChildListener ：
     * - 用于监听根节点（/dubbo）的变化
     * - 主要职责是发现新的服务接口
     * - 当发现新服务时，会触发对该服务的订阅
     *
     * 2. RegistryChildListenerImpl ：
     * - 用于监听具体服务节点的变化
     * - 监听 providers、configurators、routers 等分类节点
     * - 当服务提供者发生变化时，会通知消费者
     * @param url
     * @param listener
     */
    @Override
    public void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            //1.根节点订阅流程 （ANY_VALUE 场景）：
            if (ANY_VALUE.equals(url.getServiceInterface())) {
                String root = toRootPath();// 获取根节点 ，默认是 /dubbo
                // 获取NotifyListener对应的ChildListener
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.computeIfAbsent(url, k -> new ConcurrentHashMap<>());
                //1. 创建监听器 监听子节点变化
                ChildListener zkListener = listeners.computeIfAbsent(listener, k -> (parentPath, currentChilds) -> {
                    //遍历新的子节点
                    for (String child : currentChilds) {
                        child = URL.decode(child);
                        //// 记录该节点已经订阅过
                        if (!anyServices.contains(child)) {
                            anyServices.add(child);
                            // 该ChildListener要做的就是触发对具体Service节点的订阅
                            // 2. 递归订阅具体服务
                            subscribe(url.setPath(child).addParameters(INTERFACE_KEY, child,
                                    Constants.CHECK_KEY, String.valueOf(false)), k);
                        }
                    }
                });

                zkClient.create(root, false);// 保证根节点存在
                // 第一次订阅的时候，要处理当前已有的Service层节点
                // 3. 监听根节点
                List<String> services = zkClient.addChildListener(root, zkListener);
                if (CollectionUtils.isNotEmpty(services)) {//根节点下面的子节点不为空
                    for (String service : services) {
                        service = URL.decode(service);
                        anyServices.add(service);
                        //订阅子节点
                        subscribe(url.setPath(service).addParameters(INTERFACE_KEY, service,
                                Constants.CHECK_KEY, String.valueOf(false)), listener);
                    }
                }
            } else {
                //2.具体服务订阅流程 ：
                CountDownLatch latch = new CountDownLatch(1);
                try {
                    List<URL> urls = new ArrayList<>();
                    for (String path : toCategoriesPath(url)) {// 要订阅的所有path
                        // 生成服务的多个分类路径
                        // 例如：/dubbo/com.foo.BarService/providers
                        //      /dubbo/com.foo.BarService/configurators
                        //      /dubbo/com.foo.BarService/routers

                        // 订阅URL对应的Listener集合
                        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.computeIfAbsent(url, k -> new ConcurrentHashMap<>());
                        // 一个NotifyListener关联一个ChildListener，这个ChildListener会回调
                        // ZookeeperRegistry.notify()方法，其中会回调当前NotifyListener
                        // 1. 创建服务分类节点的监听器
                        ChildListener zkListener = listeners.computeIfAbsent(listener, k -> new RegistryChildListenerImpl(url, k, latch));
                        if (zkListener instanceof RegistryChildListenerImpl) {
                            ((RegistryChildListenerImpl) zkListener).setLatch(latch);
                        }
                        // 尝试创建持久节点，主要是为了确保当前path在Zookeeper上存在
                        // 2. 确保节点存在
                        zkClient.create(path, false);
                        // 这一个ChildListener会添加到多个path上
                        // 3. 添加监听并获取当前数据
                        List<String> children = zkClient.addChildListener(path, zkListener);
                        if (children != null) {
                            // 如果没有Provider注册，toUrlsWithEmpty()方法会返回empty协议的URL
                            // 4. 转换子节点数据为 URL 列表
                            urls.addAll(toUrlsWithEmpty(url, path, children));
                        }
                    }
                    // 初次订阅的时候，会主动调用一次notify()方法，通知NotifyListener处理当前已有的URL等注册数据 在这里会触发DubboProtoCol.refer方法
                    notify(url, listener, urls);
                } finally {
                    // tells the listener to run only after the sync notification of main thread finishes.
                    //唤醒zkListener 执行它的notify方法
                    latch.countDown();
                }
            }
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 将 URL 和 NotifyListener 对应的 ChildListener 从相关的 path 上删除，从而达到不再监听该 path 的效果
     * @param url
     * @param listener
     */
    @Override
    public void doUnsubscribe(URL url, NotifyListener listener) {
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            ChildListener zkListener = listeners.remove(listener);
            if (zkListener != null) {
                if (ANY_VALUE.equals(url.getServiceInterface())) {
                    String root = toRootPath();
                    zkClient.removeChildListener(root, zkListener);
                } else {
                    for (String path : toCategoriesPath(url)) {
                        zkClient.removeChildListener(path, zkListener);
                    }
                }
            }

            if(listeners.isEmpty()){
                zkListeners.remove(url);
            }
        }
    }

    @Override
    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            List<String> providers = new ArrayList<>();
            for (String path : toCategoriesPath(url)) {
                List<String> children = zkClient.getChildren(path);
                if (children != null) {
                    providers.addAll(children);
                }
            }
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    /**
     * 根目录 默认返回dubbo/
     * @return
     */
    private String toRootDir() {
        if (root.equals(PATH_SEPARATOR)) {
            return root;
        }
        return root + PATH_SEPARATOR;
    }

    private String toRootPath() {
        return root;
    }

    /**
     * 获取Provider URL的interface参数，默认值使用 provider URL path
     * like: dubbo/org.apache.dubbo.demo.DemoService
     * @param url
     * @return
     */
    private String toServicePath(URL url) {
        String name = url.getServiceInterface();
        if (ANY_VALUE.equals(name)) {
            return toRootPath();
        }
        return toRootDir() + URL.encode(name);
    }

    private String[] toCategoriesPath(URL url) {
        String[] categories;
        if (ANY_VALUE.equals(url.getParameter(CATEGORY_KEY))) {
            categories = new String[]{PROVIDERS_CATEGORY, CONSUMERS_CATEGORY, ROUTERS_CATEGORY, CONFIGURATORS_CATEGORY};
        } else {
            categories = url.getParameter(CATEGORY_KEY, new String[]{DEFAULT_CATEGORY});
        }
        String[] paths = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            paths[i] = toServicePath(url) + PATH_SEPARATOR + categories[i];
        }
        return paths;
    }

    /**
     * 获取Provider URL的category参数，默认值是provider
     * dubbo/org.apache.dubbo.demo.DemoService/providers
     * @param url
     * @return
     */
    private String toCategoryPath(URL url) {
        return toServicePath(url) + PATH_SEPARATOR + url.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
    }

    /**
     * 添加整个Provider URL的参数，默认值是provider
     * dubbo/org.apache.dubbo.demo.DemoService/providers/dubbo://192.xx.xx.xx......
     * @param url
     * @return
     */
    private String toUrlPath(URL url) {
        return toCategoryPath(url) + PATH_SEPARATOR + URL.encode(url.toFullString());
    }

    private List<URL> toUrlsWithoutEmpty(URL consumer, List<String> providers) {
        List<URL> urls = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(providers)) {
            for (String provider : providers) {
                if (provider.contains(PROTOCOL_SEPARATOR_ENCODED)) {
                    URL url = URLStrParser.parseEncodedStr(provider);
                    if (UrlUtils.isMatch(consumer, url)) {
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }

    private List<URL> toUrlsWithEmpty(URL consumer, String path, List<String> providers) {
        List<URL> urls = toUrlsWithoutEmpty(consumer, providers);
        if (CollectionUtils.isEmpty(urls)) {
            int i = path.lastIndexOf(PATH_SEPARATOR);
            String category = i < 0 ? path : path.substring(i + 1);
            URL empty = URLBuilder.from(consumer)
                    .setProtocol(EMPTY_PROTOCOL)
                    .addParameter(CATEGORY_KEY, category)
                    .build();
            urls.add(empty);
        }
        return urls;
    }

    /**
     * When zookeeper connection recovered from a connection loss, it need to fetch the latest provider list.
     * re-register watcher is only a side effect and is not mandate.
     */
    private void fetchLatestAddresses() {
        // subscribe
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<URL, Set<NotifyListener>>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Fetching the latest urls of " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    removeFailedSubscribed(url, listener);
                    addFailedSubscribed(url, listener);
                }
            }
        }
    }

    /**
     * - 用于监听具体服务节点的变化
     * - 监听 providers、configurators、routers 等分类节点
     * - 当服务提供者发生变化时，会通知消费者
     */
    private class RegistryChildListenerImpl implements ChildListener {

        private URL url;

        private NotifyListener listener;

        private volatile CountDownLatch latch;

        RegistryChildListenerImpl(URL url, NotifyListener listener, CountDownLatch latch) {
            this.url = url;
            this.listener = listener;
            this.latch = latch;
        }

        void setLatch(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void childChanged(String path, List<String> children) {
            try {
                //先阻塞回调事件 等待唤醒
                latch.await();
            } catch (InterruptedException e) {
                logger.warn("Zookeeper children listener thread was interrupted unexpectedly, may cause race condition with the main thread.");
            }
            ZookeeperRegistry.this.notify(url, listener, toUrlsWithEmpty(url, path, children));
        }
    }

}
