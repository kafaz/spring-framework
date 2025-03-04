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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.AdviceMode;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

/**
 * 启用Spring基于注解的事务管理功能，类似于Spring XML命名空间中{@code <tx:*>}的配置支持。
 * 该注解需在{@link org.springframework.context.annotation.Configuration @Configuration}
 * 类上使用，用于配置传统的命令式事务管理或响应式事务管理。
 *
 * <p>以下示例演示了如何通过{@link org.springframework.transaction.PlatformTransactionManager
 * PlatformTransactionManager}实现命令式事务管理。若需响应式事务管理，可配置
 * {@link org.springframework.transaction.ReactiveTransactionManager ReactiveTransactionManager}。
 *
 * <pre class="code">
 * &#064;Configuration
 * &#064;EnableTransactionManagement
 * public class AppConfig {
 *
 *     &#064;Bean
 *     public FooRepository fooRepository() {
 *         // 配置并返回包含{@code @Transactional}方法的类实例
 *         return new JdbcFooRepository(dataSource());
 *     }
 *
 *     &#064;Bean
 *     public DataSource dataSource() {
 *         // 配置并返回所需的JDBC数据源
 *     }
 *
 *     &#064;Bean
 *     public PlatformTransactionManager txManager() {
 *         return new DataSourceTransactionManager(dataSource());
 *     }
 * }</pre>
 *
 * <p>作为对比，以下XML配置与上述Java配置等效：
 *
 * <pre class="code">
 * <beans>
 *
 *     <tx:annotation-driven/>
 *
 *     <bean id="fooRepository" class="com.foo.JdbcFooRepository">
 *         <constructor-arg ref="dataSource"/>
 *     </bean>
 *
 *     <bean id="dataSource" class="com.vendor.VendorDataSource"/>
 *
 *     <bean id="transactionManager" class="org.sfwk...DataSourceTransactionManager">
 *         <constructor-arg ref="dataSource"/>
 *     </bean>
 *
 * </beans>
 * </pre>
 *
 * 在上述两种场景中，{@code @EnableTransactionManagement}和{@code <tx:annotation-driven/>}
 * 均负责注册支撑基于注解事务管理的Spring组件，例如TransactionInterceptor以及基于代理或AspectJ的通知，
 * 这些组件会在调用{@code JdbcFooRepository}的{@code @Transactional}方法时织入调用链。
 *
 * <p>两个示例的细微差别在于{@code TransactionManager} Bean的命名：
 * 在{@code @Bean}示例中，名称为<em>"txManager"</em>（基于方法名）；
 * 在XML示例中，名称为<em>"transactionManager"</em>。{@code <tx:annotation-driven/>}默认硬编码查找名为
 * "transactionManager"的Bean，而{@code @EnableTransactionManagement}则更灵活——它会通过类型自动匹配容器中的
 * {@code TransactionManager} Bean。因此名称可以是"txManager"、"transactionManager"或"tm"，不影响功能。
 *
 * <p>若需建立{@code @EnableTransactionManagement}与特定事务管理器Bean的直接关联，
 * 可实现{@link TransactionManagementConfigurer}回调接口，如下所示：
 *
 * <pre class="code">
 * &#064;Configuration
 * &#064;EnableTransactionManagement
 * public class AppConfig implements TransactionManagementConfigurer {
 *
 *     &#064;Bean
 *     public FooRepository fooRepository() {
 *         // 配置并返回包含{@code @Transactional}方法的类实例
 *         return new JdbcFooRepository(dataSource());
 *     }
 *
 *     &#064;Bean
 *     public DataSource dataSource() {
 *         // 配置并返回所需的JDBC数据源
 *     }
 *
 *     &#064;Bean
 *     public PlatformTransactionManager txManager() {
 *         return new DataSourceTransactionManager(dataSource());
 *     }
 *
 *     &#064;Override
 *     public PlatformTransactionManager annotationDrivenTransactionManager() {
 *         return txManager();
 *     }
 * }</pre>
 *
 * <p>此方式的优势在于显式指定事务管理器，或在容器中存在多个{@code TransactionManager} Bean时进行区分。
 * {@code annotationDrivenTransactionManager()}返回的实例将用于处理所有{@code @Transactional}方法。
 * 详见{@link TransactionManagementConfigurer}的Javadoc。
 *
 * <p>{@link #mode}属性控制通知的织入方式：若模式为{@link AdviceMode#PROXY}（默认值），
 * 其他属性将控制代理行为。注意，代理模式仅拦截通过代理对象的调用，同一类中的本地方法调用无法被拦截。
 *
 * <p>若{@linkplain #mode}设置为{@link AdviceMode#ASPECTJ}，
 * 则{@link #proxyTargetClass}属性将被忽略。此时需确保类路径中包含{@code spring-aspects}模块JAR，
 * 并通过编译时或加载时织入将切面应用于目标类。此模式下不使用代理，本地调用也会被拦截。
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.1
 * @see TransactionManagementConfigurer
 * @see TransactionManagementConfigurationSelector
 * @see ProxyTransactionManagementConfiguration
 * @see org.springframework.transaction.aspectj.AspectJTransactionManagementConfiguration
 */

/**
 * 启用Spring的注解驱动事务管理功能，支持声明式事务管理和响应式事务管理。
 * 该注解需标注在{@code @Configuration}配置类上，通过导入{@link TransactionManagementConfigurationSelector}
 * 实现事务管理器的自动配置。等效于XML配置中的{@code <tx:annotation-driven/>} 。
 *
 * <p>核心功能：
 * <ul>
 *   <li>注册事务拦截器（如{@code TransactionInterceptor}）</li>
 *   <li>支持基于代理或AspectJ的事务织入</li>
 *   <li>提供对{@code @Transactional}注解的支持</li>
 * </ul>
 */
@Target(ElementType.TYPE) // 限定该注解只能用于类、接口或枚举类型
@Retention(RetentionPolicy.RUNTIME) // 注解在运行时保留，可通过反射读取 
@Documented // 生成Javadoc时包含该注解
@Import(TransactionManagementConfigurationSelector.class) // 导入事务管理配置选择器，决定使用PROXY或ASPECTJ模式
public @interface EnableTransactionManagement {

    /**
     * 是否强制使用基于子类（CGLIB）的代理模式。
     * 默认为{@code false}（使用基于接口的JDK动态代理）。
     * <p>仅在{@link #mode()}为{@code AdviceMode.PROXY}时生效。
     * 设置为{@code true}会影响所有需要代理的Spring Bean（如{@code @Async}）。
     */
    boolean proxyTargetClass() default false;

    /**
     * 指定事务通知的织入方式。
     * 默认为{@code AdviceMode.PROXY}（基于代理），可选{@code ASPECTJ}（需依赖{@code spring-aspects}）。
     * <p>PROXY模式仅拦截外部方法调用，同一类中的内部调用无法触发事务；
     * ASPECTJ模式通过字节码织入支持全场景拦截。
     */
    AdviceMode mode() default AdviceMode.PROXY;

    /**
     * 定义事务通知的优先级（数值越小优先级越高）。
     * 默认为{@code Ordered.LOWEST_PRECEDENCE}（最低优先级）。
     */
    int order() default Ordered.LOWEST_PRECEDENCE;

    /**
     * 全局回滚规则：默认仅在未检查异常（RuntimeException）时回滚。
     * 可设置为{@code RollbackOn.ALL_EXCEPTIONS}以包含检查型异常（checked exception）。
     * <p>此配置会被{@code @Transactional#rollbackFor()}等方法级规则覆盖。
     */
    RollbackOn rollbackOn() default RollbackOn.RUNTIME_EXCEPTIONS;
}

