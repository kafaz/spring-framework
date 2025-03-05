/*
 * Copyright 2002-2024 the original author or authors.
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

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Role;
import org.springframework.transaction.config.TransactionManagementConfigUtils;
import org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * 基于代理的注解驱动事务管理配置类，注册Spring事务管理所需的基础设施Bean。
 * 该类由{@code @EnableTransactionManagement}通过{@link TransactionManagementConfigurationSelector}导入。
 *
 * @author Chris Beams
 * @author Sebastien Deleuze
 * @since 3.1
 * @see EnableTransactionManagement
 * @see TransactionManagementConfigurationSelector
 */
@Configuration(proxyBeanMethods = false) // 禁用代理Bean方法以提高启动性能，适用于无跨Bean依赖的场景
@Role(BeanDefinition.ROLE_INFRASTRUCTURE) // 标记为Spring框架基础设施组件，避免被应用级Bean覆盖
@ImportRuntimeHints(TransactionRuntimeHints.class) // 注册事务管理相关的AOT编译运行时元数据（如反射配置）
public class ProxyTransactionManagementConfiguration extends AbstractTransactionManagementConfiguration {

    /**
	 * {@link BeanFactoryTransactionAttributeSourceAdvisor} 是 Spring 声明式事务的核心桥梁 ，通过整合事务属性解析与拦截逻辑，实现对 @Transactional 方法的透明事务管理
     * 创建事务通知器，将事务属性解析与拦截逻辑绑定到AOP代理链
     * 该通知器会根据@Transactional注解的定义决定是否对目标方法进行事务增强
     */
    @Bean(name = TransactionManagementConfigUtils.TRANSACTION_ADVISOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public BeanFactoryTransactionAttributeSourceAdvisor transactionAdvisor(
            TransactionAttributeSource transactionAttributeSource, // 解析@Transactional注解的元数据解析器
            TransactionInterceptor transactionInterceptor) { // 实际执行事务提交/回滚的拦截器

        BeanFactoryTransactionAttributeSourceAdvisor advisor = new BeanFactoryTransactionAttributeSourceAdvisor();
        advisor.setTransactionAttributeSource(transactionAttributeSource); // 关联事务属性解析器
        advisor.setAdvice(transactionInterceptor); // 将事务拦截器绑定到通知器

        if (this.enableTx != null) {
            // 从@EnableTransactionManagement注解中提取order属性值，控制事务增强的优先级
            advisor.setOrder(this.enableTx.<Integer>getNumber("order"));
        }
        return advisor;
    }

    /**
     * 创建事务拦截器，实现事务的开启、提交、回滚等核心逻辑
     * 该拦截器会被织入到所有匹配@Transactional注解的方法调用链中
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public TransactionInterceptor transactionInterceptor(TransactionAttributeSource transactionAttributeSource) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionAttributeSource(transactionAttributeSource); // 设置事务元数据解析器

        if (this.txManager != null) {
            // 当用户显式配置PlatformTransactionManager时，将其绑定到拦截器（否则按类型自动注入）
            interceptor.setTransactionManager(this.txManager);
        }
        return interceptor;
    }

}

