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

package org.springframework.transaction.interceptor;

import java.lang.reflect.Method;
import java.util.Properties;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import io.vavr.control.Try;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.core.KotlinDetector;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ReactiveAdapter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.reactive.TransactionContextManager;
import org.springframework.transaction.support.CallbackPreferringPlatformTransactionManager;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.StringUtils;

/**
 * 事务切面（如事务拦截器或AspectJ切面）的基类。
 * <p>通过此基类可便捷使用Spring底层事务基础设施，为任意切面系统实现事务切面。</p>
 * <p>子类需负责按正确顺序调用本类中的方法。</p>
 * <p>若未在事务属性中指定事务名称，默认暴露的事务名称格式为：完整类名 + "." + 方法名。</p>
 * <p>采用策略设计模式：<br>
 * - 事务管理策略：由具体 PlatformTransactionManager 或 ReactiveTransactionManager 实现类执行实际事务管理<br>
 * - 事务属性源策略：通过 TransactionAttributeSource（如基于注解的实现）确定类/方法的事务定义</p>
 * <p>当事务切面的 TransactionManager 和 TransactionAttributeSource 可序列化时，该事务切面本身支持序列化。</p>
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Stéphane Nicoll
 * @author Sam Brannen
 * @author Mark Paluch
 * @author Sebastien Deleuze
 * @author Enric Sala
 * @since 1.1
 * @see PlatformTransactionManager        // 平台事务管理器接口
 * @see ReactiveTransactionManager        // 响应式事务管理器接口
 * @see #setTransactionManager            // 注入事务管理器的方法
 * @see #setTransactionAttributes         // 设置事务属性集合的方法
 * @see #setTransactionAttributeSource    // 设置事务属性源的方法
 */
public abstract class TransactionAspectSupport implements BeanFactoryAware, InitializingBean {

	// NOTE: This class must not implement Serializable because it serves as base
	// class for AspectJ aspects (which are not allowed to implement Serializable)!


	/**
	 * Key to use to store the default transaction manager.
	 */
	private static final Object DEFAULT_TRANSACTION_MANAGER_KEY = new Object();

	private static final String COROUTINES_FLOW_CLASS_NAME = "kotlinx.coroutines.flow.Flow";

	/**
	 * Reactive Streams API present on the classpath?
	 */
	private static final boolean reactiveStreamsPresent = ClassUtils.isPresent(
			"org.reactivestreams.Publisher", TransactionAspectSupport.class.getClassLoader());

	/**
	 * Vavr library present on the classpath?
	 */
	private static final boolean vavrPresent = ClassUtils.isPresent(
			"io.vavr.control.Try", TransactionAspectSupport.class.getClassLoader());

	/**
	 * Holder to support the {@code currentTransactionStatus()} method,
	 * and to support communication between different cooperating advices
	 * (for example, before and after advice) if the aspect involves more than a
	 * single method (as will be the case for around advice).
	 */
	private static final ThreadLocal<TransactionInfo> transactionInfoHolder =
			new NamedThreadLocal<>("Current aspect-driven transaction");


	/**
	 * Subclasses can use this to return the current TransactionInfo.
	 * Only subclasses that cannot handle all operations in one method,
	 * such as an AspectJ aspect involving distinct before and after advice,
	 * need to use this mechanism to get at the current TransactionInfo.
	 * An around advice such as an AOP Alliance MethodInterceptor can hold a
	 * reference to the TransactionInfo throughout the aspect method.
	 * <p>A TransactionInfo will be returned even if no transaction was created.
	 * The {@code TransactionInfo.hasTransaction()} method can be used to query this.
	 * <p>To find out about specific transaction characteristics, consider using
	 * TransactionSynchronizationManager's {@code isSynchronizationActive()}
	 * and/or {@code isActualTransactionActive()} methods.
	 * @return the TransactionInfo bound to this thread, or {@code null} if none
	 * @see TransactionInfo#hasTransaction()
	 * @see org.springframework.transaction.support.TransactionSynchronizationManager#isSynchronizationActive()
	 * @see org.springframework.transaction.support.TransactionSynchronizationManager#isActualTransactionActive()
	 */
	protected static @Nullable TransactionInfo currentTransactionInfo() throws NoTransactionException {
		return transactionInfoHolder.get();
	}

	/**
	 * Return the transaction status of the current method invocation.
	 * Mainly intended for code that wants to set the current transaction
	 * rollback-only but not throw an application exception.
	 * <p>This exposes the locally declared transaction boundary with its declared name
	 * and characteristics, as managed by the aspect. Ar runtime, the local boundary may
	 * participate in an outer transaction: If you need transaction metadata from such
	 * an outer transaction (the actual resource transaction) instead, consider using
	 * {@link org.springframework.transaction.support.TransactionSynchronizationManager}.
	 * @throws NoTransactionException if the transaction info cannot be found,
	 * because the method was invoked outside an AOP invocation context
	 * @see org.springframework.transaction.support.TransactionSynchronizationManager#getCurrentTransactionName()
	 * @see org.springframework.transaction.support.TransactionSynchronizationManager#isCurrentTransactionReadOnly()
	 */
	public static TransactionStatus currentTransactionStatus() throws NoTransactionException {
		TransactionInfo info = currentTransactionInfo();
		if (info == null || info.transactionStatus == null) {
			throw new NoTransactionException("No transaction aspect-managed TransactionStatus in scope");
		}
		return info.transactionStatus;
	}


	// 日志记录器，用于输出事务相关的调试或监控信息，通过getClass()动态获取当前类的Class对象以绑定日志上下文
	protected final Log logger = LogFactory.getLog(getClass());

	// 响应式适配器注册表，用于支持响应式编程中的事务管理（如WebFlux），可能为null表示未启用响应式特性
	private final @Nullable ReactiveAdapterRegistry reactiveAdapterRegistry;

	// 事务管理器Bean的名称，用于从Spring容器中显式指定事务管理器实例（当存在多个事务管理器时使用）
	private @Nullable String transactionManagerBeanName;

	// 当前使用的事务管理器实例，直接负责事务的开启、提交、回滚等核心操作
	private @Nullable TransactionManager transactionManager;

	// 事务属性源，用于解析目标方法或类的事务配置（如传播行为、隔离级别、回滚规则等）
	private @Nullable TransactionAttributeSource transactionAttributeSource;

	// Spring Bean工厂引用，用于动态获取容器中的Bean（如根据名称查找事务管理器）
	private @Nullable BeanFactory beanFactory;

	// 事务管理器缓存，使用弱引用存储已解析的事务管理器实例，避免重复查找（键为事务管理器标识，如Bean名称）
	private final ConcurrentMap<Object, TransactionManager> transactionManagerCache =
			new ConcurrentReferenceHashMap<>(4);

	// 响应式事务支持缓存，存储方法与其对应的响应式事务处理器（如Mono/Flux适配逻辑），提升重复调用性能
	private final ConcurrentMap<Method, ReactiveTransactionSupport> transactionSupportCache =
			new ConcurrentReferenceHashMap<>(1024);


	protected TransactionAspectSupport() {
		if (reactiveStreamsPresent) {
			this.reactiveAdapterRegistry = ReactiveAdapterRegistry.getSharedInstance();
		}
		else {
			this.reactiveAdapterRegistry = null;
		}
	}


	/**
	 * Specify the name of the default transaction manager bean.
	 * <p>This can either point to a traditional {@link PlatformTransactionManager} or a
	 * {@link ReactiveTransactionManager} for reactive transaction management.
	 */
	public void setTransactionManagerBeanName(@Nullable String transactionManagerBeanName) {
		this.transactionManagerBeanName = transactionManagerBeanName;
	}

	/**
	 * Return the name of the default transaction manager bean.
	 */
	protected final @Nullable String getTransactionManagerBeanName() {
		return this.transactionManagerBeanName;
	}

	/**
	 * Specify the <em>default</em> transaction manager to use to drive transactions.
	 * <p>This can either be a traditional {@link PlatformTransactionManager} or a
	 * {@link ReactiveTransactionManager} for reactive transaction management.
	 * <p>The default transaction manager will be used if a <em>qualifier</em>
	 * has not been declared for a given transaction or if an explicit name for the
	 * default transaction manager bean has not been specified.
	 * @see #setTransactionManagerBeanName
	 */
	public void setTransactionManager(@Nullable TransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

	/**
	 * Return the default transaction manager, or {@code null} if unknown.
	 * <p>This can either be a traditional {@link PlatformTransactionManager} or a
	 * {@link ReactiveTransactionManager} for reactive transaction management.
	 */
	public @Nullable TransactionManager getTransactionManager() {
		return this.transactionManager;
	}

	/**
	 * Set properties with method names as keys and transaction attribute
	 * descriptors (parsed via TransactionAttributeEditor) as values:
	 * for example, key = "myMethod", value = "PROPAGATION_REQUIRED,readOnly".
	 * <p>Note: Method names are always applied to the target class,
	 * no matter if defined in an interface or the class itself.
	 * <p>Internally, a NameMatchTransactionAttributeSource will be
	 * created from the given properties.
	 * @see #setTransactionAttributeSource
	 * @see TransactionAttributeEditor
	 * @see NameMatchTransactionAttributeSource
	 */
	public void setTransactionAttributes(Properties transactionAttributes) {
		NameMatchTransactionAttributeSource tas = new NameMatchTransactionAttributeSource();
		tas.setProperties(transactionAttributes);
		this.transactionAttributeSource = tas;
	}

	/**
	 * Set multiple transaction attribute sources which are used to find transaction
	 * attributes. Will build a CompositeTransactionAttributeSource for the given sources.
	 * @see CompositeTransactionAttributeSource
	 * @see MethodMapTransactionAttributeSource
	 * @see NameMatchTransactionAttributeSource
	 * @see org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
	 */
	public void setTransactionAttributeSources(TransactionAttributeSource... transactionAttributeSources) {
		this.transactionAttributeSource = new CompositeTransactionAttributeSource(transactionAttributeSources);
	}

	/**
	 * Set the transaction attribute source which is used to find transaction
	 * attributes. If specifying a String property value, a PropertyEditor
	 * will create a MethodMapTransactionAttributeSource from the value.
	 * @see TransactionAttributeSourceEditor
	 * @see MethodMapTransactionAttributeSource
	 * @see NameMatchTransactionAttributeSource
	 * @see org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
	 */
	public void setTransactionAttributeSource(@Nullable TransactionAttributeSource transactionAttributeSource) {
		this.transactionAttributeSource = transactionAttributeSource;
	}

	/**
	 * Return the transaction attribute source.
	 */
	public @Nullable TransactionAttributeSource getTransactionAttributeSource() {
		return this.transactionAttributeSource;
	}

	/**
	 * Set the BeanFactory to use for retrieving {@code TransactionManager} beans.
	 */
	@Override
	public void setBeanFactory(@Nullable BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * Return the BeanFactory to use for retrieving {@code TransactionManager} beans.
	 */
	protected final @Nullable BeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	/**
	 * Check that required properties were set.
	 */
	@Override
	public void afterPropertiesSet() {
		if (getTransactionManager() == null && this.beanFactory == null) {
			throw new IllegalStateException(
					"Set the 'transactionManager' property or make sure to run within a BeanFactory " +
					"containing a TransactionManager bean!");
		}
		if (getTransactionAttributeSource() == null) {
			throw new IllegalStateException(
					"Either 'transactionAttributeSource' or 'transactionAttributes' is required: " +
					"If there are no transactional methods, then don't use a transaction aspect.");
		}
	}


	/**
	 * General delegate for around-advice-based subclasses, delegating to several other template
	 * methods on this class. Able to handle {@link CallbackPreferringPlatformTransactionManager}
	 * as well as regular {@link PlatformTransactionManager} implementations and
	 * {@link ReactiveTransactionManager} implementations for reactive return types.
	 * @param method the Method being invoked
	 * @param targetClass the target class that we're invoking the method on
	 * @param invocation the callback to use for proceeding with the target invocation
	 * @return the return value of the method, if any
	 * @throws Throwable propagated from the target invocation
	 * 事务拦截处理核心方法，通过环绕通知实现事务的开启、提交与回滚，支持响应式和传统事务管理
	 */
	protected @Nullable Object invokeWithinTransaction(Method method, @Nullable Class<?> targetClass,
			final InvocationCallback invocation) throws Throwable {

		// 获取事务属性源，用于解析当前方法的事务配置（如传播行为、隔离级别）
		TransactionAttributeSource tas = getTransactionAttributeSource();
		final TransactionAttribute txAttr = (tas != null ? tas.getTransactionAttribute(method, targetClass) : null);
		// 确定当前使用的事务管理器（根据事务属性或目标类动态解析）
		final TransactionManager tm = determineTransactionManager(txAttr, targetClass);

		// 处理响应式事务场景（如WebFlux）
		if (this.reactiveAdapterRegistry != null && tm instanceof ReactiveTransactionManager rtm) {
			boolean isSuspendingFunction = KotlinDetector.isSuspendingFunction(method);
			boolean hasSuspendingFlowReturnType = isSuspendingFunction &&
					COROUTINES_FLOW_CLASS_NAME.equals(new MethodParameter(method, -1).getParameterType().getName());

			// 从缓存中获取或创建响应式事务支持对象，根据方法返回类型适配响应式流（Mono/Flux）
			ReactiveTransactionSupport txSupport = this.transactionSupportCache.computeIfAbsent(method, key -> {
				Class<?> reactiveType = (isSuspendingFunction ?
					(hasSuspendingFlowReturnType ? Flux.class : Mono.class) : method.getReturnType());
				ReactiveAdapter adapter = this.reactiveAdapterRegistry.getAdapter(reactiveType);
				if (adapter == null) {
					throw new IllegalStateException("无法为非响应式返回类型应用响应式事务管理");
				}
				return new ReactiveTransactionSupport(adapter);
			});

			// 委托给响应式事务处理器执行事务逻辑
			return txSupport.invokeWithinTransaction(method, targetClass, invocation, txAttr, rtm);
		}

		// 传统事务处理流程（非响应式）
		PlatformTransactionManager ptm = asPlatformTransactionManager(tm);
		final String joinpointIdentification = methodIdentification(method, targetClass, txAttr);

		// 处理非回调优先的事务管理器（标准事务边界控制）
		if (txAttr == null || !(ptm instanceof CallbackPreferringPlatformTransactionManager cpptm)) {
			// 创建事务并准备事务上下文
			TransactionInfo txInfo = createTransactionIfNecessary(ptm, txAttr, joinpointIdentification);

			Object retVal;
			try {
				// 执行目标方法（通过拦截器链调用）
				retVal = invocation.proceedWithInvocation();
			}
			catch (Throwable ex) {
				// 异常处理：根据异常类型决定是否标记回滚
				completeTransactionAfterThrowing(txInfo, ex);
				throw ex;
			}
			finally {
				// 清理当前线程的事务上下文
				cleanupTransactionInfo(txInfo);
			}

			// 处理异步返回值（如Future）的事务同步
			if (retVal != null && txAttr != null) {
				TransactionStatus status = txInfo.getTransactionStatus();
				if (status != null) {
					if (retVal instanceof Future<?> future && future.isDone()) {
						try {
							future.get(); // 检查异步任务结果以触发回滚
						}
						catch (ExecutionException ex) {
							Throwable cause = ex.getCause();
							if (txAttr.rollbackOn(cause)) {
								status.setRollbackOnly();
							}
						}
						catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
						}
					}
					// 处理Vavr的Try类型返回值的回滚规则
					else if (vavrPresent && VavrDelegate.isVavrTry(retVal)) {
						retVal = VavrDelegate.evaluateTryFailure(retVal, txAttr, status);
					}
				}
			}

			// 提交事务（若未标记回滚）
			commitTransactionAfterReturning(txInfo);
			return retVal;
		}
		// 处理回调优先的事务管理器（如编程式事务）
		else {
			Object result;
			final ThrowableHolder throwableHolder = new ThrowableHolder();

			// 通过事务回调接口执行事务操作
			try {
				result = cpptm.execute(txAttr, status -> {
					TransactionInfo txInfo = prepareTransactionInfo(ptm, txAttr, joinpointIdentification, status);
					try {
						Object retVal = invocation.proceedWithInvocation();
						// 处理Vavr Try类型的返回值回滚规则
						if (retVal != null && vavrPresent && VavrDelegate.isVavrTry(retVal)) {
							retVal = VavrDelegate.evaluateTryFailure(retVal, txAttr, status);
						}
						return retVal;
					}
					catch (Throwable ex) {
						if (txAttr.rollbackOn(ex)) {
							// 运行时异常直接抛出触发回滚
							if (ex instanceof RuntimeException runtimeException) {
								throw runtimeException;
							}
							else {
								throw new ThrowableHolderException(ex);
							}
						}
						else {
							// 非回滚异常暂存异常并返回null（后续提交事务）
							throwableHolder.throwable = ex;
							return null;
						}
					}
					finally {
						cleanupTransactionInfo(txInfo);
					}
				});
			}
			catch (ThrowableHolderException ex) {
				throw ex.getCause(); // 抛出暂存的业务异常
			}
			catch (TransactionSystemException ex2) {
				// 处理事务系统异常时保留原始业务异常
				if (throwableHolder.throwable != null) {
					logger.error("事务提交异常覆盖了业务异常", throwableHolder.throwable);
					ex2.initApplicationException(throwableHolder.throwable);
				}
				throw ex2;
			}
			catch (Throwable ex2) {
				// 处理其他未捕获异常
				if (throwableHolder.throwable != null) {
					logger.error("事务提交异常覆盖了业务异常", throwableHolder.throwable);
				}
				throw ex2;
			}

			// 抛出暂存的业务异常（若存在）
			if (throwableHolder.throwable != null) {
				throw throwableHolder.throwable;
			}
			return result;
		}
	}

	/**
	 * Clear the transaction manager cache.
	 */
	protected void clearTransactionManagerCache() {
		this.transactionManagerCache.clear();
		this.beanFactory = null;
	}

	/**
	 * Determine the specific transaction manager to use for the given transaction.
	 * @param txAttr the current transaction attribute
	 * @param targetClass the target class that the attribute has been declared on
	 * @since 6.2
	 */
	protected @Nullable TransactionManager determineTransactionManager(
			@Nullable TransactionAttribute txAttr, @Nullable Class<?> targetClass) {

		TransactionManager tm = determineTransactionManager(txAttr);
		if (tm != null) {
			return tm;
		}

		// Do not attempt to lookup tx manager if no tx attributes are set
		if (txAttr == null || this.beanFactory == null) {
			return getTransactionManager();
		}

		String qualifier = txAttr.getQualifier();
		if (StringUtils.hasText(qualifier)) {
			return determineQualifiedTransactionManager(this.beanFactory, qualifier);
		}
		else if (targetClass != null) {
			// Consider type-level qualifier annotations for transaction manager selection
			String typeQualifier = BeanFactoryAnnotationUtils.getQualifierValue(targetClass);
			if (StringUtils.hasText(typeQualifier)) {
				try {
					return determineQualifiedTransactionManager(this.beanFactory, typeQualifier);
				}
				catch (NoSuchBeanDefinitionException ex) {
					// Consider type qualifier as optional, proceed with regular resolution below.
				}
			}
		}

		if (StringUtils.hasText(this.transactionManagerBeanName)) {
			return determineQualifiedTransactionManager(this.beanFactory, this.transactionManagerBeanName);
		}
		else {
			TransactionManager defaultTransactionManager = getTransactionManager();
			if (defaultTransactionManager == null) {
				defaultTransactionManager = this.transactionManagerCache.get(DEFAULT_TRANSACTION_MANAGER_KEY);
				if (defaultTransactionManager == null) {
					defaultTransactionManager = this.beanFactory.getBean(TransactionManager.class);
					this.transactionManagerCache.putIfAbsent(
							DEFAULT_TRANSACTION_MANAGER_KEY, defaultTransactionManager);
				}
			}
			return defaultTransactionManager;
		}
	}

	/**
	 * Determine the specific transaction manager to use for the given transaction.
	 * @deprecated as of 6.2, in favor of {@link #determineTransactionManager(TransactionAttribute, Class)}
	 */
	@Deprecated
	protected @Nullable TransactionManager determineTransactionManager(@Nullable TransactionAttribute txAttr) {
		return null;
	}

	private TransactionManager determineQualifiedTransactionManager(BeanFactory beanFactory, String qualifier) {
		TransactionManager txManager = this.transactionManagerCache.get(qualifier);
		if (txManager == null) {
			txManager = BeanFactoryAnnotationUtils.qualifiedBeanOfType(
					beanFactory, TransactionManager.class, qualifier);
			this.transactionManagerCache.putIfAbsent(qualifier, txManager);
		}
		return txManager;
	}

	private @Nullable PlatformTransactionManager asPlatformTransactionManager(@Nullable Object transactionManager) {
		if (transactionManager == null) {
			return null;
		}
		if (transactionManager instanceof PlatformTransactionManager ptm) {
			return ptm;
		}
		else {
			throw new IllegalStateException(
					"Specified transaction manager is not a PlatformTransactionManager: " + transactionManager);
		}
	}

	private String methodIdentification(Method method, @Nullable Class<?> targetClass,
			@Nullable TransactionAttribute txAttr) {

		String methodIdentification = methodIdentification(method, targetClass);
		if (methodIdentification == null) {
			if (txAttr instanceof DefaultTransactionAttribute dta) {
				methodIdentification = dta.getDescriptor();
			}
			if (methodIdentification == null) {
				methodIdentification = ClassUtils.getQualifiedMethodName(method, targetClass);
			}
		}
		return methodIdentification;
	}

	/**
	 * Convenience method to return a String representation of this Method
	 * for use in logging. Can be overridden in subclasses to provide a
	 * different identifier for the given method.
	 * <p>The default implementation returns {@code null}, indicating the
	 * use of {@link DefaultTransactionAttribute#getDescriptor()} instead,
	 * ending up as {@link ClassUtils#getQualifiedMethodName(Method, Class)}.
	 * @param method the method we're interested in
	 * @param targetClass the class that the method is being invoked on
	 * @return a String representation identifying this method
	 * @see org.springframework.util.ClassUtils#getQualifiedMethodName
	 */
	protected @Nullable String methodIdentification(Method method, @Nullable Class<?> targetClass) {
		return null;
	}

	/**
	 * Create a transaction if necessary based on the given TransactionAttribute.
	 * <p>Allows callers to perform custom TransactionAttribute lookups through
	 * the TransactionAttributeSource.
	 * @param txAttr the TransactionAttribute (may be {@code null})
	 * @param joinpointIdentification the fully qualified method name
	 * (used for monitoring and logging purposes)
	 * @return a TransactionInfo object, whether a transaction was created.
	 * The {@code hasTransaction()} method on TransactionInfo can be used to
	 * tell if there was a transaction created.
	 * @see #getTransactionAttributeSource()
	 */
	@SuppressWarnings("serial")
	protected TransactionInfo createTransactionIfNecessary(@Nullable PlatformTransactionManager tm,
			@Nullable TransactionAttribute txAttr, final String joinpointIdentification) {

		// If no name specified, apply method identification as transaction name.
		if (txAttr != null && txAttr.getName() == null) {
			txAttr = new DelegatingTransactionAttribute(txAttr) {
				@Override
				public String getName() {
					return joinpointIdentification;
				}
			};
		}

		TransactionStatus status = null;
		if (txAttr != null) {
			if (tm != null) {
				status = tm.getTransaction(txAttr);
			}
			else {
				if (logger.isDebugEnabled()) {
					logger.debug("Skipping transactional joinpoint [" + joinpointIdentification +
							"] because no transaction manager has been configured");
				}
			}
		}
		return prepareTransactionInfo(tm, txAttr, joinpointIdentification, status);
	}

	/**
	 * Prepare a TransactionInfo for the given attribute and status object.
	 * @param txAttr the TransactionAttribute (may be {@code null})
	 * @param joinpointIdentification the fully qualified method name
	 * (used for monitoring and logging purposes)
	 * @param status the TransactionStatus for the current transaction
	 * @return the prepared TransactionInfo object
	 */
	protected TransactionInfo prepareTransactionInfo(@Nullable PlatformTransactionManager tm,
			@Nullable TransactionAttribute txAttr, String joinpointIdentification,
			@Nullable TransactionStatus status) {

		TransactionInfo txInfo = new TransactionInfo(tm, txAttr, joinpointIdentification);
		if (txAttr != null) {
			// We need a transaction for this method...
			if (logger.isTraceEnabled()) {
				logger.trace("Getting transaction for [" + txInfo.getJoinpointIdentification() + "]");
			}
			// The transaction manager will flag an error if an incompatible tx already exists.
			txInfo.newTransactionStatus(status);
		}
		else {
			// The TransactionInfo.hasTransaction() method will return false. We created it only
			// to preserve the integrity of the ThreadLocal stack maintained in this class.
			if (logger.isTraceEnabled()) {
				logger.trace("No need to create transaction for [" + joinpointIdentification +
						"]: This method is not transactional.");
			}
		}

		// We always bind the TransactionInfo to the thread, even if we didn't create
		// a new transaction here. This guarantees that the TransactionInfo stack
		// will be managed correctly even if no transaction was created by this aspect.
		txInfo.bindToThread();
		return txInfo;
	}

	/**
	 * Execute after successful completion of call, but not after an exception was handled.
	 * Do nothing if we didn't create a transaction.
	 * @param txInfo information about the current transaction
	 */
	protected void commitTransactionAfterReturning(@Nullable TransactionInfo txInfo) {
		if (txInfo != null && txInfo.getTransactionStatus() != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Completing transaction for [" + txInfo.getJoinpointIdentification() + "]");
			}
			txInfo.getTransactionManager().commit(txInfo.getTransactionStatus());
		}
	}

	/**
	 * Handle a throwable, completing the transaction.
	 * We may commit or roll back, depending on the configuration.
	 * @param txInfo information about the current transaction
	 * @param ex throwable encountered
	 */
	protected void completeTransactionAfterThrowing(@Nullable TransactionInfo txInfo, Throwable ex) {
		if (txInfo != null && txInfo.getTransactionStatus() != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Completing transaction for [" + txInfo.getJoinpointIdentification() +
						"] after exception: " + ex);
			}
			if (txInfo.transactionAttribute != null && txInfo.transactionAttribute.rollbackOn(ex)) {
				try {
					txInfo.getTransactionManager().rollback(txInfo.getTransactionStatus());
				}
				catch (TransactionSystemException ex2) {
					logger.error("Application exception overridden by rollback exception", ex);
					ex2.initApplicationException(ex);
					throw ex2;
				}
				catch (RuntimeException | Error ex2) {
					logger.error("Application exception overridden by rollback exception", ex);
					throw ex2;
				}
			}
			else {
				// We don't roll back on this exception.
				// Will still roll back if TransactionStatus.isRollbackOnly() is true.
				try {
					txInfo.getTransactionManager().commit(txInfo.getTransactionStatus());
				}
				catch (TransactionSystemException ex2) {
					logger.error("Application exception overridden by commit exception", ex);
					ex2.initApplicationException(ex);
					throw ex2;
				}
				catch (RuntimeException | Error ex2) {
					logger.error("Application exception overridden by commit exception", ex);
					throw ex2;
				}
			}
		}
	}

	/**
	 * Reset the TransactionInfo ThreadLocal.
	 * <p>Call this in all cases: exception or normal return!
	 * @param txInfo information about the current transaction (may be {@code null})
	 */
	protected void cleanupTransactionInfo(@Nullable TransactionInfo txInfo) {
		if (txInfo != null) {
			txInfo.restoreThreadLocalStatus();
		}
	}


	/**
	 * Opaque object used to hold transaction information. Subclasses
	 * must pass it back to methods on this class, but not see its internals.
	 */
	protected static final class TransactionInfo {

		private final @Nullable PlatformTransactionManager transactionManager;

		private final @Nullable TransactionAttribute transactionAttribute;

		private final String joinpointIdentification;

		private @Nullable TransactionStatus transactionStatus;

		private @Nullable TransactionInfo oldTransactionInfo;

		public TransactionInfo(@Nullable PlatformTransactionManager transactionManager,
				@Nullable TransactionAttribute transactionAttribute, String joinpointIdentification) {

			this.transactionManager = transactionManager;
			this.transactionAttribute = transactionAttribute;
			this.joinpointIdentification = joinpointIdentification;
		}

		public PlatformTransactionManager getTransactionManager() {
			Assert.state(this.transactionManager != null, "No PlatformTransactionManager set");
			return this.transactionManager;
		}

		public @Nullable TransactionAttribute getTransactionAttribute() {
			return this.transactionAttribute;
		}

		/**
		 * Return a String representation of this joinpoint (usually a Method call)
		 * for use in logging.
		 */
		public String getJoinpointIdentification() {
			return this.joinpointIdentification;
		}

		public void newTransactionStatus(@Nullable TransactionStatus status) {
			this.transactionStatus = status;
		}

		public @Nullable TransactionStatus getTransactionStatus() {
			return this.transactionStatus;
		}

		/**
		 * Return whether a transaction was created by this aspect,
		 * or whether we just have a placeholder to keep ThreadLocal stack integrity.
		 */
		public boolean hasTransaction() {
			return (this.transactionStatus != null);
		}

		private void bindToThread() {
			// Expose current TransactionStatus, preserving any existing TransactionStatus
			// for restoration after this transaction is complete.
			this.oldTransactionInfo = transactionInfoHolder.get();
			transactionInfoHolder.set(this);
		}

		private void restoreThreadLocalStatus() {
			// Use stack to restore old transaction TransactionInfo.
			// Will be null if none was set.
			transactionInfoHolder.set(this.oldTransactionInfo);
		}

		@Override
		public String toString() {
			return (this.transactionAttribute != null ? this.transactionAttribute.toString() : "No transaction");
		}
	}


	/**
	 * Simple callback interface for proceeding with the target invocation.
	 * Concrete interceptors/aspects adapt this to their invocation mechanism.
	 */
	@FunctionalInterface
	protected interface InvocationCallback {

		@Nullable Object proceedWithInvocation() throws Throwable;
	}


	/**
	 * Internal holder class for a Throwable in a callback transaction model.
	 */
	private static class ThrowableHolder {

		public @Nullable Throwable throwable;
	}


	/**
	 * Internal holder class for a Throwable, used as a RuntimeException to be
	 * thrown from a TransactionCallback (and subsequently unwrapped again).
	 */
	@SuppressWarnings("serial")
	private static class ThrowableHolderException extends RuntimeException {

		public ThrowableHolderException(Throwable throwable) {
			super(throwable);
		}

		@Override
		public String toString() {
			Throwable cause = getCause();
			Assert.state(cause != null, "Cause must not be null");
			return cause.toString();
		}
	}


	/**
	 * Inner class to avoid a hard dependency on the Vavr library at runtime.
	 */
	private static class VavrDelegate {

		public static boolean isVavrTry(Object retVal) {
			return (retVal instanceof Try);
		}

		public static Object evaluateTryFailure(Object retVal, TransactionAttribute txAttr, TransactionStatus status) {
			return ((Try<?>) retVal).onFailure(ex -> {
				if (txAttr.rollbackOn(ex)) {
					status.setRollbackOnly();
				}
			});
		}
	}


	/**
	 * Delegate for Reactor-based management of transactional methods with a
	 * reactive return type.
	 */
	private class ReactiveTransactionSupport {

		private final ReactiveAdapter adapter;

		public ReactiveTransactionSupport(ReactiveAdapter adapter) {
			this.adapter = adapter;
		}

		public Object invokeWithinTransaction(Method method, @Nullable Class<?> targetClass,
				InvocationCallback invocation, @Nullable TransactionAttribute txAttr, ReactiveTransactionManager rtm) {

			String joinpointIdentification = methodIdentification(method, targetClass, txAttr);

			// For Mono and suspending functions not returning kotlinx.coroutines.flow.Flow
			if (Mono.class.isAssignableFrom(method.getReturnType()) || (KotlinDetector.isSuspendingFunction(method) &&
					!COROUTINES_FLOW_CLASS_NAME.equals(new MethodParameter(method, -1).getParameterType().getName()))) {

				return TransactionContextManager.currentContext().flatMap(context ->
							Mono.<Object, ReactiveTransactionInfo>usingWhen(
								createTransactionIfNecessary(rtm, txAttr, joinpointIdentification),
								tx -> {
									try {
										return (Mono<?>) invocation.proceedWithInvocation();
									}
									catch (Throwable ex) {
										return Mono.error(ex);
									}
								},
								this::commitTransactionAfterReturning,
								this::completeTransactionAfterThrowing,
								this::rollbackTransactionOnCancel)
							.onErrorMap(this::unwrapIfResourceCleanupFailure))
						.contextWrite(TransactionContextManager.getOrCreateContext())
						.contextWrite(TransactionContextManager.getOrCreateContextHolder());
			}

			// Any other reactive type, typically a Flux
			return this.adapter.fromPublisher(TransactionContextManager.currentContext().flatMapMany(context ->
						Flux.usingWhen(
							createTransactionIfNecessary(rtm, txAttr, joinpointIdentification),
							tx -> {
								try {
									return this.adapter.toPublisher(invocation.proceedWithInvocation());
								}
								catch (Throwable ex) {
									return Mono.error(ex);
								}
							},
							this::commitTransactionAfterReturning,
							this::completeTransactionAfterThrowing,
							this::rollbackTransactionOnCancel)
						.onErrorMap(this::unwrapIfResourceCleanupFailure))
					.contextWrite(TransactionContextManager.getOrCreateContext())
					.contextWrite(TransactionContextManager.getOrCreateContextHolder()));
		}

		@SuppressWarnings("serial")
		private Mono<ReactiveTransactionInfo> createTransactionIfNecessary(ReactiveTransactionManager tm,
				@Nullable TransactionAttribute txAttr, final String joinpointIdentification) {

			// If no name specified, apply method identification as transaction name.
			if (txAttr != null && txAttr.getName() == null) {
				txAttr = new DelegatingTransactionAttribute(txAttr) {
					@Override
					public String getName() {
						return joinpointIdentification;
					}
				};
			}

			final TransactionAttribute attrToUse = txAttr;
			Mono<ReactiveTransaction> tx = (attrToUse != null ? tm.getReactiveTransaction(attrToUse) : Mono.empty());
			return tx.map(it -> prepareTransactionInfo(tm, attrToUse, joinpointIdentification, it)).switchIfEmpty(
					Mono.defer(() -> Mono.just(prepareTransactionInfo(tm, attrToUse, joinpointIdentification, null))));
		}

		private ReactiveTransactionInfo prepareTransactionInfo(@Nullable ReactiveTransactionManager tm,
				@Nullable TransactionAttribute txAttr, String joinpointIdentification,
				@Nullable ReactiveTransaction transaction) {

			ReactiveTransactionInfo txInfo = new ReactiveTransactionInfo(tm, txAttr, joinpointIdentification);
			if (txAttr != null) {
				// We need a transaction for this method...
				if (logger.isTraceEnabled()) {
					logger.trace("Getting transaction for [" + txInfo.getJoinpointIdentification() + "]");
				}
				// The transaction manager will flag an error if an incompatible tx already exists.
				txInfo.newReactiveTransaction(transaction);
			}
			else {
				// The TransactionInfo.hasTransaction() method will return false. We created it only
				// to preserve the integrity of the ThreadLocal stack maintained in this class.
				if (logger.isTraceEnabled()) {
					logger.trace("Don't need to create transaction for [" + joinpointIdentification +
							"]: This method isn't transactional.");
				}
			}

			return txInfo;
		}

		private Mono<Void> commitTransactionAfterReturning(@Nullable ReactiveTransactionInfo txInfo) {
			if (txInfo != null && txInfo.getReactiveTransaction() != null) {
				if (logger.isTraceEnabled()) {
					logger.trace("Completing transaction for [" + txInfo.getJoinpointIdentification() + "]");
				}
				return txInfo.getTransactionManager().commit(txInfo.getReactiveTransaction());
			}
			return Mono.empty();
		}

		private Mono<Void> rollbackTransactionOnCancel(@Nullable ReactiveTransactionInfo txInfo) {
			if (txInfo != null && txInfo.getReactiveTransaction() != null) {
				if (logger.isTraceEnabled()) {
					logger.trace("Rolling back transaction for [" + txInfo.getJoinpointIdentification() + "] after cancellation");
				}
				return txInfo.getTransactionManager().rollback(txInfo.getReactiveTransaction());
			}
			return Mono.empty();
		}

		private Mono<Void> completeTransactionAfterThrowing(@Nullable ReactiveTransactionInfo txInfo, Throwable ex) {
			if (txInfo != null && txInfo.getReactiveTransaction() != null) {
				if (logger.isTraceEnabled()) {
					logger.trace("Completing transaction for [" + txInfo.getJoinpointIdentification() +
							"] after exception: " + ex);
				}
				if (txInfo.transactionAttribute != null && txInfo.transactionAttribute.rollbackOn(ex)) {
					return txInfo.getTransactionManager().rollback(txInfo.getReactiveTransaction()).onErrorMap(ex2 -> {
								logger.error("Application exception overridden by rollback exception", ex);
								if (ex2 instanceof TransactionSystemException systemException) {
									systemException.initApplicationException(ex);
								}
								else {
									ex2.addSuppressed(ex);
								}
								return ex2;
							}
					);
				}
				else {
					// We don't roll back on this exception.
					// Will still roll back if TransactionStatus.isRollbackOnly() is true.
					return txInfo.getTransactionManager().commit(txInfo.getReactiveTransaction()).onErrorMap(ex2 -> {
								logger.error("Application exception overridden by commit exception", ex);
								if (ex2 instanceof TransactionSystemException systemException) {
									systemException.initApplicationException(ex);
								}
								else {
									ex2.addSuppressed(ex);
								}
								return ex2;
							}
					);
				}
			}
			return Mono.empty();
		}

		/**
		 * Unwrap the cause of a throwable, if produced by a failure
		 * during the async resource cleanup in {@link Flux#usingWhen}.
		 * @param ex the throwable to try to unwrap
		 */
		private Throwable unwrapIfResourceCleanupFailure(Throwable ex) {
			if (ex instanceof RuntimeException && ex.getCause() != null) {
				String msg = ex.getMessage();
				if (msg != null && msg.startsWith("Async resource cleanup failed")) {
					return ex.getCause();
				}
			}
			return ex;
		}
	}


	/**
	 * Opaque object used to hold transaction information for reactive methods.
	 */
	private static final class ReactiveTransactionInfo {

		private final @Nullable ReactiveTransactionManager transactionManager;

		private final @Nullable TransactionAttribute transactionAttribute;

		private final String joinpointIdentification;

		private @Nullable ReactiveTransaction reactiveTransaction;

		public ReactiveTransactionInfo(@Nullable ReactiveTransactionManager transactionManager,
				@Nullable TransactionAttribute transactionAttribute, String joinpointIdentification) {

			this.transactionManager = transactionManager;
			this.transactionAttribute = transactionAttribute;
			this.joinpointIdentification = joinpointIdentification;
		}

		public ReactiveTransactionManager getTransactionManager() {
			Assert.state(this.transactionManager != null, "No ReactiveTransactionManager set");
			return this.transactionManager;
		}

		public @Nullable TransactionAttribute getTransactionAttribute() {
			return this.transactionAttribute;
		}

		/**
		 * Return a String representation of this joinpoint (usually a Method call)
		 * for use in logging.
		 */
		public String getJoinpointIdentification() {
			return this.joinpointIdentification;
		}

		public void newReactiveTransaction(@Nullable ReactiveTransaction transaction) {
			this.reactiveTransaction = transaction;
		}

		public @Nullable ReactiveTransaction getReactiveTransaction() {
			return this.reactiveTransaction;
		}

		@Override
		public String toString() {
			return (this.transactionAttribute != null ? this.transactionAttribute.toString() : "No transaction");
		}
	}

}
