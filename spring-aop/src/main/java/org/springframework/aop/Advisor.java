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

package org.springframework.aop;

import org.aopalliance.aop.Advice;
/**
 * Advisor接口是Spring AOP中定义切面的基础接口，它将通知（Advice）与切点（Pointcut）结合，
 * 决定在哪些连接点（Joinpoint）应用通知逻辑。此接口为Spring内部实现服务，不直接供开发者使用。
 */
public interface Advisor {

    /**
     * 空Advice占位符，当未配置有效通知时返回此对象（例如配置错误或初始化未完成时）。
     * 从Spring 5.0版本开始引入，用于统一空值处理。
     */
    Advice EMPTY_ADVICE = new Advice() {};

    /**
     * 获取与当前Advisor绑定的通知对象。通知可以是以下类型：
     * - 方法拦截器（MethodInterceptor，实现环绕通知）
     * - 前置通知（BeforeAdvice）
     * - 异常通知（ThrowsAdvice）
     * - 返回通知（AfterReturningAdvice）
     * 通知的具体行为由实现类定义，例如AspectJMethodBeforeAdvice表示前置通知。
     */
    Advice getAdvice();

    /**
     * 判断当前Advisor是否与目标对象的特定实例绑定。
     * 若返回true，表示每个目标实例拥有独立的Advisor；
     * 若返回false，表示所有目标实例共享同一个Advisor。
     * 当前Spring框架未直接使用此方法，开发者应通过以下方式控制生命周期：
     * 1. 使用单例/原型作用域Bean定义
     * 2. 通过编程式代理创建时手动管理
     * 从Spring 6.0.10开始，默认实现返回true。
     */
    default boolean isPerInstance() {
        return true;
    }

}


