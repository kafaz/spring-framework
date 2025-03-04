/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.aop.framework.autoproxy;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * 基础设施通知器自动代理创建器，专门用于处理Spring框架内部的基础设施级别的通知器。
 * 该类只考虑基础设施级别的Advisor Bean，会忽略任何应用程序定义的Advisor。
 * 
 * 主要用途：
 * 1. 为Spring内部功能（如事务管理）提供AOP支持
 * 2. 确保基础设施级别的切面（如@Transactional）能够正常工作
 * 3. 避免与应用程序定义的切面产生冲突
 *
 * @author Juergen Hoeller
 * @since 2.0.7
 */
@SuppressWarnings("serial")
public class InfrastructureAdvisorAutoProxyCreator extends AbstractAdvisorAutoProxyCreator {

    /**
     * 持有对Bean工厂的引用，用于后续判断Advisor的角色
     * 可空注解表示该字段可能为null
     */
    private @Nullable ConfigurableListableBeanFactory beanFactory;


    /**
     * 初始化Bean工厂，在Spring容器启动过程中被调用
     * 该方法会保存对Bean工厂的引用，以便后续使用
     * 
     * @param beanFactory 可配置的可列举Bean工厂
     */
    @Override
    protected void initBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        // 调用父类的初始化方法
        super.initBeanFactory(beanFactory);
        // 保存Bean工厂引用
        this.beanFactory = beanFactory;
    }

    /**
     * 判断给定名称的Advisor Bean是否符合条件（是否为基础设施Bean）
     * 该方法是该类的核心逻辑，用于筛选只处理基础设施级别的Advisor
     * 
     * 判断标准：
     * 1. Bean工厂不为null
     * 2. Bean工厂中包含指定名称的Bean定义
     * 3. 该Bean定义的角色是ROLE_INFRASTRUCTURE（基础设施角色）
     * 
     * @param beanName 要检查的Bean名称
     * @return 如果是符合条件的基础设施Advisor则返回true，否则返回false
     */
    @Override
    protected boolean isEligibleAdvisorBean(String beanName) {
        return (this.beanFactory != null && 
                this.beanFactory.containsBeanDefinition(beanName) &&
                this.beanFactory.getBeanDefinition(beanName).getRole() == BeanDefinition.ROLE_INFRASTRUCTURE);
    }

}