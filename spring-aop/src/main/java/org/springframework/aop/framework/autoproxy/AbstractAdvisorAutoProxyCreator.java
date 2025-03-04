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

package org.springframework.aop.framework.autoproxy;

import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.AopConfigException;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;

/**
 * 通用的自动代理创建器，基于检测到的Advisor为特定Bean构建AOP代理。
 * 
 * <p>子类可以重写{@link #findCandidateAdvisors()}方法以返回适用于任何对象的自定义Advisor列表。
 * 子类还可以重写继承的{@link #shouldSkip}方法以排除某些对象不进行自动代理。
 * 
 * <p>需要排序的Advisor或Advice应使用{@link org.springframework.core.annotation.Order @Order}注解
 * 或实现{@link org.springframework.core.Ordered}接口。此类使用{@link AnnotationAwareOrderComparator}
 * 对Advisor进行排序。未标注{@code @Order}或未实现{@code Ordered}接口的Advisor将被视为无序，
 * 它们会出现在Advisor链的末尾，且顺序未定义。
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #findCandidateAdvisors
 */
@SuppressWarnings("serial")
public abstract class AbstractAdvisorAutoProxyCreator extends AbstractAutoProxyCreator {

    // BeanFactoryAdvisorRetrievalHelper用于从容器中检索Advisor
    private @Nullable BeanFactoryAdvisorRetrievalHelper advisorRetrievalHelper;

    /**
     * 设置BeanFactory并初始化BeanFactoryAdvisorRetrievalHelper。
     * 需要ConfigurableListableBeanFactory类型的BeanFactory，否则抛出异常。
     */
    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        super.setBeanFactory(beanFactory);
        if (!(beanFactory instanceof ConfigurableListableBeanFactory clbf)) {
            throw new IllegalArgumentException(
                    "AdvisorAutoProxyCreator requires a ConfigurableListableBeanFactory: " + beanFactory);
        }
        initBeanFactory(clbf);
    }

    /**
     * 初始化BeanFactoryAdvisorRetrievalHelper。
     */
    protected void initBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        this.advisorRetrievalHelper = new BeanFactoryAdvisorRetrievalHelperAdapter(beanFactory);
    }

    /**
     * 获取适用于当前Bean的Advices和Advisors。
     * 如果没有匹配的Advisor，则返回DO_NOT_PROXY（表示不需要代理）。
     */
    @Override
    protected Object @Nullable [] getAdvicesAndAdvisorsForBean(
            Class<?> beanClass, String beanName, @Nullable TargetSource targetSource) {

        List<Advisor> advisors = findEligibleAdvisors(beanClass, beanName);
        if (advisors.isEmpty()) {
            return DO_NOT_PROXY;
        }
        return advisors.toArray();
    }

    /**
     * 查找适用于当前类的所有符合条件的Advisor。
     * 
     * @param beanClass 要查找Advisor的目标类
     * @param beanName 当前代理Bean的名称
     * @return 符合条件的Advisor列表，如果没有匹配的Advisor则返回空列表
     * @see #findCandidateAdvisors
     * @see #sortAdvisors
     * @see #extendAdvisors
     */
    protected List<Advisor> findEligibleAdvisors(Class<?> beanClass, String beanName) {
        // 查找所有候选Advisor
        List<Advisor> candidateAdvisors = findCandidateAdvisors();
        // 筛选出适用于当前Bean的Advisor
        List<Advisor> eligibleAdvisors = findAdvisorsThatCanApply(candidateAdvisors, beanClass, beanName);
        // 扩展Advisor列表（子类可重写此方法）
        extendAdvisors(eligibleAdvisors);
        if (!eligibleAdvisors.isEmpty()) {
            try {
                // 对Advisor进行排序
                eligibleAdvisors = sortAdvisors(eligibleAdvisors);
            } catch (BeanCreationException ex) {
                throw new AopConfigException("Advisor sorting failed with unexpected bean creation, probably due " +
                        "to custom use of the Ordered interface. Consider using the @Order annotation instead.", ex);
            }
        }
        return eligibleAdvisors;
    }

    /**
     * 查找所有候选Advisor用于自动代理。
     * 
     * @return 候选Advisor列表
     */
    protected List<Advisor> findCandidateAdvisors() {
        Assert.state(this.advisorRetrievalHelper != null, "No BeanFactoryAdvisorRetrievalHelper available");
        return this.advisorRetrievalHelper.findAdvisorBeans();
    }

    /**
     * 在给定的候选Advisor中查找适用于指定Bean的所有Advisor。
     * 
     * @param candidateAdvisors 候选Advisor列表
     * @param beanClass 目标类
     * @param beanName 目标Bean的名称
     * @return 适用的Advisor列表
     * @see ProxyCreationContext#getCurrentProxiedBeanName()
     */
    protected List<Advisor> findAdvisorsThatCanApply(
            List<Advisor> candidateAdvisors, Class<?> beanClass, String beanName) {

        // 设置当前正在代理的Bean名称
        ProxyCreationContext.setCurrentProxiedBeanName(beanName);
        try {
            return AopUtils.findAdvisorsThatCanApply(candidateAdvisors, beanClass);
        } finally {
            // 清除当前代理Bean名称
            ProxyCreationContext.setCurrentProxiedBeanName(null);
        }
    }

    /**
     * 判断具有给定名称的Advisor Bean是否符合代理条件。
     * 默认实现始终返回true，子类可重写此方法以实现自定义过滤逻辑。
     * 
     * @param beanName Advisor Bean的名称
     * @return 是否符合条件
     */
    protected boolean isEligibleAdvisorBean(String beanName) {
        return true;
    }

    /**
     * 根据顺序对Advisor进行排序。
     * 子类可以选择重写此方法以自定义排序策略。
     * 
     * @param advisors 待排序的Advisor列表
     * @return 排序后的Advisor列表
     * @see org.springframework.core.Ordered
     * @see org.springframework.core.annotation.Order
     * @see org.springframework.core.annotation.AnnotationAwareOrderComparator
     */
    protected List<Advisor> sortAdvisors(List<Advisor> advisors) {
        AnnotationAwareOrderComparator.sort(advisors);
        return advisors;
    }

    /**
     * 扩展Advisor列表的钩子方法。
     * 子类可以重写此方法以注册额外的Advisor，通常用于添加暴露上下文信息的Advisor。
     * 默认实现为空。
     * 
     * @param candidateAdvisors 已经筛选出的适用于某个Bean的Advisor列表
     */
    protected void extendAdvisors(List<Advisor> candidateAdvisors) {
    }

    /**
     * 此自动代理创建器始终返回预过滤的Advisor。
     */
    @Override
    protected boolean advisorsPreFiltered() {
        return true;
    }

    /**
     * BeanFactoryAdvisorRetrievalHelper的子类，委托给外部的AbstractAdvisorAutoProxyCreator功能。
     */
    private class BeanFactoryAdvisorRetrievalHelperAdapter extends BeanFactoryAdvisorRetrievalHelper {

        public BeanFactoryAdvisorRetrievalHelperAdapter(ConfigurableListableBeanFactory beanFactory) {
            super(beanFactory);
        }

        /**
         * 判断给定名称的Bean是否符合Advisor条件。
         */
        @Override
        protected boolean isEligibleBean(String beanName) {
            return AbstractAdvisorAutoProxyCreator.this.isEligibleAdvisorBean(beanName);
        }
    }
}

