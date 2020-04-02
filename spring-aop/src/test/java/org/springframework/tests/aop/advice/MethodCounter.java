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

package org.springframework.tests.aop.advice;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * Abstract superclass for counting advices etc.
 *
 * @author Rod Johnson
 * @author Chris Beams
 */
@SuppressWarnings("serial")
public class MethodCounter implements Serializable {

	/** Method name --> count, does not understand overloading */
	// 这个HashMap使用来存储方法名和调用次数的键值对
	private HashMap<String, Integer> map = new HashMap<String, Integer>();
	// 所有的调用次数，不管是什么方法名
	private int allCount;

	// CountBeforeAdvice的调用入口
	protected void count(Method m) {
		count(m.getName());
	}

	// 根据目标方法的方法名统计调用次数
	protected void count(String methodName) {
		Integer i = map.get(methodName);
		i = (i != null) ? new Integer(i.intValue() + 1) : new Integer(1);
		map.put(methodName, i);
		++allCount;
	}

	// 根据方法名取得调用次数
	public int getCalls(String methodName) {
		Integer i = map.get(methodName);
		return (i != null ? i.intValue() : 0);
	}

	// 取得所有方法的调用次数
	public int getCalls() {
		return allCount;
	}

	/**
	 * A bit simplistic: just wants the same class.
	 * Doesn't worry about counts.
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object other) {
		return (other != null && other.getClass() == this.getClass());
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
