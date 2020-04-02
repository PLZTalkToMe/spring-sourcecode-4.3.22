/*
 * Copyright 2002-2012 the original author or authors.
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

package org.springframework.aop.framework;

/**
 * Delegate interface for a configured AOP proxy, allowing for the creation
 * of actual proxy objects.
 *
 * <p>Out-of-the-box implementations are available for JDK dynamic proxies
 * and for CGLIB proxies, as applied by {@link DefaultAopProxyFactory}.
 *
 * 通过使用AopProxy对象封装target目标对象之后，ProxyFactoryBean的getObject方法得到的对象就不是一个普通的Java对象了，
 * 而是一个AopProxy代理对象，在ProxyFactoryBean中配置的target目标对象，这时已经不会让应用直接调用其方法实现，而是作为
 * AOP实现的一部分，对target目标对象的方法调用，会首先被AopProxy代理对象拦截，对于不同的AopProxy代理对象的生成方式，
 * 会使用不同的拦截回调入口。例如：对于Jdk的AopProxy代理对象，使用的是InvocationHandler中的invoke回调入口；而对于
 * CGLIB的AopProxy代理对象，使用的是设置好的callback回调，这是由CGLIB的使用来决定的。在这些callback回调中，对于AOP的
 * 实现，是通过DynamicAdvisedInterceptor来完成的，而DynamicAdvisedInterceptor的回调入口方法是interceptor方法。
 * 通过这一些列的准备，已经为AOP的横切拦截机制奠定了基础，在这个基础上，AOP的Advisor可以通过AopProxy代理的对象进行拦截，
 * 对需要他进行增强的target目标对象发挥切面的强大威力
 *
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see DefaultAopProxyFactory
 */
public interface AopProxy {

	/**
	 * Create a new proxy object.
	 * <p>Uses the AopProxy's default class loader (if necessary for proxy creation):
	 * usually, the thread context class loader.
	 * @return the new proxy object (never {@code null})
	 * @see Thread#getContextClassLoader()
	 */
	Object getProxy();

	/**
	 * Create a new proxy object.
	 * <p>Uses the given class loader (if necessary for proxy creation).
	 * {@code null} will simply be passed down and thus lead to the low-level
	 * proxy facility's default, which is usually different from the default chosen
	 * by the AopProxy implementation's {@link #getProxy()} method.
	 * @param classLoader the class loader to create the proxy with
	 * (or {@code null} for the low-level proxy facility's default)
	 * @return the new proxy object (never {@code null})
	 */
	Object getProxy(ClassLoader classLoader);

}
