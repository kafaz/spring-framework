/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.context.annotation;

import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.type.AnnotationMetadata;

/**
 * 自动代理注册器，根据{@code @Enable*}注解的{@code mode}和{@code proxyTargetClass}属性注册自动代理创建器。
 * 用于支持AOP代理和声明式事务管理等场景 。
 *
 * @author Chris Beams
 * @since 3.1
 * @see org.springframework.cache.annotation.EnableCaching
 * @see org.springframework.transaction.annotation.EnableTransactionManagement
 */
public class AutoProxyRegistrar implements ImportBeanDefinitionRegistrar {

    private final Log logger = LogFactory.getLog(getClass());

    /**
     * 核心方法：根据{@code @Enable*}注解的属性注册并配置自动代理创建器（APC）。
     * 逻辑步骤：
     * 1. 遍历导入该注册器的配置类上的所有注解，寻找包含{@code mode}和{@code proxyTargetClass}属性的注解。
     * 2. 若{@code mode}为{@code PROXY}，注册APC；若{@code proxyTargetClass}为{@code true}，强制使用CGLIB代理 。
     */
    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        boolean candidateFound = false;
        Set<String> annTypes = importingClassMetadata.getAnnotationTypes(); // 获取配置类上的所有注解类型
        for (String annType : annTypes) {
            // 提取注解属性（如@EnableTransactionManagement的mode和proxyTargetClass）
            AnnotationAttributes candidate = AnnotationConfigUtils.attributesFor(importingClassMetadata, annType);
            if (candidate == null) {
                continue;
            }
            Object mode = candidate.get("mode"); // 获取mode属性值（PROXY或ASPECTJ）
            Object proxyTargetClass = candidate.get("proxyTargetClass"); // 获取proxyTargetClass属性值（是否强制CGLIB）
            
            // 验证属性类型是否符合预期（mode为AdviceMode，proxyTargetClass为Boolean）
            if (mode != null && proxyTargetClass != null 
                    && AdviceMode.class == mode.getClass()
                    && Boolean.class == proxyTargetClass.getClass()) {
                candidateFound = true;
                
                // 若mode为PROXY，注册自动代理创建器
                if (mode == AdviceMode.PROXY) {
                    // 注册InfrastructureAdvisorAutoProxyCreator（Spring的默认APC）
                    AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
                    
                    // 若proxyTargetClass为true，强制使用CGLIB代理（而非JDK动态代理）
                    if ((Boolean) proxyTargetClass) {
                        AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
                        return; // 配置完成，提前退出
                    }
                }
            }
        }
        
        // 若未找到符合条件的注解，记录警告日志
        if (!candidateFound && logger.isInfoEnabled()) {
            String name = getClass().getSimpleName();
            logger.info(String.format("%s被导入但未找到同时包含'mode'和'proxyTargetClass'属性的注解。" +
                    "请确保%s与相关@Enable*注解在同一个配置类上被@Import。", name, name));
        }
    }

}

