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
package org.apache.dubbo.registry.client;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.extension.SPI;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.metadata.MappingChangedEvent;
import org.apache.dubbo.metadata.MappingListener;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.metadata.WritableMetadataService;
import org.apache.dubbo.registry.NotifyListener;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.client.event.ServiceInstancesChangedEvent;
import org.apache.dubbo.registry.client.event.listener.ServiceInstancesChangedListener;
import org.apache.dubbo.registry.client.metadata.SubscribedURLsSynthesizer;
import org.apache.dubbo.registry.support.AbstractRegistryFactory;
import org.apache.dubbo.registry.support.FailbackRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Collections.emptySet;
import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Stream.of;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_CHAR_SEPARATOR;
import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.MAPPING_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROTOCOL_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PROVIDER_SIDE;
import static org.apache.dubbo.common.constants.CommonConstants.SIDE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.PROVIDED_BY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_CLUSTER_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.REGISTRY_TYPE_KEY;
import static org.apache.dubbo.common.constants.RegistryConstants.SERVICE_REGISTRY_TYPE;
import static org.apache.dubbo.common.constants.RegistryConstants.SUBSCRIBED_SERVICE_NAMES_KEY;
import static org.apache.dubbo.common.function.ThrowableAction.execute;
import static org.apache.dubbo.common.utils.CollectionUtils.isEmpty;
import static org.apache.dubbo.common.utils.StringUtils.isBlank;
import static org.apache.dubbo.registry.client.ServiceDiscoveryFactory.getExtension;
import static org.apache.dubbo.rpc.Constants.ID_KEY;

/**
 * Being different to the traditional registry, {@link ServiceDiscoveryRegistry} that is a new service-oriented
 * {@link Registry} based on {@link ServiceDiscovery}, it will not interact in the external registry directly,
 * but store the {@link URL urls} that Dubbo services exported and referenced into {@link WritableMetadataService}
 * when {@link #register(URL)} and {@link #subscribe(URL, NotifyListener)} methods are executed. After that the exported
 * {@link URL urls} can be get from {@link WritableMetadataService#getExportedURLs()} and its variant methods. In contrast,
 * {@link WritableMetadataService#getSubscribedURLs()} method offers the subscribed {@link URL URLs}.
 * <p>
 * Every {@link ServiceDiscoveryRegistry} object has its own {@link ServiceDiscovery} instance that was initialized
 * under {@link #ServiceDiscoveryRegistry(URL) the construction}. As the primary argument of constructor , the
 * {@link URL} of connection the registry decides what the kind of ServiceDiscovery is. Generally, each
 * protocol associates with a kind of {@link ServiceDiscovery}'s implementation if present, or the
 * {@link FileSystemServiceDiscovery} will be the default one. Obviously, it's also allowed to extend
 * {@link ServiceDiscovery} using {@link SPI the Dubbo SPI}.
 * In contrast, current {@link ServiceInstance service instance} will not be registered to the registry whether any
 * Dubbo service is exported or not.
 * <p>
 *
 * @see ServiceDiscovery
 * @see FailbackRegistry
 * @see WritableMetadataService
 * @since 2.7.5
 *
 * ServiceDiscoveryRegistry 是一个面向服务实例（ServiceInstance）的注册中心实现，
 * 其底层依赖前面两个课时介绍的 ServiceDiscovery、WritableMetadataService 等组件
 *
 * 对应工厂ServiceDiscoveryRegistryFactory（扩展名称是 service-discovery-registry）
 */
public class ServiceDiscoveryRegistry implements Registry {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 用于 ServiceInstance 的发布和订阅。
     */
    private final ServiceDiscovery serviceDiscovery;

    /**
     * 记录了当前订阅的服务名称。
     */
    private final Set<String> subscribedServices;

    /**
     * 用于 Service ID 与 Service Name 之间的转换。
     */
    private final ServiceNameMapping serviceNameMapping;

    /**
     * 用于发布和查询元数据。
     */
    private final WritableMetadataService writableMetadataService;

    /**
     * 记录了注册的 ServiceInstancesChangedListener 的唯一标识。
     */
    private final Set<String> registeredListeners = new LinkedHashSet<>();

    /* apps - listener */
    private final Map<String, ServiceInstancesChangedListener> serviceListeners = new HashMap<>();
    private final Map<String, String> serviceToAppsMapping = new HashMap<>();

    private URL registryURL;

    /**
     * A cache for all URLs of services that the subscribed services exported
     * The key is the service name
     * The value is a nested {@link Map} whose key is the revision and value is all URLs of services
     */
    private final Map<String, Map<String, List<URL>>> serviceRevisionExportedURLsCache = new LinkedHashMap<>();

    public ServiceDiscoveryRegistry(URL registryURL) {
        this.registryURL = registryURL;
        // 初始化ServiceDiscovery对象
        this.serviceDiscovery = createServiceDiscovery(registryURL);
        // 从registryURL中解析出subscribed-services参数，并按照逗号切分，得到subscribedServices集合
        this.subscribedServices = parseServices(registryURL.getParameter(SUBSCRIBED_SERVICE_NAMES_KEY));
        // 获取DefaultServiceNameMapping对象
        this.serviceNameMapping = ServiceNameMapping.getExtension(registryURL.getParameter(MAPPING_KEY));
        // 初始化WritableMetadataService对象
        this.writableMetadataService = WritableMetadataService.getDefaultExtension();
    }

    public ServiceDiscovery getServiceDiscovery() {
        return serviceDiscovery;
    }

    /**
     * Create the {@link ServiceDiscovery} from the registry {@link URL}
     *
     * @param registryURL the {@link URL} to connect the registry
     * @return non-null
     *
     * 在 createServiceDiscovery() 方法中，不仅会加载 ServiceDiscovery 的相应实现，
     * 还会在外层添加 EventPublishingServiceDiscovery 装饰器，在 register()、
     * initialize() 等方法前后触发相应的事件
     */
    protected ServiceDiscovery createServiceDiscovery(URL registryURL) {
        // 根据registryURL获取对应的ServiceDiscovery实现
        ServiceDiscovery originalServiceDiscovery = getServiceDiscovery(registryURL);
        // ServiceDiscovery外层添加一层EventPublishingServiceDiscovery修饰器，
        // EventPublishingServiceDiscovery会在register()、initialize()等方法前后触发相应的事件，
        // 例如，在register()方法的前后分别会触发ServiceInstancePreRegisteredEvent和ServiceInstanceRegisteredEvent
        ServiceDiscovery serviceDiscovery = enhanceEventPublishing(originalServiceDiscovery);
        execute(() -> {// 初始化ServiceDiscovery
            serviceDiscovery.initialize(registryURL.addParameter(INTERFACE_KEY, ServiceDiscovery.class.getName())
                    .removeParameter(REGISTRY_TYPE_KEY));
        });
        return serviceDiscovery;
    }

    /**
     * 将 ServiceInstance 的信息与元数据进行合并，得到订阅服务的完整 URL。
     * @return
     */
    private List<SubscribedURLsSynthesizer> initSubscribedURLsSynthesizers() {
        ExtensionLoader<SubscribedURLsSynthesizer> loader = ExtensionLoader.getExtensionLoader(SubscribedURLsSynthesizer.class);
        return Collections.unmodifiableList(new ArrayList<>(loader.getSupportedExtensionInstances()));
    }

    /**
     * Get the instance {@link ServiceDiscovery} from the registry {@link URL} using
     * {@link ServiceDiscoveryFactory} SPI
     *
     * @param registryURL the {@link URL} to connect the registry
     * @return
     */
    private ServiceDiscovery getServiceDiscovery(URL registryURL) {
        ServiceDiscoveryFactory factory = getExtension(registryURL);
        return factory.getServiceDiscovery(registryURL);
    }

    /**
     * Enhance the original {@link ServiceDiscovery} with event publishing feature
     *
     * @param original the original {@link ServiceDiscovery}
     * @return {@link EventPublishingServiceDiscovery} instance
     */
    private ServiceDiscovery enhanceEventPublishing(ServiceDiscovery original) {
        return new EventPublishingServiceDiscovery(original);
    }

    protected boolean shouldRegister(URL providerURL) {

        String side = providerURL.getParameter(SIDE_KEY);

        boolean should = PROVIDER_SIDE.equals(side); // Only register the Provider.

        if (!should) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("The URL[%s] should not be registered.", providerURL.toString()));
            }
        }

        return should;
    }

    protected boolean shouldSubscribe(URL subscribedURL) {
        return !shouldRegister(subscribedURL);
    }

    @Override
    public final void register(URL url) {
        // 检测URL中的side参数是否为provider
        if (!shouldRegister(url)) { // Should Not Register
            return;
        }
        doRegister(url);
    }

    public void doRegister(URL url) {
        String registryCluster = serviceDiscovery.getUrl().getParameter(ID_KEY);
        if (registryCluster != null && url.getParameter(REGISTRY_CLUSTER_KEY) == null) {
            url = url.addParameter(REGISTRY_CLUSTER_KEY, registryCluster);
        }
        // 将元数据发布到MetadataService
        if (writableMetadataService.exportURL(url)) {
            if (logger.isInfoEnabled()) {
                logger.info(format("The URL[%s] registered successfully.", url.toString()));
            }
        } else {
            if (logger.isWarnEnabled()) {
                logger.info(format("The URL[%s] has been registered.", url.toString()));
            }
        }
    }

    @Override
    public final void unregister(URL url) {
        if (!shouldRegister(url)) {
            return;
        }
        doUnregister(url);
    }

    public void doUnregister(URL url) {
        String registryCluster = serviceDiscovery.getUrl().getParameter(ID_KEY);
        if (registryCluster != null && url.getParameter(REGISTRY_CLUSTER_KEY) == null) {
            url = url.addParameter(REGISTRY_CLUSTER_KEY, registryCluster);
        }
        if (writableMetadataService.unexportURL(url)) {
            if (logger.isInfoEnabled()) {
                logger.info(format("The URL[%s] deregistered successfully.", url.toString()));
            }
        } else {
            if (logger.isWarnEnabled()) {
                logger.info(format("The URL[%s] has been deregistered.", url.toString()));
            }
        }
    }

    @Override
    public final void subscribe(URL url, NotifyListener listener) {
        if (!shouldSubscribe(url)) { // Should Not Subscribe
            return;
        }
        String registryCluster = serviceDiscovery.getUrl().getParameter(ID_KEY);
        if (registryCluster != null && url.getParameter(REGISTRY_CLUSTER_KEY) == null) {
            url = url.addParameter(REGISTRY_CLUSTER_KEY, registryCluster);
        }
        doSubscribe(url, listener);
    }

    /**
     * 在 ServiceDiscoveryRegistry 的 doSubscribe() 方法中，会执行如下完整的订阅流程：
     *
     * 调用 WriteMetadataService.subscribeURL() 方法在 subscribedServiceURLs 集合中记录当前订阅的 URL；
     *
     * 通过订阅的 URL 获取 Service Name；
     *
     * 根据 Service Name 获取 ServiceInstance 集合；
     *
     * 根据 ServiceInstance 调用相应的 MetadataService 服务，获取元数据，其中涉及历史数据的清理和缓存更新等操作；
     *
     * 将 ServiceInstance 信息以及对应的元数据信息进行合并，得到完整的 URL；
     *
     * 触发 NotifyListener 监听器；
     *
     * 添加 ServiceInstancesChangedListener 监听器。
     * @param url
     * @param listener
     */
    public void doSubscribe(URL url, NotifyListener listener) {
        // 记录该订阅的URL
        writableMetadataService.subscribeURL(url);

        // 获取订阅的Service Name
        Set<String> serviceNames = getServices(url, listener);

        if (CollectionUtils.isEmpty(serviceNames)) {
            logger.warn("Should has at least one way to know which services this interface belongs to, subscription url: " + url);
            return;
        }

        // 执行后续的订阅操作
        subscribeURLs(url, listener, serviceNames);
    }

    @Override
    public final void unsubscribe(URL url, NotifyListener listener) {
        if (!shouldSubscribe(url)) { // Should Not Subscribe
            return;
        }
        String registryCluster = serviceDiscovery.getUrl().getParameter(ID_KEY);
        if (registryCluster != null && url.getParameter(REGISTRY_CLUSTER_KEY) == null) {
            url = url.addParameter(REGISTRY_CLUSTER_KEY, registryCluster);
        }
        doUnsubscribe(url, listener);
    }

    public void doUnsubscribe(URL url, NotifyListener listener) {
        writableMetadataService.unsubscribeURL(url);
        String protocolServiceKey = url.getServiceKey() + GROUP_CHAR_SEPARATOR + url.getParameter(PROTOCOL_KEY, DUBBO);
        String serviceNamesKey = serviceToAppsMapping.remove(protocolServiceKey);
        if (serviceNamesKey == null) {
            return;
        }
        ServiceInstancesChangedListener instancesChangedListener = serviceListeners.get(serviceNamesKey);
        instancesChangedListener.removeListener(protocolServiceKey);
    }

    @Override
    public List<URL> lookup(URL url) {
        throw new UnsupportedOperationException("");
    }

    @Override
    public URL getUrl() {
        return registryURL;
    }

    @Override
    public boolean isAvailable() {
        return !serviceDiscovery.getServices().isEmpty();
    }

    @Override
    public void destroy() {
        AbstractRegistryFactory.removeDestroyedRegistry(this);
        execute(() -> {
            // stop ServiceDiscovery
            serviceDiscovery.destroy();
        });
    }

    /**
     * 在 subscribeURLs(url, listener, serviceName, serviceInstances) 这个重载中，
     * 主要是根据前面获取的 ServiceInstance 实例集合，构造对应的、完整的 subscribedURL 集合，
     * 并触发传入的 NotifyListener 监听器
     *
     * 这个和介绍的版本不一样 修改了实现方式
     * @param url
     * @param listener
     * @param serviceNames
     */
    protected void subscribeURLs(URL url, NotifyListener listener, Set<String> serviceNames) {
        String serviceNamesKey = serviceNames.toString();
        String protocolServiceKey = url.getServiceKey() + GROUP_CHAR_SEPARATOR + url.getParameter(PROTOCOL_KEY, DUBBO);
        serviceToAppsMapping.put(protocolServiceKey, serviceNamesKey);

        // register ServiceInstancesChangedListener
        ServiceInstancesChangedListener serviceListener = serviceListeners.computeIfAbsent(serviceNamesKey,
                k -> new ServiceInstancesChangedListener(serviceNames, serviceDiscovery));
        serviceListener.setUrl(url);
        listener.addServiceListener(serviceListener);

        serviceListener.addListener(protocolServiceKey, listener);
        //注册监听
        registerServiceInstancesChangedListener(url, serviceListener);

        // FIXME: This will cause redundant duplicate notifications
        serviceNames.forEach(serviceName -> {
            List<ServiceInstance> serviceInstances = serviceDiscovery.getInstances(serviceName);
            if (CollectionUtils.isNotEmpty(serviceInstances)) {
                //发布服务实例变更事件
                serviceListener.onEvent(new ServiceInstancesChangedEvent(serviceName, serviceInstances));
            } else {
                logger.info("getInstances by serviceName=" + serviceName + " is empty, waiting for serviceListener callback. url=" + url);
            }
        });

        // 触发NotifyListener监听器
        listener.notify(serviceListener.getUrls(protocolServiceKey));

    }

    /**
     * Register the {@link ServiceInstancesChangedListener} If absent
     *
     * @param url      {@link URL}
     * @param listener the {@link ServiceInstancesChangedListener}
     */
    private void registerServiceInstancesChangedListener(URL url, ServiceInstancesChangedListener listener) {
        String listenerId = createListenerId(url, listener);
        if (registeredListeners.add(listenerId)) {
            serviceDiscovery.addServiceInstancesChangedListener(listener);
        }
    }

    private String createListenerId(URL url, ServiceInstancesChangedListener listener) {
        return listener.getServiceNames() + ":" + url.toString(VERSION_KEY, GROUP_KEY, PROTOCOL_KEY);
    }

    /**
     * 1.developer explicitly specifies the application name this interface belongs to
     * 2.check Interface-App mapping
     * 3.use the services specified in registry url.
     *
     * getServices() 方法的具体实现：
     * 它会首先根据 subscribeURL 的 provided-by 参数值获取订阅的 Service Name 集合，
     * 如果获取失败，则根据 Service ID 获取对应的 Service Name 集合；
     * 如果此时依旧获取失败，则尝试从 registryURL 中的 subscribed-services 参数值获取 Service Name 集合。
     * @param subscribedURL
     * @return
     */
    protected Set<String> getServices(URL subscribedURL, final NotifyListener listener) {
        Set<String> subscribedServices = new TreeSet<>();

        // 首先尝试从subscribeURL中获取provided-by参数值，其中封装了全部Service Name
        String serviceNames = subscribedURL.getParameter(PROVIDED_BY);
        if (StringUtils.isNotEmpty(serviceNames)) {
            logger.info(subscribedURL.getServiceInterface() + " mapping to " + serviceNames + " instructed by provided-by set by user.");
            // 解析provided-by参数值，得到全部的Service Name集合
            subscribedServices.addAll(parseServices(serviceNames));
        }

        if (isEmpty(subscribedServices)) {
            // 如果没有指定provided-by参数，则尝试通过subscribedURL构造Service ID，
            // 然后通过ServiceNameMapping的get()方法查找Service Name
            Set<String> mappedServices = findMappedServices(subscribedURL, new DefaultMappingListener(subscribedURL, subscribedServices, listener));
            logger.info(subscribedURL.getServiceInterface() + " mapping to " + serviceNames + " instructed by remote metadata center.");
            subscribedServices.addAll(mappedServices);
            if (isEmpty(subscribedServices)) {
                logger.info(subscribedURL.getServiceInterface() + " mapping to " + serviceNames + " by default.");
                // 如果subscribedServices依旧为空，则返回registryURL中的subscribed-services参数值
                subscribedServices.addAll(getSubscribedServices());
            }
        }
        return subscribedServices;
    }

    public static Set<String> parseServices(String literalServices) {
        return isBlank(literalServices) ? emptySet() :
                unmodifiableSet(of(literalServices.split(","))
                        .map(String::trim)
                        .filter(StringUtils::isNotEmpty)
                        .collect(toSet()));
    }

    /**
     * Get the subscribed service names
     *
     * @return non-null
     */
    public Set<String> getSubscribedServices() {
        return subscribedServices;
    }

    /**
     * Get the mapped services name by the specified {@link URL}
     *
     * @param subscribedURL
     * @return
     */
    protected Set<String> findMappedServices(URL subscribedURL, MappingListener listener) {
        return serviceNameMapping.getAndListen(subscribedURL, listener);
    }

    /**
     * Create an instance of {@link ServiceDiscoveryRegistry} if supported
     *
     * @param registryURL the {@link URL url} of registry
     * @return <code>null</code> if not supported
     */
    public static ServiceDiscoveryRegistry create(URL registryURL) {
        return supports(registryURL) ? new ServiceDiscoveryRegistry(registryURL) : null;
    }

    /**
     * Supports or not ?
     *
     * @param registryURL the {@link URL url} of registry
     * @return if supported, return <code>true</code>, or <code>false</code>
     */
    public static boolean supports(URL registryURL) {
        return SERVICE_REGISTRY_TYPE.equalsIgnoreCase(registryURL.getParameter(REGISTRY_TYPE_KEY));
    }

    private static List<URL> filterSubscribedURLs(URL subscribedURL, List<URL> exportedURLs) {
        return exportedURLs.stream()
                .filter(url -> isSameServiceInterface(subscribedURL, url))
                .filter(url -> isSameParameter(subscribedURL, url, VERSION_KEY))
                .filter(url -> isSameParameter(subscribedURL, url, GROUP_KEY))
                .filter(url -> isCompatibleProtocol(subscribedURL, url))
                .collect(Collectors.toList());
    }

    private static boolean isSameServiceInterface(URL one, URL another) {
        return Objects.equals(one.getServiceInterface(), another.getServiceInterface());
    }

    private static boolean isSameParameter(URL one, URL another, String key) {
        return Objects.equals(one.getParameter(key), another.getParameter(key));
    }

    private static boolean isCompatibleProtocol(URL one, URL another) {
        String protocol = one.getParameter(PROTOCOL_KEY);
        return isCompatibleProtocol(protocol, another);
    }

    private static boolean isCompatibleProtocol(String protocol, URL targetURL) {
        return protocol == null || Objects.equals(protocol, targetURL.getParameter(PROTOCOL_KEY))
                || Objects.equals(protocol, targetURL.getProtocol());
    }

    private class DefaultMappingListener implements MappingListener {
        private URL url;
        private Set<String> oldApps;
        private NotifyListener listener;

        public DefaultMappingListener(URL subscribedURL, Set<String> serviceNames, NotifyListener listener) {
            this.url = subscribedURL;
            this.oldApps = serviceNames;
            this.listener = listener;
        }

        @Override
        public void onEvent(MappingChangedEvent event) {
            Set<String> newApps = event.getApps();
            Set<String> tempOldApps = oldApps;
            oldApps = newApps;

            if (CollectionUtils.isEmpty(newApps)) {
                return;
            }

            if (CollectionUtils.isEmpty(tempOldApps) && newApps.size() > 0) {
                subscribeURLs(url, listener, newApps);
                return;
            }

            for (String newAppName : newApps) {
                if (!tempOldApps.contains(newAppName)) {
                    subscribeURLs(url, listener, newApps);
                    return;
                }
            }
        }
    }
}
