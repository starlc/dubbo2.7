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
package org.apache.dubbo.registry.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.APPLICATION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.COMMA_SPLIT_PATTERN;
import static org.apache.dubbo.common.constants.CommonConstants.FILE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.ACCEPTS_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.CATEGORY_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.DEFAULT_CATEGORY;
import static org.apache.dubbo.common.constants.RegistryConstants.DYNAMIC_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.EMPTY_PROTOCOL;
import static org.apache.dubbo.registry.Constants.REGISTRY_FILESAVE_SYNC_KEY;
import static org.apache.dubbo.registry.Constants.REGISTRY__LOCAL_FILE_CACHE_ENABLED;

/**
 * AbstractRegistry. (SPI, Prototype, ThreadSafe)
 * AbstractRegistry 实现了 Registry 接口，虽然 AbstractRegistry 本身在内存中实现了注册数据的读写功能，
 * 也没有什么抽象方法，但它依然被标记成了抽象类，
 * 从前面的Registry 继承关系图中可以看出，Registry 接口的所有实现类都继承了 AbstractRegistry。
 *
 * 为了减轻注册中心组件的压力，AbstractRegistry 会把当前节点订阅的 URL 信息缓存到本地的 Properties 文件中
 *
 *  AbstractRegistry 依赖这些字段都提供了哪些公共能力:
 *  1. 本地缓存
 *  作为一个 RPC 框架，Dubbo 在微服务架构中解决了各个服务间协作的难题；
 *  作为 Provider 和 Consumer 的底层依赖，它会与服务一起打包部署。
 *  dubbo-registry 也仅仅是其中一个依赖包，负责完成与 ZooKeeper、etcd、Consul 等服务发现组件的交互。
 *
 *   当 Provider 端暴露的 URL 发生变化时，ZooKeeper 等服务发现组件会通知 Consumer 端的 Registry 组件，
 *   Registry 组件会调用 notify() 方法，被通知的 Consumer 能匹配到所有 Provider 的 URL 列表并写入 properties 集合中。
 *
 *   AbstractRegistry 的核心是本地文件缓存的功能。 在 AbstractRegistry 的构造方法中，
 *   会调用 loadProperties() 方法将上面写入的本地缓存文件，加载到 properties 对象中。
 *   在网络抖动等原因而导致订阅失败时，Consumer 端的 Registry 就可以调用 getCacheUrls() 方法获取本地缓存，
 *   从而得到最近注册的 Provider URL。
 *   可见，AbstractRegistry 通过本地缓存提供了一种容错机制，保证了服务的可靠性。
 *
 *   2. 注册/订阅
 *   AbstractRegistry 实现了 Registry 接口，
 *   它实现的 registry() 方法会将当前节点要注册的 URL 缓存到 registered 集合，
 *   而 unregistry() 方法会从 registered 集合删除指定的 URL，例如当前节点下线的时候。
 *   subscribe() 方法会将当前节点作为 Consumer 的 URL 以及相关的 NotifyListener 记录到 subscribed 集合，
 *   unsubscribe() 方法会将当前节点的 URL 以及关联的 NotifyListener 从 subscribed 集合删除。
 *
 *   单看 AbstractRegistry 的实现，上述四个基础的注册、订阅方法都是内存操作，但是 Java 有继承和多态的特性，
 *   AbstractRegistry 的子类会覆盖上述四个基础的注册、订阅方法进行增强。
 *
 *   3. 恢复/销毁
 *   AbstractRegistry 中还有另外两个需要关注的方法：recover() 方法和destroy() 方法。
 *   在 Provider 因为网络问题与注册中心断开连接之后，会进行重连，重新连接成功之后，
 *   会调用 recover() 方法将 registered 集合中的全部 URL 重新走一遍 register() 方法，恢复注册数据。
 *   同样，recover() 方法也会将 subscribed 集合中的 URL 重新走一遍 subscribe() 方法，恢复订阅监听器。
 *   recover() 方法的具体实现比较简单，这里就不再展示，你若感兴趣的话，可以参考源码进行学习。
 *
 *    在当前节点下线的时候，会调用 Node.destroy() 方法释放底层资源。
 *    AbstractRegistry 实现的 destroy() 方法会调用 unregister() 方法和
 *    unsubscribe() 方法将当前节点注册的 URL 以及订阅的监听全部清理掉，
 *    其中不会清理非动态注册的 URL（即 dynamic 参数明确指定为 false）。AbstractRegistry 中 destroy() 方法的实现比较简单
 */
public abstract class AbstractRegistry implements Registry {

    // URL address separator, used in file cache, service provider URL separation
    private static final char URL_SEPARATOR = ' ';
    // URL address separated regular expression for parsing the service provider URL list in the file cache
    private static final String URL_SPLIT = "\\s+";
    // Max times to retry to save properties to local cache file
    private static final int MAX_RETRY_TIMES_SAVE_PROPERTIES = 3;
    // Log output
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    // Local disk cache, where the special key value.registries records the list of registry centers, and the others are the list of notified service providers
    /**
     * 本地的 Properties 文件缓存，properties 是加载到内存的 Properties 对象，file 是磁盘上对应的文件，两者的数据是同步的。
     * 在 AbstractRegistry 初始化时，会根据 registryUrl 中的 file.cache 参数值决定是否开启文件缓存。
     * 如果开启文件缓存功能，就会立即将 file 文件中的 KV 缓存加载到 properties 字段中。
     * 当 properties 中的注册数据发生变化时，会写入本地的 file 文件进行同步。
     * properties 是一个 KV 结构，其中 Key 是当前节点作为 Consumer 的一个 URL，
     * Value 是对应的 Provider 列表，包含了所有 Category（例如，providers、routes、configurators 等） 下的 URL。
     * properties 中有一个特殊的 Key 值为 registies，对应的 Value 是注册中心列表，其他记录的都是 Provider 列表。
     */
    private final Properties properties = new Properties();
    // File cache timing writing
    /**
     *  这是一个单线程的线程池，在一个 Provider 的注册数据发生变化的时候，
     *  会将该 Provider 的全量数据同步到 properties 字段和缓存文件中，
     *  如果 syncSaveFile 配置为 false，就由该线程池异步完成文件写入。
     */
    private final ExecutorService registryCacheExecutor = Executors.newFixedThreadPool(1, new NamedThreadFactory("DubboSaveRegistryCache", true));
    // Is it synchronized to save the file
    /**
     * 是否同步保存文件的配置，对应的是 registryUrl 中的 save.file 参数
     */
    private boolean syncSaveFile;
    /**
     * 注册数据的版本号，每次写入 file 文件时，都是全覆盖写入，而不是修改文件，所以需要版本控制，防止旧数据覆盖新数据
     */
    private final AtomicLong lastCacheChanged = new AtomicLong();
    private final AtomicInteger savePropertiesRetryTimes = new AtomicInteger();
    /**
     * 这个比较简单，它是注册的 URL 集合。
     */
    private final Set<URL> registered = new ConcurrentHashSet<>();
    /**
     * 表示订阅 URL 的监听器集合，其中 Key 是被监听的 URL， Value 是相应的监听器集合。
     */
    private final ConcurrentMap<URL, Set<NotifyListener>> subscribed = new ConcurrentHashMap<>();
    /**
     * 该集合第一层 Key 是当前节点作为 Consumer 的一个 URL，表示的是该节点的某个
     * Consumer 角色（一个节点可以同时消费多个 Provider 节点）；
     * Value 是一个 Map 集合，该 Map 集合的 Key 是 Provider URL 的分类（Category），
     * 例如 providers、routes、configurators 等，Value 就是相应分类下的 URL 集合。
     */
    private final ConcurrentMap<URL, Map<String, List<URL>>> notified = new ConcurrentHashMap<>();
    /**
     * 该 URL 包含了创建该 Registry 对象的全部配置信息，是 AbstractRegistryFactory 修改后的产物
     */
    private URL registryUrl;
    // Local disk cache file
    /**
     * 本地缓存文件
     * 在 Windows 系统下通常是： C:\Users\{用户名}\.dubbo\dubbo-registry-{应用名}-{注册中心地址}.cache
     * - 可以通过在注册中心 URL 中配置 file 参数来自定义缓存文件位置
     * - 示例：
     * dubbo.registry.file=D:/dubbo/cache/dubbo-registry.cache
     *
     * 这个缓存文件的主要作用是：
     *
     * 1. 保存注册中心的数据
     * 2. 当注册中心出现故障时，可以使用本地缓存继续提供服务
     * 3. 服务重启时可以读取本地缓存，加快服务恢复速度
     */
    private File file;

    public AbstractRegistry(URL url) {
        setUrl(url);
        //file.cache
        if (url.getParameter(REGISTRY__LOCAL_FILE_CACHE_ENABLED, true)) {
            // Start file save timer
            syncSaveFile = url.getParameter(REGISTRY_FILESAVE_SYNC_KEY, false);
            String defaultFilename = System.getProperty("user.home") + "/.dubbo/dubbo-registry-" + url.getParameter(APPLICATION_KEY) + "-" + url.getAddress().replaceAll(":", "-") + ".cache";
            String filename = url.getParameter(FILE_KEY, defaultFilename);
            File file = null;
            if (ConfigUtils.isNotEmpty(filename)) {
                file = new File(filename);
                if (!file.exists() && file.getParentFile() != null && !file.getParentFile().exists()) {
                    if (!file.getParentFile().mkdirs()) {
                        throw new IllegalArgumentException("Invalid registry cache file " + file + ", cause: Failed to create directory " + file.getParentFile() + "!");
                    }
                }
            }
            this.file = file;
            // When starting the subscription center,
            // we need to read the local cache file for future Registry fault tolerance processing.
            //将上面写入的本地缓存文件，加载到 properties 对象中
            loadProperties();
            notify(url.getBackupUrls());
        }
    }

    /**
     * 这个方法的主要作用是：
     * 1.空列表处理 ：
     * - 当服务提供者列表为空时（即没有可用的服务提供者）
     * - 会创建一个使用 empty:// 协议的 URL
     * - 这个 empty URL 用来标识当前没有可用的服务提供者
     *
     * 2.作用意义 ：
     * - 区分"未获取到服务列表"和"服务列表为空"两种情况
     * - 帮助消费者正确处理服务不可用的情况
     * - 避免消费者在服务列表为空时继续使用旧的服务列表
     * @param url
     * @param urls
     * @return
     */
    protected static List<URL> filterEmpty(URL url, List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            List<URL> result = new ArrayList<>(1);
            result.add(url.setProtocol(EMPTY_PROTOCOL));
            return result;
        }
        return urls;
    }

    @Override
    public URL getUrl() {
        return registryUrl;
    }

    protected void setUrl(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("registry url == null");
        }
        this.registryUrl = url;
    }

    public Set<URL> getRegistered() {
        return Collections.unmodifiableSet(registered);
    }

    public Map<URL, Set<NotifyListener>> getSubscribed() {
        return Collections.unmodifiableMap(subscribed);
    }

    public Map<URL, Map<String, List<URL>>> getNotified() {
        return Collections.unmodifiableMap(notified);
    }

    public File getCacheFile() {
        return file;
    }

    public Properties getCacheProperties() {
        return properties;
    }

    public AtomicLong getLastCacheChanged() {
        return lastCacheChanged;
    }

    public void doSaveProperties(long version) {
        if (version < lastCacheChanged.get()) {
            return;
        }
        if (file == null) {
            return;
        }
        // Save
        try {
            File lockfile = new File(file.getAbsolutePath() + ".lock");
            if (!lockfile.exists()) {
                lockfile.createNewFile();
            }
            try (RandomAccessFile raf = new RandomAccessFile(lockfile, "rw");
                 FileChannel channel = raf.getChannel()) {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw new IOException("Can not lock the registry cache file " + file.getAbsolutePath() + ", ignore and retry later, maybe multi java process use the file, please config: dubbo.registry.file=xxx.properties");
                }
                // Save
                try {
                    if (!file.exists()) {
                        file.createNewFile();
                    }
                    try (FileOutputStream outputFile = new FileOutputStream(file)) {
                        properties.store(outputFile, "Dubbo Registry Cache");
                    }
                } finally {
                    lock.release();
                }
            }
        } catch (Throwable e) {
            savePropertiesRetryTimes.incrementAndGet();
            if (savePropertiesRetryTimes.get() >= MAX_RETRY_TIMES_SAVE_PROPERTIES) {
                logger.warn("Failed to save registry cache file after retrying " + MAX_RETRY_TIMES_SAVE_PROPERTIES + " times, cause: " + e.getMessage(), e);
                savePropertiesRetryTimes.set(0);
                return;
            }
            if (version < lastCacheChanged.get()) {
                savePropertiesRetryTimes.set(0);
                return;
            } else {
                registryCacheExecutor.execute(new SaveProperties(lastCacheChanged.incrementAndGet()));
            }
            logger.warn("Failed to save registry cache file, will retry, cause: " + e.getMessage(), e);
        }
    }

    /**
     * 通过文件流加载到properties中
     */
    private void loadProperties() {
        if (file != null && file.exists()) {
            InputStream in = null;
            try {
                in = new FileInputStream(file);
                properties.load(in);
                if (logger.isInfoEnabled()) {
                    logger.info("Load registry cache file " + file + ", data: " + properties);
                }
            } catch (Throwable e) {
                logger.warn("Failed to load registry cache file " + file, e);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            }
        }
    }

    public List<URL> getCacheUrls(URL url) {
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();
            if (StringUtils.isNotEmpty(key) && key.equals(url.getServiceKey())
                    && (Character.isLetter(key.charAt(0)) || key.charAt(0) == '_')
                    && StringUtils.isNotEmpty(value)) {
                String[] arr = value.trim().split(URL_SPLIT);
                List<URL> urls = new ArrayList<>();
                for (String u : arr) {
                    urls.add(URL.valueOf(u));
                }
                return urls;
            }
        }
        return null;
    }

    @Override
    public List<URL> lookup(URL url) {
        List<URL> result = new ArrayList<>();
        Map<String, List<URL>> notifiedUrls = getNotified().get(url);
        if (CollectionUtils.isNotEmptyMap(notifiedUrls)) {
            for (List<URL> urls : notifiedUrls.values()) {
                for (URL u : urls) {
                    if (!EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        } else {
            final AtomicReference<List<URL>> reference = new AtomicReference<>();
            NotifyListener listener = reference::set;
            subscribe(url, listener); // Subscribe logic guarantees the first notify to return
            List<URL> urls = reference.get();
            if (CollectionUtils.isNotEmpty(urls)) {
                for (URL u : urls) {
                    if (!EMPTY_PROTOCOL.equals(u.getProtocol())) {
                        result.add(u);
                    }
                }
            }
        }
        return result;
    }

    /**
     *  registry() 方法将当前节点要注册的 URL 缓存到 registered 集合，
     * 而 unregistry() 方法会从 registered 集合删除指定的 URL，例如当前节点下线的时候。
     * @param url  Registration information , is not allowed to be empty, e.g: dubbo://10.20.153.10/org.apache.dubbo.foo.BarService?version=1.0.0&application=kylin
     */
    @Override
    public void register(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("register url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Register: " + url);
        }
        registered.add(url);
    }

    @Override
    public void unregister(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("unregister url == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unregister: " + url);
        }
        registered.remove(url);
    }

    @Override
    public void subscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("subscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("subscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Subscribe: " + url);
        }
        Set<NotifyListener> listeners = subscribed.computeIfAbsent(url, n -> new ConcurrentHashSet<>());
        listeners.add(listener);
    }

    @Override
    public void unsubscribe(URL url, NotifyListener listener) {
        if (url == null) {
            throw new IllegalArgumentException("unsubscribe url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("unsubscribe listener == null");
        }
        if (logger.isInfoEnabled()) {
            logger.info("Unsubscribe: " + url);
        }
        Set<NotifyListener> listeners = subscribed.get(url);
        if (listeners != null) {
            listeners.remove(listener);
        }

        // do not forget remove notified
        notified.remove(url);
    }

    /**
     * 在 Provider 因为网络问题与注册中心断开连接之后，会进行重连，重新连接成功之后，
     * 会调用 recover() 方法将 registered 集合中的全部 URL 重新走一遍 register() 方法，恢复注册数据。
     *
     * 同样，recover() 方法也会将 subscribed 集合中的 URL 重新走一遍 subscribe() 方法，恢复订阅监听器。
     * @throws Exception
     */
    protected void recover() throws Exception {
        // register 重新走一遍 register() 方法，恢复注册数据
        Set<URL> recoverRegistered = new HashSet<>(getRegistered());
        if (!recoverRegistered.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover register url " + recoverRegistered);
            }
            for (URL url : recoverRegistered) {
                register(url);
            }
        }
        // subscribe 重新走一遍 subscribe() 方法，恢复订阅监听器
        Map<URL, Set<NotifyListener>> recoverSubscribed = new HashMap<>(getSubscribed());
        if (!recoverSubscribed.isEmpty()) {
            if (logger.isInfoEnabled()) {
                logger.info("Recover subscribe url " + recoverSubscribed.keySet());
            }
            for (Map.Entry<URL, Set<NotifyListener>> entry : recoverSubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    subscribe(url, listener);
                }
            }
        }
    }

    protected void notify(List<URL> urls) {
        if (CollectionUtils.isEmpty(urls)) {
            return;
        }
        //从已注册的订阅 URL 的监听器集合中取出 NotifyListener
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL url = entry.getKey();

            if (!UrlUtils.isMatch(url, urls.get(0))) {
                continue;
            }

            Set<NotifyListener> listeners = entry.getValue();
            if (listeners != null) {
                for (NotifyListener listener : listeners) {
                    try {
                        //设置一个空的服务端列表
                        notify(url, listener, filterEmpty(url, urls));
                    } catch (Throwable t) {
                        logger.error("Failed to notify registry event, urls: " + urls + ", cause: " + t.getMessage(), t);
                    }
                }
            }
        }
    }

    /**
     * Notify changes from the Provider side.
     * （修改缓存）
     * 当 Provider 端暴露的 URL 发生变化时，ZooKeeper 等服务发现组件会通知 Consumer 端的 Registry 组件，
     * Registry 组件会调用 notify() 方法，
     * 被通知的 Consumer 能匹配到所有 Provider 的 URL 列表并写入 properties 集合中。
     *
     * 注意入参，第一个URL参数表示的是Consumer，
     * 第二个NotifyListener是第一个参数对应的监听器，
     * 第三个参数是Provider端暴露的URL的全量数据
     *
     * @param url      consumer side url
     * @param listener listener
     * @param urls     provider latest urls
     */
    protected void notify(URL url, NotifyListener listener, List<URL> urls) {
        if (url == null) {
            throw new IllegalArgumentException("notify url == null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("notify listener == null");
        }
        if ((CollectionUtils.isEmpty(urls))
                && !ANY_VALUE.equals(url.getServiceInterface())) {
            logger.warn("Ignore empty notify urls for subscribe url " + url);
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Notify urls for subscribe url " + url + ", urls: " + urls);
        }
        // keep every provider's category.
        Map<String, List<URL>> result = new HashMap<>();
        for (URL u : urls) {
            // 需要Consumer URL与Provider URL匹配，具体匹配规则见方法注释
            if (UrlUtils.isMatch(url, u)) {
                // 根据Provider URL中的category参数进行分类
                String category = u.getParameter(CATEGORY_KEY, DEFAULT_CATEGORY);
                List<URL> categoryList = result.computeIfAbsent(category, k -> new ArrayList<>());
                categoryList.add(u);
            }
        }
        if (result.size() == 0) {
            return;
        }
        Map<String, List<URL>> categoryNotified = notified.computeIfAbsent(url, u -> new ConcurrentHashMap<>());
        for (Map.Entry<String, List<URL>> entry : result.entrySet()) {
            String category = entry.getKey();
            List<URL> categoryList = entry.getValue();
            categoryNotified.put(category, categoryList);// 更新notified
            listener.notify(categoryList);// 调用NotifyListener
            // We will update our cache file after each notification.
            // When our Registry has a subscribe failure due to network jitter, we can return at least the existing cache URL.
            // 当注册中心因为网络波动导致订阅失败时，可以至少返回已经缓存的服务端URL
            // 更新properties集合以及底层的文件缓存
            saveProperties(url);
        }
    }

    /**
     * 在 saveProperties() 方法中会取出 Consumer 订阅的各个分类的 URL 连接起来（中间以空格分隔），
     * 然后以 Consumer 的 ServiceKey 为键值写到 properties 中，同时 lastCacheChanged 版本号会自增。
     * 完成 properties 字段的更新之后，会根据 syncSaveFile 字段值来决定是在当前线程同步更新 file 文件，
     * 还是向 registryCacheExecutor 线程池提交任务，异步完成 file 文件的同步。
     *
     * 本地缓存文件的具体路径是：
     * /.dubbo/dubbo-registry-[当前应用名]-[当前Registry所在的IP地址].cache
     * @param url
     */
    private void saveProperties(URL url) {
        if (file == null) {
            return;
        }

        try {
            StringBuilder buf = new StringBuilder();
            Map<String, List<URL>> categoryNotified = notified.get(url);
            if (categoryNotified != null) {
                for (List<URL> us : categoryNotified.values()) {
                    for (URL u : us) {
                        if (buf.length() > 0) {
                            buf.append(URL_SEPARATOR);
                        }
                        buf.append(u.toFullString());
                    }
                }
            }
            properties.setProperty(url.getServiceKey(), buf.toString());
            //版本号+1
            long version = lastCacheChanged.incrementAndGet();
            //根据是否同步保存文件的标识 决定是否同步保存文件 默认是false
            if (syncSaveFile) {
                doSaveProperties(version);
            } else {
                //启动异步任务 保存文件
                registryCacheExecutor.execute(new SaveProperties(version));
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    public void destroy() {
        if (logger.isInfoEnabled()) {
            logger.info("Destroy registry:" + getUrl());
        }
        Set<URL> destroyRegistered = new HashSet<>(getRegistered());
        if (!destroyRegistered.isEmpty()) {
            for (URL url : new HashSet<>(destroyRegistered)) {
                if (url.getParameter(DYNAMIC_KEY, true)) {
                    try {
                        unregister(url);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unregister url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unregister url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
        Map<URL, Set<NotifyListener>> destroySubscribed = new HashMap<>(getSubscribed());
        if (!destroySubscribed.isEmpty()) {
            for (Map.Entry<URL, Set<NotifyListener>> entry : destroySubscribed.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    try {
                        unsubscribe(url, listener);
                        if (logger.isInfoEnabled()) {
                            logger.info("Destroy unsubscribe url " + url);
                        }
                    } catch (Throwable t) {
                        logger.warn("Failed to unsubscribe url " + url + " to registry " + getUrl() + " on destroy, cause: " + t.getMessage(), t);
                    }
                }
            }
        }
        AbstractRegistryFactory.removeDestroyedRegistry(this);
    }

    protected boolean acceptable(URL urlToRegistry) {
        String pattern = registryUrl.getParameter(ACCEPTS_KEY);
        if (StringUtils.isEmpty(pattern)) {
            return true;
        }

        return Arrays.stream(COMMA_SPLIT_PATTERN.split(pattern))
                .anyMatch(p -> p.equalsIgnoreCase(urlToRegistry.getProtocol()));
    }

    @Override
    public String toString() {
        return getUrl().toString();
    }

    private class SaveProperties implements Runnable {
        private long version;

        private SaveProperties(long version) {
            this.version = version;
        }

        @Override
        public void run() {
            doSaveProperties(version);
        }
    }

}
