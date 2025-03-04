/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.transaction.annotation;

import org.springframework.context.annotation.AdviceMode;
import org.springframework.context.annotation.AdviceModeImportSelector;
import org.springframework.context.annotation.AutoProxyRegistrar;
import org.springframework.util.ClassUtils;

/**
 * 事务管理配置选择器，根据{@link EnableTransactionManagement#mode()}的值动态选择事务管理配置类。
 * 核心作用：通过{@code @EnableTransactionManagement}注解的{@code mode}属性，决定使用基于代理（PROXY）或AspectJ的事务管理配置。
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 * @see EnableTransactionManagement
 * @see ProxyTransactionManagementConfiguration
 * @see TransactionManagementConfigUtils#TRANSACTION_ASPECT_CONFIGURATION_CLASS_NAME
 * @see TransactionManagementConfigUtils#JTA_TRANSACTION_ASPECT_CONFIGURATION_CLASS_NAME
 */
public class TransactionManagementConfigurationSelector extends AdviceModeImportSelector<EnableTransactionManagement> {

    /**
     * 根据{@code mode}属性选择导入的配置类：
     * <ul>
     *   <li>{@code PROXY}模式：导入{@code AutoProxyRegistrar}和{@code ProxyTransactionManagementConfiguration}，启用基于动态代理的事务管理。</li>
     *   <li>{@code ASPECTJ}模式：根据类路径是否存在JTA依赖，导入对应的AspectJ事务切面配置类。</li>
     * </ul>
     */
    @Override
    protected String[] selectImports(AdviceMode adviceMode) {
        return switch (adviceMode) {
            case PROXY -> new String[] {
				//AutoProxyRegistrar.class.getName()-> 注册 InfrastructureAdvisorAutoProxyCreator，这是一个自动代理创建器，
				//负责为目标 Bean 生成代理对象。它会扫描所有带有 @Transactional 注解的 Bean，并为其创建代理
                AutoProxyRegistrar.class.getName(), // 注册自动代理创建器，处理@Transactional注解 
                // 配置事务拦截器（BeanFactoryTransactionAttributeSourceAdvisor），将事务管理逻辑（如开启、提交、回滚事务）织入到代理对象中
                ProxyTransactionManagementConfiguration.class.getName() // 配置基于代理的事务拦截器 
            };
            case ASPECTJ -> new String[] { determineTransactionAspectClass() }; // 根据条件选择AspectJ事务切面配置
        };
    }

    /**
     * 确定AspectJ事务切面的具体配置类：
     * <ol>
     *   <li>若类路径中存在{@code jakarta.transaction.Transactional}（JTA API），则使用JTA事务切面配置（支持分布式事务）。</li>
     *   <li>否则使用标准的Spring事务切面配置（{@code TransactionAspectSupport}） 。</li>
     * </ol>
     */
    private String determineTransactionAspectClass() {
        return (ClassUtils.isPresent("jakarta.transaction.Transactional", getClass().getClassLoader()) ?
                // JTA事务切面配置类（支持Jakarta EE规范）
                TransactionManagementConfigUtils.JTA_TRANSACTION_ASPECT_CONFIGURATION_CLASS_NAME :
                // 标准Spring事务切面配置类
                TransactionManagementConfigUtils.TRANSACTION_ASPECT_CONFIGURATION_CLASS_NAME);
    }

}

