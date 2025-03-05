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

package org.springframework.transaction.interceptor;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Properties;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;

/**
 * AOP Alliance MethodInterceptor for declarative transaction
 * management using the common Spring transaction infrastructure
 * ({@link org.springframework.transaction.PlatformTransactionManager}/
 * {@link org.springframework.transaction.ReactiveTransactionManager}).
 *
 * <p>Derives from the {@link TransactionAspectSupport} class which
 * contains the integration with Spring's underlying transaction API.
 * TransactionInterceptor simply calls the relevant superclass methods
 * such as {@link #invokeWithinTransaction} in the correct order.
 *
 * <p>TransactionInterceptors are thread-safe.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Sebastien Deleuze
 * @see TransactionProxyFactoryBean
 * @see org.springframework.aop.framework.ProxyFactoryBean
 * @see org.springframework.aop.framework.ProxyFactory
 */
@SuppressWarnings("serial")
/**
 * 事务拦截器，用于通过Spring事务基础设施（PlatformTransactionManager/ReactiveTransactionManager）
 * 实现声明式事务管理。继承自TransactionAspectSupport，整合了Spring事务管理的核心逻辑。
 * 该类是线程安全的，作为AOP Alliance的MethodInterceptor实现，拦截方法调用并管理事务边界。
 */
public class TransactionInterceptor extends TransactionAspectSupport implements MethodInterceptor, Serializable {

    /**
     * 无参构造函数，需后续通过set方法设置事务管理器和事务属性源。
     */
    public TransactionInterceptor() {
    }

    /**
     * 构造函数，直接指定事务管理器和事务属性源（5.2.5版本新增）。
     * @param ptm 实际执行事务管理的默认事务管理器
     * @param tas 用于查找事务属性的源对象
     */
    public TransactionInterceptor(TransactionManager ptm, TransactionAttributeSource tas) {
        setTransactionManager(ptm); // 设置事务管理器
        setTransactionAttributeSource(tas); // 设置事务属性源
    }

    /**
     * 过时构造函数（推荐使用基于TransactionManager的构造函数）。
     * @param ptm 传统的PlatformTransactionManager事务管理器
     * @param tas 事务属性源
     */
    @Deprecated
    public TransactionInterceptor(PlatformTransactionManager ptm, TransactionAttributeSource tas) {
        setTransactionManager(ptm);
        setTransactionAttributeSource(tas);
    }

    /**
     * 过时构造函数（推荐使用setTransactionAttributes方法）。
     * @param ptm 事务管理器
     * @param attributes 以Properties格式定义的事务属性
     */
    @Deprecated
    public TransactionInterceptor(PlatformTransactionManager ptm, Properties attributes) {
        setTransactionManager(ptm);
        setTransactionAttributes(attributes); // 将Properties转换为事务属性源
    }

    /**
     * 核心拦截方法，通过AOP调用链触发事务管理。
     * @param invocation 方法调用上下文
     * @return 方法执行结果
     * @throws Throwable 方法可能抛出的异常
     */
    @Override
    public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
        // 获取目标类（可能为null，如代理对象未绑定具体实例）
        Class<?> targetClass = (invocation.getThis() != null ?
                AopUtils.getTargetClass(invocation.getThis()) : null);

        // 调用父类TransactionAspectSupport的事务管理逻辑
        return invokeWithinTransaction(
                invocation.getMethod(), // 当前调用方法
                targetClass, // 目标类
                invocation::proceed // 方法执行回调（触发实际业务逻辑）
        );
    }

    //---------------------------------------------------------------------
    // 序列化支持（因父类未实现Serializable，需手动处理状态）
    //---------------------------------------------------------------------

    /**
     * 自定义序列化逻辑，将父类状态写入输出流。
     */
    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.defaultWriteObject(); // 默认序列化当前类状态

        // 手动序列化父类关键字段
        oos.writeObject(getTransactionManagerBeanName());
        oos.writeObject(getTransactionManager());
        oos.writeObject(getTransactionAttributeSource());
        oos.writeObject(getBeanFactory());
    }

    /**
     * 自定义反序列化逻辑，从输入流恢复父类状态。
     */
    private void readObject(ObjectInputStream ois)
            throws IOException, ClassNotFoundException {
        ois.defaultReadObject(); // 默认反序列化当前类状态

        // 手动恢复父类字段
        setTransactionManagerBeanName((String) ois.readObject());
        setTransactionManager((PlatformTransactionManager) ois.readObject());
        setTransactionAttributeSource((TransactionAttributeSource) ois.readObject());
        setBeanFactory((BeanFactory) ois.readObject());
    }

}