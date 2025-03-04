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
@Configuration(proxyBeanMethods = false) // 禁用代理Bean方法以提高性能 
@Role(BeanDefinition.ROLE_INFRASTRUCTURE) // 标记为框架级基础设施Bean 
@ImportRuntimeHints(TransactionRuntimeHints.class) // 导入AOT编译所需的运行时提示 
public class ProxyTransactionManagementConfiguration extends AbstractTransactionManagementConfiguration {

    /**
     * 创建事务通知器，将事务属性源与拦截器绑定。
     * 该通知器负责决定哪些方法需要被事务拦截，并定义事务行为。
     *
     * @param transactionAttributeSource 事务属性解析器（解析{@code @Transactional}注解）
     * @param transactionInterceptor 事务拦截器（执行事务逻辑）
     * @return 事务通知器实例
     */
    @Bean(name = TransactionManagementConfigUtils.TRANSACTION_ADVISOR_BEAN_NAME)
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public BeanFactoryTransactionAttributeSourceAdvisor transactionAdvisor(
            TransactionAttributeSource transactionAttributeSource, 
            TransactionInterceptor transactionInterceptor) {

        BeanFactoryTransactionAttributeSourceAdvisor advisor = new BeanFactoryTransactionAttributeSourceAdvisor();
        advisor.setTransactionAttributeSource(transactionAttributeSource); // 绑定事务属性源
        advisor.setAdvice(transactionInterceptor); // 绑定事务拦截器
        if (this.enableTx != null) {
            // 从{@code @EnableTransactionManagement}注解中读取order属性值
            advisor.setOrder(this.enableTx.<Integer>getNumber("order"));
        }
        return advisor;
    }

    /**
     * 创建事务拦截器，实现事务的提交、回滚等核心逻辑。
     * 拦截器通过AOP机制织入到标注{@code @Transactional}的方法调用链中 。
     *
     * @param transactionAttributeSource 事务属性解析器，用于解析方法上的事务定义
     * @return 事务拦截器实例
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public TransactionInterceptor transactionInterceptor(TransactionAttributeSource transactionAttributeSource) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionAttributeSource(transactionAttributeSource); // 设置事务属性源
        if (this.txManager != null) {
            // 若用户显式定义了事务管理器（如{@code PlatformTransactionManager}），则绑定到拦截器 
            interceptor.setTransactionManager(this.txManager);
        }
        return interceptor;
    }

}

