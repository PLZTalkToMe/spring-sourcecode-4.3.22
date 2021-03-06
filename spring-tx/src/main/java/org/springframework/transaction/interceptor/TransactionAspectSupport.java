/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.core.NamedThreadLocal;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.CallbackPreferringPlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.util.ClassUtils;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.StringUtils;

/**
 * Base class for transactional aspects, such as the {@link TransactionInterceptor}
 * or an AspectJ aspect.
 *
 * <p>This enables the underlying Spring transaction infrastructure to be used easily
 * to implement an aspect for any aspect system.
 *
 * <p>Subclasses are responsible for calling methods in this class in the correct order.
 *
 * <p>If no transaction name has been specified in the {@code TransactionAttribute},
 * the exposed name will be the {@code fully-qualified class name + "." + method name}
 * (by default).
 *
 * <p>Uses the <b>Strategy</b> design pattern. A {@code PlatformTransactionManager}
 * implementation will perform the actual transaction management, and a
 * {@code TransactionAttributeSource} is used for determining transaction definitions.
 *
 * <p>A transaction aspect is serializable if its {@code PlatformTransactionManager}
 * and {@code TransactionAttributeSource} are serializable.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Stéphane Nicoll
 * @author Sam Brannen
 * @see #setTransactionManager
 * @see #setTransactionAttributes
 * @see #setTransactionAttributeSource
 * @since 1.1
 */
public abstract class TransactionAspectSupport implements BeanFactoryAware, InitializingBean {

    // NOTE: This class must not implement Serializable because it serves as base
    // class for AspectJ aspects (which are not allowed to implement Serializable)!


    /**
     * Key to use to store the default transaction manager.
     */
    private static final Object DEFAULT_TRANSACTION_MANAGER_KEY = new Object();

    /**
     * Holder to support the {@code currentTransactionStatus()} method,
     * and to support communication between different cooperating advices
     * (e.g. before and after advice) if the aspect involves more than a
     * single method (as will be the case for around advice).
     */
    private static final ThreadLocal<TransactionInfo> transactionInfoHolder =
            new NamedThreadLocal<TransactionInfo>("Current aspect-driven transaction");


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
     *
     * @return TransactionInfo bound to this thread, or {@code null} if none
     * @see TransactionInfo#hasTransaction()
     * @see org.springframework.transaction.support.TransactionSynchronizationManager#isSynchronizationActive()
     * @see org.springframework.transaction.support.TransactionSynchronizationManager#isActualTransactionActive()
     */
    protected static TransactionInfo currentTransactionInfo() throws NoTransactionException {
        return transactionInfoHolder.get();
    }

    /**
     * Return the transaction status of the current method invocation.
     * Mainly intended for code that wants to set the current transaction
     * rollback-only but not throw an application exception.
     *
     * @throws NoTransactionException if the transaction info cannot be found,
     *                                because the method was invoked outside an AOP invocation context
     */
    public static TransactionStatus currentTransactionStatus() throws NoTransactionException {
        TransactionInfo info = currentTransactionInfo();
        if (info == null || info.transactionStatus == null) {
            throw new NoTransactionException("No transaction aspect-managed TransactionStatus in scope");
        }
        return info.transactionStatus;
    }


    protected final Log logger = LogFactory.getLog(getClass());

    private String transactionManagerBeanName;

    private PlatformTransactionManager transactionManager;

    private TransactionAttributeSource transactionAttributeSource;

    private BeanFactory beanFactory;

    private final ConcurrentMap<Object, PlatformTransactionManager> transactionManagerCache =
            new ConcurrentReferenceHashMap<Object, PlatformTransactionManager>(4);


    /**
     * Specify the name of the default transaction manager bean.
     */
    public void setTransactionManagerBeanName(String transactionManagerBeanName) {
        this.transactionManagerBeanName = transactionManagerBeanName;
    }

    /**
     * Return the name of the default transaction manager bean.
     */
    protected final String getTransactionManagerBeanName() {
        return this.transactionManagerBeanName;
    }

    /**
     * Specify the <em>default</em> transaction manager to use to drive transactions.
     * <p>The default transaction manager will be used if a <em>qualifier</em>
     * has not been declared for a given transaction or if an explicit name for the
     * default transaction manager bean has not been specified.
     *
     * @see #setTransactionManagerBeanName
     */
    public void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    /**
     * Return the default transaction manager, or {@code null} if unknown.
     */
    public PlatformTransactionManager getTransactionManager() {
        return this.transactionManager;
    }

    /**
     * Set properties with method names as keys and transaction attribute
     * descriptors (parsed via TransactionAttributeEditor) as values:
     * e.g. key = "myMethod", value = "PROPAGATION_REQUIRED,readOnly".
     * <p>Note: Method names are always applied to the target class,
     * no matter if defined in an interface or the class itself.
     * <p>Internally, a NameMatchTransactionAttributeSource will be
     * created from the given properties.
     *
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
     *
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
     *
     * @see TransactionAttributeSourceEditor
     * @see MethodMapTransactionAttributeSource
     * @see NameMatchTransactionAttributeSource
     * @see org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
     */
    public void setTransactionAttributeSource(TransactionAttributeSource transactionAttributeSource) {
        this.transactionAttributeSource = transactionAttributeSource;
    }

    /**
     * Return the transaction attribute source.
     */
    public TransactionAttributeSource getTransactionAttributeSource() {
        return this.transactionAttributeSource;
    }

    /**
     * Set the BeanFactory to use for retrieving PlatformTransactionManager beans.
     */
    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * Return the BeanFactory to use for retrieving PlatformTransactionManager beans.
     */
    protected final BeanFactory getBeanFactory() {
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
                            "containing a PlatformTransactionManager bean!");
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
     * as well as regular {@link PlatformTransactionManager} implementations.
     *
     * @param method      the Method being invoked
     * @param targetClass the target class that we're invoking the method on
     * @param invocation  the callback to use for proceeding with the target invocation
     * @return the return value of the method, if any
     * @throws Throwable propagated from the target invocation
     */
    protected Object invokeWithinTransaction(Method method, Class<?> targetClass, final InvocationCallback invocation)
            throws Throwable {

        // If the transaction attribute is null, the method is non-transactional.
        // 这里获取事务的属性配置，通过TransactionAttributeSource对象取得
        final TransactionAttribute txAttr = getTransactionAttributeSource().getTransactionAttribute(method, targetClass);
        // 根据TransactionProxyFactoryBean的配置信息，获得具体的事务处理器
        final PlatformTransactionManager tm = determineTransactionManager(txAttr);
        final String joinpointIdentification = methodIdentification(method, targetClass, txAttr);


        // 这里区分不同类型的PlatformTransactionManager，因为他们的调用方式不同，
        // 对于CallbackPreferringPlatformTransactionManager来说，需要对调函数来实现事务的创建和提交
        // 对非CallbackPreferringPlatformTransactionManager来说，不需要通过回调函数来实现事务的创建和提交
        // 想DataSourceTransactionManager就不是CallbackPreferringPlatformTransactionManager，
        // 不需要通过回调的方法是来使用。
        if (txAttr == null || !(tm instanceof CallbackPreferringPlatformTransactionManager)) {
            // Standard transaction demarcation with getTransaction and commit/rollback calls.
            // 这里创建事务，同时把创建事务过程中得到的信息放到TransactionInfo中去
            // TransactionInfo是保存当前事务状态的对象。
            TransactionInfo txInfo = createTransactionIfNecessary(tm, txAttr, joinpointIdentification);
            Object retVal = null;
            try {
                // This is an around advice: Invoke the next interceptor in the chain.
                // This will normally result in a target object being invoked.
                // 这里调用，使得处理沿着拦截器链进行，使得最后目标对象的方法得到调用。
                retVal = invocation.proceedWithInvocation();
            } catch (Throwable ex) {
                // target invocation exception
                // 如果在事务处理的方法中出现了异常，事务处理如何进行需要根据具体的情况考虑回滚或者提交
                completeTransactionAfterThrowing(txInfo, ex);
                throw ex;
            } finally {
                // 这里把与线程绑定的TransactionInfo设置为oldTransactionInfo。
                cleanupTransactionInfo(txInfo);
            }
            // 这里通过事务处理器来来对事务进行提交
            commitTransactionAfterReturning(txInfo);
            return retVal;
        } else {
            final ThrowableHolder throwableHolder = new ThrowableHolder();

            // It's a CallbackPreferringPlatformTransactionManager: pass a TransactionCallback in.
            try {
                // 采用回调的方法来使用事务处理器。
                Object result = ((CallbackPreferringPlatformTransactionManager) tm).execute(txAttr,
                        new TransactionCallback<Object>() {
                            @Override
                            public Object doInTransaction(TransactionStatus status) {
                                TransactionInfo txInfo = prepareTransactionInfo(tm, txAttr, joinpointIdentification, status);
                                try {
                                    return invocation.proceedWithInvocation();
                                } catch (Throwable ex) {
                                    if (txAttr.rollbackOn(ex)) {
                                        // A RuntimeException: will lead to a rollback.
                                        // RuntimeException 会导致事务回滚
                                        if (ex instanceof RuntimeException) {
                                            throw (RuntimeException) ex;
                                        } else {
                                            throw new ThrowableHolderException(ex);
                                        }
                                    } else {
                                        // A normal return value: will lead to a commit.
                                        // 正常的返回，导致事务提交
                                        throwableHolder.throwable = ex;
                                        return null;
                                    }
                                } finally {
                                    cleanupTransactionInfo(txInfo);
                                }
                            }
                        });

                // Check result state: It might indicate a Throwable to rethrow.
                if (throwableHolder.throwable != null) {
                    throw throwableHolder.throwable;
                }
                return result;
            } catch (ThrowableHolderException ex) {
                throw ex.getCause();
            } catch (TransactionSystemException ex2) {
                if (throwableHolder.throwable != null) {
                    logger.error("Application exception overridden by commit exception", throwableHolder.throwable);
                    ex2.initApplicationException(throwableHolder.throwable);
                }
                throw ex2;
            } catch (Throwable ex2) {
                if (throwableHolder.throwable != null) {
                    logger.error("Application exception overridden by commit exception", throwableHolder.throwable);
                }
                throw ex2;
            }
        }
    }

    /**
     * Clear the cache.
     */
    protected void clearTransactionManagerCache() {
        this.transactionManagerCache.clear();
        this.beanFactory = null;
    }

    /**
     * Determine the specific transaction manager to use for the given transaction.
     */
    protected PlatformTransactionManager determineTransactionManager(TransactionAttribute txAttr) {
        // Do not attempt to lookup tx manager if no tx attributes are set
        if (txAttr == null || this.beanFactory == null) {
            return getTransactionManager();
        }
        String qualifier = txAttr.getQualifier();
        if (StringUtils.hasText(qualifier)) {
            return determineQualifiedTransactionManager(qualifier);
        } else if (StringUtils.hasText(this.transactionManagerBeanName)) {
            return determineQualifiedTransactionManager(this.transactionManagerBeanName);
        } else {
            PlatformTransactionManager defaultTransactionManager = getTransactionManager();
            if (defaultTransactionManager == null) {
                defaultTransactionManager = this.transactionManagerCache.get(DEFAULT_TRANSACTION_MANAGER_KEY);
                if (defaultTransactionManager == null) {
                    defaultTransactionManager = this.beanFactory.getBean(PlatformTransactionManager.class);
                    this.transactionManagerCache.putIfAbsent(
                            DEFAULT_TRANSACTION_MANAGER_KEY, defaultTransactionManager);
                }
            }
            return defaultTransactionManager;
        }
    }

    private PlatformTransactionManager determineQualifiedTransactionManager(String qualifier) {
        PlatformTransactionManager txManager = this.transactionManagerCache.get(qualifier);
        if (txManager == null) {
            txManager = BeanFactoryAnnotationUtils.qualifiedBeanOfType(
                    this.beanFactory, PlatformTransactionManager.class, qualifier);
            this.transactionManagerCache.putIfAbsent(qualifier, txManager);
        }
        return txManager;
    }

    private String methodIdentification(Method method, Class<?> targetClass, TransactionAttribute txAttr) {
        String methodIdentification = methodIdentification(method, targetClass);
        if (methodIdentification == null) {
            if (txAttr instanceof DefaultTransactionAttribute) {
                methodIdentification = ((DefaultTransactionAttribute) txAttr).getDescriptor();
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
     *
     * @param method      the method we're interested in
     * @param targetClass the class that the method is being invoked on
     * @return a String representation identifying this method
     * @see org.springframework.util.ClassUtils#getQualifiedMethodName
     */
    protected String methodIdentification(Method method, Class<?> targetClass) {
        return null;
    }

    /**
     * Create a transaction if necessary based on the given TransactionAttribute.
     * <p>Allows callers to perform custom TransactionAttribute lookups through
     * the TransactionAttributeSource.
     *
     * @param txAttr                  the TransactionAttribute (may be {@code null})
     * @param joinpointIdentification the fully qualified method name
     *                                (used for monitoring and logging purposes)
     * @return a TransactionInfo object, whether or not a transaction was created.
     * The {@code hasTransaction()} method on TransactionInfo can be used to
     * tell if there was a transaction created.
     * @see #getTransactionAttributeSource()
     */
    @SuppressWarnings("serial")
    protected TransactionInfo createTransactionIfNecessary(
            PlatformTransactionManager tm, TransactionAttribute txAttr, final String joinpointIdentification) {

        // If no name specified, apply method identification as transaction name.
        // 如果没有指定的名字，使用方法特征来作为事务名
        if (txAttr != null && txAttr.getName() == null) {
            txAttr = new DelegatingTransactionAttribute(txAttr) {
                @Override
                public String getName() {
                    return joinpointIdentification;
                }
            };
        }

        // 这个TransactionStatus封装了事务执行的状态信息
        TransactionStatus status = null;
        if (txAttr != null) {
            if (tm != null) {
                // 这里使用了定义好的事务方法的配置信息
                // 事务创建由事务处理器来完成，同时返回TransactionStatus来记录当前的事务状态，
                // 包括已经创建的事务。
                status = tm.getTransaction(txAttr);
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("Skipping transactional joinpoint [" + joinpointIdentification +
                            "] because no transaction manager has been configured");
                }
            }
        }

        // 准备TransactionInfo， TransactionInfo对象封装了事务处理的配置信息以及
        // TransactionStatus。
        return prepareTransactionInfo(tm, txAttr, joinpointIdentification, status);
    }

    /**
     * Prepare a TransactionInfo for the given attribute and status object.
     *
     * @param txAttr                  the TransactionAttribute (may be {@code null})
     * @param joinpointIdentification the fully qualified method name
     *                                (used for monitoring and logging purposes)
     * @param status                  the TransactionStatus for the current transaction
     * @return the prepared TransactionInfo object
     */
    protected TransactionInfo prepareTransactionInfo(PlatformTransactionManager tm,
                                                     TransactionAttribute txAttr, String joinpointIdentification, TransactionStatus status) {

        TransactionInfo txInfo = new TransactionInfo(tm, txAttr, joinpointIdentification);
        if (txAttr != null) {
            // We need a transaction for this method...
            if (logger.isTraceEnabled()) {
                logger.trace("Getting transaction for [" + txInfo.getJoinpointIdentification() + "]");
            }
            // The transaction manager will flag an error if an incompatible tx already exists.
            // 这里为transactionInfo设置TransactionStatus，这个TransactionStatus很重要，
            // 他持有管理事务处理需要的数据，比如，transaction对象就是由TransactionStatus来持有的。
            txInfo.newTransactionStatus(status);
        } else {
            // The TransactionInfo.hasTransaction() method will return false. We created it only
            // to preserve the integrity of the ThreadLocal stack maintained in this class.
            if (logger.isTraceEnabled())
                logger.trace("Don't need to create transaction for [" + joinpointIdentification +
                        "]: This method isn't transactional.");
        }

        // We always bind the TransactionInfo to the thread, even if we didn't create
        // a new transaction here. This guarantees that the TransactionInfo stack
        // will be managed correctly even if no transaction was created by this aspect.
        // 这里把当前的TransactionInfo和线程绑定，同时在TransactionInfo中由一个变量来保存以前
        // 的TransactionInfo，这样持有了一连串与事务处理相关的TransactionInfo
        // 虽然不一定需要创建新的事务，但是总会在请求事务时创建TransactionInfo。
        txInfo.bindToThread();
        return txInfo;
    }

    /**
     * Execute after successful completion of call, but not after an exception was handled.
     * Do nothing if we didn't create a transaction.
     *
     * @param txInfo information about the current transaction
     */
    protected void commitTransactionAfterReturning(TransactionInfo txInfo) {
        if (txInfo != null && txInfo.hasTransaction()) {
            if (logger.isTraceEnabled()) {
                logger.trace("Completing transaction for [" + txInfo.getJoinpointIdentification() + "]");
            }
            txInfo.getTransactionManager().commit(txInfo.getTransactionStatus());
        }
    }

    /**
     * Handle a throwable, completing the transaction.
     * We may commit or roll back, depending on the configuration.
     *
     * @param txInfo information about the current transaction
     * @param ex     throwable encountered
     */
    protected void completeTransactionAfterThrowing(TransactionInfo txInfo, Throwable ex) {
        if (txInfo != null && txInfo.hasTransaction()) {
            if (logger.isTraceEnabled()) {
                logger.trace("Completing transaction for [" + txInfo.getJoinpointIdentification() +
                        "] after exception: " + ex);
            }
            if (txInfo.transactionAttribute.rollbackOn(ex)) {
                try {
                    txInfo.getTransactionManager().rollback(txInfo.getTransactionStatus());
                } catch (TransactionSystemException ex2) {
                    logger.error("Application exception overridden by rollback exception", ex);
                    ex2.initApplicationException(ex);
                    throw ex2;
                } catch (RuntimeException ex2) {
                    logger.error("Application exception overridden by rollback exception", ex);
                    throw ex2;
                } catch (Error err) {
                    logger.error("Application exception overridden by rollback error", ex);
                    throw err;
                }
            } else {
                // We don't roll back on this exception.
                // Will still roll back if TransactionStatus.isRollbackOnly() is true.
                try {
                    txInfo.getTransactionManager().commit(txInfo.getTransactionStatus());
                } catch (TransactionSystemException ex2) {
                    logger.error("Application exception overridden by commit exception", ex);
                    ex2.initApplicationException(ex);
                    throw ex2;
                } catch (RuntimeException ex2) {
                    logger.error("Application exception overridden by commit exception", ex);
                    throw ex2;
                } catch (Error err) {
                    logger.error("Application exception overridden by commit error", ex);
                    throw err;
                }
            }
        }
    }

    /**
     * Reset the TransactionInfo ThreadLocal.
     * <p>Call this in all cases: exception or normal return!
     *
     * @param txInfo information about the current transaction (may be {@code null})
     */
    protected void cleanupTransactionInfo(TransactionInfo txInfo) {
        if (txInfo != null) {
            txInfo.restoreThreadLocalStatus();
        }
    }


    /**
     * Opaque object used to hold Transaction information. Subclasses
     * must pass it back to methods on this class, but not see its internals.
     */
    protected final class TransactionInfo {

        private final PlatformTransactionManager transactionManager;

        private final TransactionAttribute transactionAttribute;

        private final String joinpointIdentification;

        private TransactionStatus transactionStatus;

        private TransactionInfo oldTransactionInfo;

        public TransactionInfo(PlatformTransactionManager transactionManager,
                               TransactionAttribute transactionAttribute, String joinpointIdentification) {

            this.transactionManager = transactionManager;
            this.transactionAttribute = transactionAttribute;
            this.joinpointIdentification = joinpointIdentification;
        }

        public PlatformTransactionManager getTransactionManager() {
            return this.transactionManager;
        }

        public TransactionAttribute getTransactionAttribute() {
            return this.transactionAttribute;
        }

        /**
         * Return a String representation of this joinpoint (usually a Method call)
         * for use in logging.
         */
        public String getJoinpointIdentification() {
            return this.joinpointIdentification;
        }

        public void newTransactionStatus(TransactionStatus status) {
            this.transactionStatus = status;
        }

        public TransactionStatus getTransactionStatus() {
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
            return this.transactionAttribute.toString();
        }
    }


    /**
     * Simple callback interface for proceeding with the target invocation.
     * Concrete interceptors/aspects adapt this to their invocation mechanism.
     */
    protected interface InvocationCallback {

        Object proceedWithInvocation() throws Throwable;
    }


    /**
     * Internal holder class for a Throwable in a callback transaction model.
     */
    private static class ThrowableHolder {

        public Throwable throwable;
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
            return getCause().toString();
        }
    }

}
