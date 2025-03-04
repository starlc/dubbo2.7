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
package org.apache.dubbo.rpc.cluster.router.tag;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.configcenter.ConfigChangeType;
import org.apache.dubbo.common.config.configcenter.ConfigChangedEvent;
import org.apache.dubbo.common.config.configcenter.ConfigurationListener;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.AbstractRouter;
import org.apache.dubbo.rpc.cluster.router.tag.model.TagRouterRule;
import org.apache.dubbo.rpc.cluster.router.tag.model.TagRuleParser;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.TAG_KEY;
import static org.apache.dubbo.rpc.Constants.FORCE_USE_TAG;

/**
 * TagRouter, "application.tag-router"
 * 基于 Tag 的测试环境隔离方案
 * 通过 TagRouter，我们可以将某一个或多个 Provider 划分到同一分组，
 * 约束流量只在指定分组中流转，这样就可以轻松达到流量隔离的目的，从而支持灰度发布等场景。
 *
 * 目前，Dubbo 提供了动态和静态两种方式给 Provider 打标签，
 * 其中动态方式就是通过服务治理平台动态下发标签，
 * 静态方式就是在 XML 等静态配置中打标签。
 * Consumer 端可以在 RpcContext 的 attachment 中添加 request.tag 附加属性，
 * 注意保存在 attachment 中的值将会在一次完整的远程调用中持续传递，
 * 我们只需要在起始调用时进行设置，就可以达到标签的持续传递。
 *
 * 实现：
 * 在 TagRouter 中持有一个 TagRouterRule 对象的引用，在 TagRouterRule 中维护了一个 Tag 集合，
 * 而在每个 Tag 对象中又都维护了一个 Tag 的名称，以及 Tag 绑定的网络地址集合，
 *
 * ConfigurationListener 用于监听配置的变化，其中就包括 TagRouterRule 配置的变更
 */
public class TagRouter extends AbstractRouter implements ConfigurationListener {
    public static final String NAME = "TAG_ROUTER";
    private static final int TAG_ROUTER_DEFAULT_PRIORITY = 100;
    private static final Logger logger = LoggerFactory.getLogger(TagRouter.class);
    private static final String RULE_SUFFIX = ".tag-router";

    /**
     * 在 TagRouterRule 中维护了一个 Tag 集合，而在每个 Tag 对象中又都维护了一个 Tag 的名称，以及 Tag 绑定的网络地址集合
     */
    private TagRouterRule tagRouterRule;
    private String application;

    public TagRouter(URL url) {
        super(url);
        this.priority = TAG_ROUTER_DEFAULT_PRIORITY;
    }

    @Override
    public synchronized void process(ConfigChangedEvent event) {
        if (logger.isDebugEnabled()) {
            logger.debug("Notification of tag rule, change type is: " + event.getChangeType() + ", raw rule is:\n " +
                    event.getContent());
        }

        try {
            // DELETED事件会直接清空tagRouterRule
            if (event.getChangeType().equals(ConfigChangeType.DELETED)) {
                this.tagRouterRule = null;
            } else {
                // 其他事件会解析最新的路由规则，并记录到tagRouterRule字段中
                this.tagRouterRule = TagRuleParser.parse(event.getContent());
            }
        } catch (Exception e) {
            logger.error("Failed to parse the raw tag router rule and it will not take effect, please check if the " +
                    "rule matches with the template, the raw rule is:\n ", e);
        }
    }

    @Override
    public URL getUrl() {
        return url;
    }


    /**
     * tagRouter 是如何使用 TagRouterRule 路由逻辑进行 Invoker 过滤的，大致步骤如下。
     * 1如果 invokers 为空，直接返回空集合。
     * 2检查关联的 tagRouterRule 对象是否可用，如果不可用，则会直接调用 filterUsingStaticTag() 方法进行过滤，
     * 并返回过滤结果。在 filterUsingStaticTag() 方法中，会比较请求携带的 tag 值与 Provider URL 中的 tag 参数值。
     * 3获取此次调用的 tag 信息，这里会尝试从 Invocation 以及 URL 的参数中获取。
     * 4如果此次请求指定了 tag 信息，则首先会获取 tag 关联的 address 集合。
     *      a如果 address 集合不为空，则根据该 address 集合中的地址，匹配出符合条件的 Invoker 集合。如果存在符合条件的 Invoker，则直接将过滤得到的 Invoker 集合返回；如果不存在，就会根据 force 配置决定是否返回空 Invoker 集合。
     *      b如果 address 集合为空，则会将请求携带的 tag 值与 Provider URL 中的 tag 参数值进行比较，匹配出符合条件的 Invoker 集合。如果存在符合条件的 Invoker，则直接将过滤得到的 Invoker 集合返回；如果不存在，就会根据 force 配置决定是否返回空 Invoker 集合。
     *      c如果 force 配置为 false，且符合条件的 Invoker 集合为空，则返回所有不包含任何 tag 的 Provider 列表。
     * 5如果此次请求未携带 tag 信息，则会先获取 TagRouterRule 规则中全部 tag 关联的 address 集合。如果 address 集合不为空，则过滤出不在 address 集合中的 Invoker 并添加到结果集合中，最后，将 Provider URL 中的 tag 值与 TagRouterRule 中的 tag 名称进行比较，得到最终的 Invoker 集合。
     * @param invokers   invoker list
     * @param url        refer url
     * @param invocation invocation
     * @return
     * @param <T>
     * @throws RpcException
     */
    @Override
    public <T> List<Invoker<T>> route(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
        // 如果invokers为空，直接返回空集合(略)
        if (CollectionUtils.isEmpty(invokers)) {
            return invokers;
        }

        // since the rule can be changed by config center, we should copy one to use.
        // 检查关联的tagRouterRule对象是否可用，如果不可用，则会直接调用filterUsingStaticTag() 方法进行过滤
        final TagRouterRule tagRouterRuleCopy = tagRouterRule;
        if (tagRouterRuleCopy == null || !tagRouterRuleCopy.isValid() || !tagRouterRuleCopy.isEnabled()) {
            return filterUsingStaticTag(invokers, url, invocation);
        }

        List<Invoker<T>> result = invokers;
        // 获取此次调用的tag信息，尝试从Invocation以及URL中获取
        String tag = StringUtils.isEmpty(invocation.getAttachment(TAG_KEY)) ? url.getParameter(TAG_KEY) :
                invocation.getAttachment(TAG_KEY);

        // if we are requesting for a Provider with a specific tag
        if (StringUtils.isNotEmpty(tag)) {// 此次请求一个特殊的tag
            // 获取tag关联的address集合
            List<String> addresses = tagRouterRuleCopy.getTagnameToAddresses().get(tag);
            // filter by dynamic tag group first
            if (CollectionUtils.isNotEmpty(addresses)) {
                // 根据上面的address集合匹配符合条件的Invoker
                result = filterInvoker(invokers, invoker -> addressMatches(invoker.getUrl(), addresses));
                // if result is not null OR it's null but force=true, return result directly
                // 如果存在符合条件的Invoker，则直接将过滤得到的Invoker集合返回
                // 如果不存在符合条件的Invoker，根据force配置决定是否返回空Invoker集合
                if (CollectionUtils.isNotEmpty(result) || tagRouterRuleCopy.isForce()) {
                    return result;
                }
            } else {
                // dynamic tag group doesn't have any item about the requested app OR it's null after filtered by
                // dynamic tag group but force=false. check static tag
                // 如果 address 集合为空，则会将请求携带的 tag 与 Provider URL 中的 tag 参数值进行比较，匹配出符合条件的 Invoker 集合。
                result = filterInvoker(invokers, invoker -> tag.equals(invoker.getUrl().getParameter(TAG_KEY)));
            }
            // If there's no tagged providers that can match the current tagged request. force.tag is set by default
            // to false, which means it will invoke any providers without a tag unless it's explicitly disallowed.
            if (CollectionUtils.isNotEmpty(result) || isForceUseTag(invocation)) {
                return result;// 存在符合条件的Invoker或是force配置为true
            }
            // FAILOVER: return all Providers without any tags.
            else {// 如果 force 配置为 false，且符合条件的 Invoker 集合为空，则返回所有不包含任何 tag 的 Provider 列表。
                List<Invoker<T>> tmp = filterInvoker(invokers, invoker -> addressNotMatches(invoker.getUrl(),
                        tagRouterRuleCopy.getAddresses()));
                return filterInvoker(tmp, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
            }
        } else {
            // List<String> addresses = tagRouterRule.filter(providerApp);
            // return all addresses in dynamic tag group.
            // 如果此次请求未携带 tag 信息，则会先获取 TagRouterRule 规则中全部 tag 关联的 address 集合。
            List<String> addresses = tagRouterRuleCopy.getAddresses();
            if (CollectionUtils.isNotEmpty(addresses)) {
                // 如果 address 集合不为空，则过滤出不在 address 集合中的 Invoker 并添加到结果集合中。
                result = filterInvoker(invokers, invoker -> addressNotMatches(invoker.getUrl(), addresses));
                // 1. all addresses are in dynamic tag group, return empty list.
                if (CollectionUtils.isEmpty(result)) {
                    return result;
                }
                // 2. if there are some addresses that are not in any dynamic tag group, continue to filter using the
                // static tag group.
            }
            // 如果不存在符合条件的 Invoker 或是 address 集合为空，则会将请求携带的 tag 与 Provider URL 中的 tag 参数值进行比较，得到最终的 Invoker 集合。
            return filterInvoker(result, invoker -> {
                String localTag = invoker.getUrl().getParameter(TAG_KEY);
                return StringUtils.isEmpty(localTag) || !tagRouterRuleCopy.getTagNames().contains(localTag);
            });
        }
    }

    /**
     * If there's no dynamic tag rule being set, use static tag in URL.
     * <p>
     * A typical scenario is a Consumer using version 2.7.x calls Providers using version 2.6.x or lower,
     * the Consumer should always respect the tag in provider URL regardless of whether a dynamic tag rule has been set to it or not.
     * <p>
     * TODO, to guarantee consistent behavior of interoperability between 2.6- and 2.7+, this method should has the same logic with the TagRouter in 2.6.x.
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    private <T> List<Invoker<T>> filterUsingStaticTag(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        List<Invoker<T>> result;
        // Dynamic param
        String tag = StringUtils.isEmpty(invocation.getAttachment(TAG_KEY)) ? url.getParameter(TAG_KEY) :
                invocation.getAttachment(TAG_KEY);
        // Tag request
        if (!StringUtils.isEmpty(tag)) {
            result = filterInvoker(invokers, invoker -> tag.equals(invoker.getUrl().getParameter(TAG_KEY)));
            if (CollectionUtils.isEmpty(result) && !isForceUseTag(invocation)) {
                result = filterInvoker(invokers, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
            }
        } else {
            result = filterInvoker(invokers, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
        }
        return result;
    }

    @Override
    public boolean isRuntime() {
        return tagRouterRule != null && tagRouterRule.isRuntime();
    }

    @Override
    public boolean isForce() {
        // FIXME
        return tagRouterRule != null && tagRouterRule.isForce();
    }

    private boolean isForceUseTag(Invocation invocation) {
        return Boolean.parseBoolean(invocation.getAttachment(FORCE_USE_TAG, url.getParameter(FORCE_USE_TAG, "false")));
    }

    private <T> List<Invoker<T>> filterInvoker(List<Invoker<T>> invokers, Predicate<Invoker<T>> predicate) {
        if (invokers.stream().allMatch(predicate)) {
            return invokers;
        }

        return invokers.stream()
                .filter(predicate)
                .collect(Collectors.toList());
    }

    private boolean addressMatches(URL url, List<String> addresses) {
        return addresses != null && checkAddressMatch(addresses, url.getHost(), url.getPort());
    }

    private boolean addressNotMatches(URL url, List<String> addresses) {
        return addresses == null || !checkAddressMatch(addresses, url.getHost(), url.getPort());
    }

    private boolean checkAddressMatch(List<String> addresses, String host, int port) {
        for (String address : addresses) {
            try {
                if (NetUtils.matchIpExpression(address, host, port)) {
                    return true;
                }
                if ((ANYHOST_VALUE + ":" + port).equals(address)) {
                    return true;
                }
            } catch (Exception e) {
                logger.error("The format of ip address is invalid in tag route. Address :" + address, e);
            }
        }
        return false;
    }

    public void setApplication(String app) {
        this.application = app;
    }

    @Override
    public <T> void notify(List<Invoker<T>> invokers) {
        if (CollectionUtils.isEmpty(invokers)) {
            return;
        }

        Invoker<T> invoker = invokers.get(0);
        URL url = invoker.getUrl();
        String providerApplication = url.getParameter(CommonConstants.REMOTE_APPLICATION_KEY);

        if (StringUtils.isEmpty(providerApplication)) {
            logger.error("TagRouter must getConfig from or subscribe to a specific application, but the application " +
                    "in this TagRouter is not specified.");
            return;
        }

        synchronized (this) {
            if (!providerApplication.equals(application)) {
                if (!StringUtils.isEmpty(application)) {
                    ruleRepository.removeListener(application + RULE_SUFFIX, this);
                }
                String key = providerApplication + RULE_SUFFIX;
                ruleRepository.addListener(key, this);
                application = providerApplication;
                String rawRule = ruleRepository.getRule(key, DynamicConfiguration.DEFAULT_GROUP);
                if (StringUtils.isNotEmpty(rawRule)) {
                    this.process(new ConfigChangedEvent(key, DynamicConfiguration.DEFAULT_GROUP, rawRule));
                }
            }
        }
    }

}
