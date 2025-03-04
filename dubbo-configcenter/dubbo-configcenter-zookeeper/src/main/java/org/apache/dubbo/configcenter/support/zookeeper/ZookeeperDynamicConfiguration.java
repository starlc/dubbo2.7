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
package org.apache.dubbo.configcenter.support.zookeeper;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.configcenter.ConfigurationListener;
import org.apache.dubbo.common.config.configcenter.TreePathDynamicConfiguration;
import org.apache.dubbo.common.threadpool.support.AbortPolicyWithReport;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;
import org.apache.dubbo.remoting.zookeeper.ZookeeperTransporter;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class ZookeeperDynamicConfiguration extends TreePathDynamicConfiguration {

    /**
     * 用于执行监听器的线程池。
     */
    private Executor executor;
    // The final root path would be: /configRootPath/"config"
    /**
     * 以 Zookeeper 作为配置中心时，配置也是以 ZNode 形式存储的，rootPath 记录了所有配置节点的根路径。
     */
    private String rootPath;
    /**
     * 与 Zookeeper 集群交互的客户端
     */
    private final ZookeeperClient zkClient;

    /**
     * 用于监听配置变化的监听器。
     */
    private CacheListener cacheListener;
    /**
     * 配置中心对应的 URL 对象。
     */
    private URL url;
    private static final int DEFAULT_ZK_EXECUTOR_THREADS_NUM = 1;
    private static final int DEFAULT_QUEUE = 10000;
    private static final Long THREAD_KEEP_ALIVE_TIME = 0L;

    ZookeeperDynamicConfiguration(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        this.url = url;
        // 根据URL中的config.namespace参数(默认值为dubbo)，确定配置中心ZNode的根路径
        rootPath = getRootPath(url);

        // 初始化initializedLatch以及cacheListener，
        // 在cacheListener注册成功之后，会调用cacheListener.countDown()方法
        //这个版本的就注册监听事件已经放在别的地方
        this.cacheListener = new CacheListener(rootPath);

        final String threadName = this.getClass().getSimpleName();
        // 初始化executor字段，用于执行监听器的逻辑 单线程的线程池
        this.executor = new ThreadPoolExecutor(DEFAULT_ZK_EXECUTOR_THREADS_NUM, DEFAULT_ZK_EXECUTOR_THREADS_NUM,
                THREAD_KEEP_ALIVE_TIME, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(DEFAULT_QUEUE),//10000
                new NamedThreadFactory(threadName, true),
                new AbortPolicyWithReport(threadName, url));

        // 初始化Zookeeper客户端
        zkClient = zookeeperTransporter.connect(url);
        boolean isConnected = zkClient.isConnected();
        if (!isConnected) {
            throw new IllegalStateException("Failed to connect with zookeeper, pls check if url " + url + " is correct.");
        }
    }

    /**
     * @param key e.g., {service}.configurators, {service}.tagrouters, {group}.dubbo.properties
     * ZookeeperDynamicConfiguration 中读取配置、写入配置的相关操作
     * @return
     */
    @Override
    public String getInternalProperty(String key) {
        // 直接从Zookeeper中读取对应的Key
        return zkClient.getContent(buildPathKey("", key));
    }

    @Override
    protected void doClose() throws Exception {
        zkClient.close();
        if (executor instanceof ExecutorService) {
            ExecutorService executorService = (ExecutorService) executor;
            if (!executorService.isShutdown()) {
                executorService.shutdown();
            }
        }
        cacheListener.removeAllListeners();
    }

    @Override
    protected boolean doPublishConfig(String pathKey, String content) throws Exception {
        // 在Zookeeper中创建对应ZNode节点用来存储配置信息
        zkClient.create(pathKey, content, false);
        return true;
    }

    @Override
    protected String doGetConfig(String pathKey) throws Exception {
        return zkClient.getContent(pathKey);
    }

    @Override
    protected boolean doRemoveConfig(String pathKey) throws Exception {
        zkClient.delete(pathKey);
        return true;
    }

    @Override
    protected Collection<String> doGetConfigKeys(String groupPath) {
        return zkClient.getChildren(groupPath);
    }

    @Override
    protected void doAddListener(String pathKey, ConfigurationListener listener) {
        cacheListener.addListener(pathKey, listener);
        zkClient.addDataListener(pathKey, cacheListener, executor);
    }

    @Override
    protected void doRemoveListener(String pathKey, ConfigurationListener listener) {
        cacheListener.removeListener(pathKey, listener);
        Set<ConfigurationListener> configurationListeners = cacheListener.getConfigurationListeners(pathKey);
        if (CollectionUtils.isEmpty(configurationListeners)) {
            zkClient.removeDataListener(pathKey, cacheListener);
        }
    }
}
