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
package org.apache.dubbo.registry.client.migration;

import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.cluster.support.migration.MigrationRule;
import org.apache.dubbo.rpc.cluster.support.migration.MigrationStep;

/**
 * 迁徙规则Handler
 * MigrationRuleHandler是 Dubbo 中负责处理服务迁移规则的处理器，主要用于实现服务注册中心的迁移。让我解释其核心功能：
 *
 * 1. 主要职责 ：
 * - 处理服务迁移规则
 * - 控制服务调用方式的切换
 * - 管理迁移过程中的状态变更
 * 2.迁移步骤 （MigrationStep）
 *
 * 3. 迁移场景 ：
 * - 从接口级服务发现迁移到应用级服务发现
 * - 多注册中心之间的迁移
 * - 服务调用方式的切换
 * 4. 核心方法 doMigrate ：
 * - 解析迁移规则
 * - 检查是否需要执行迁移
 * - 根据规则执行相应的迁移策略
 * - 处理多注册中心的特殊情况
 * 5. 使用场景 ：
 * - 服务治理架构升级
 * - 注册中心迁移
 * - 服务发现模式切换
 * 这个处理器在 Dubbo 的服务治理中扮演重要角色，使得服务架构的演进能够平滑进行，不影响现有业务。
 * @param <T>
 */
public class MigrationRuleHandler<T> {
    private static final Logger logger = LoggerFactory.getLogger(MigrationRuleHandler.class);

    private MigrationInvoker<T> migrationInvoker;

    public MigrationRuleHandler(MigrationInvoker<T> invoker) {
        this.migrationInvoker = invoker;
    }

    private MigrationStep currentStep;

    public void doMigrate(String rawRule) {
        MigrationRule rule = MigrationRule.parse(rawRule);

        if (null != currentStep && currentStep.equals(rule.getStep())) {
            if (logger.isInfoEnabled()) {
                logger.info("Migration step is not change. rule.getStep is " + currentStep.name());
            }
            return;
        } else {
            currentStep = rule.getStep();
        }

        migrationInvoker.setMigrationRule(rule);

        if (migrationInvoker.isMigrationMultiRegistry()) {
            if (migrationInvoker.isServiceInvoker()) {
                migrationInvoker.refreshServiceDiscoveryInvoker();
            } else {
                migrationInvoker.refreshInterfaceInvoker();
            }
        } else {
            switch (rule.getStep()) {
                case APPLICATION_FIRST:// 应用级服务发现优先
                    migrationInvoker.migrateToServiceDiscoveryInvoker(false);
                    break;
                case FORCE_APPLICATION:// 强制使用应用级服务发现
                    migrationInvoker.migrateToServiceDiscoveryInvoker(true);
                    break;
                case FORCE_INTERFACE:// 强制使用接口级服务发现
                default://默认 接口级别调用
                    migrationInvoker.fallbackToInterfaceInvoker();
            }
        }
    }
}
