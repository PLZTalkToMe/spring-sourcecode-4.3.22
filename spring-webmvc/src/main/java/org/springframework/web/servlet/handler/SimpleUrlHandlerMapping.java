/*
 * Copyright 2002-2015 the original author or authors.
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

package org.springframework.web.servlet.handler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.springframework.beans.BeansException;
import org.springframework.util.CollectionUtils;

/**
 * Implementation of the {@link org.springframework.web.servlet.HandlerMapping}
 * interface to map from URLs to request handler beans. Supports both mapping to bean
 * instances and mapping to bean names; the latter is required for non-singleton handlers.
 *
 * <p>The "urlMap" property is suitable for populating the handler map with
 * bean references, e.g. via the map element in XML bean definitions.
 *
 * <p>Mappings to bean names can be set via the "mappings" property, in a form
 * accepted by the {@code java.util.Properties} class, like as follows:<br>
 * {@code
 * /welcome.html=ticketController
 * /show.html=ticketController
 * }<br>
 * The syntax is {@code PATH=HANDLER_BEAN_NAME}.
 * If the path doesn't begin with a slash, one is prepended.
 *
 * <p>Supports direct matches (given "/test" -> registered "/test") and "*"
 * pattern matches (given "/test" -> registered "/t*"). Note that the default
 * is to map within the current servlet mapping if applicable; see the
 * {@link #setAlwaysUseFullPath "alwaysUseFullPath"} property. For details on the
 * pattern options, see the {@link org.springframework.util.AntPathMatcher} javadoc.
 *
 *
 * 在SimpleUrlHandlerMapping中，定义了一个map来持有一些列的映射关系。通过这些在HandlerMapping中定义的
 * 映射关系，即这些Url请求和控制器的对应关系，是Spring MVC应用可以根据HTTP请求确定一个对应的controller。具体
 * 的来说这些映射关系是通过接口类HandlerMapping来封装的，在HandlerMapping接口中定义了一个getHandler方法，
 * 通过这个方法可以获得与Http请求相对应的HandlerExecutionChain，在这个HandlerExecutionChain中，封装了具体的
 * controller对象
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #setMappings
 * @see #setUrlMap
 * @see BeanNameUrlHandlerMapping
 */
public class SimpleUrlHandlerMapping extends AbstractUrlHandlerMapping {

    private final Map<String, Object> urlMap = new LinkedHashMap<String, Object>();


    /**
     * Map URL paths to handler bean names.
     * This is the typical way of configuring this HandlerMapping.
     * <p>Supports direct URL matches and Ant-style pattern matches. For syntax
     * details, see the {@link org.springframework.util.AntPathMatcher} javadoc.
     *
     * @param mappings properties with URLs as keys and bean names as values
     * @see #setUrlMap
     */
    public void setMappings(Properties mappings) {
        CollectionUtils.mergePropertiesIntoMap(mappings, this.urlMap);
    }

    /**
     * Set a Map with URL paths as keys and handler beans (or handler bean names)
     * as values. Convenient for population with bean references.
     * <p>Supports direct URL matches and Ant-style pattern matches. For syntax
     * details, see the {@link org.springframework.util.AntPathMatcher} javadoc.
     *
     * @param urlMap map with URLs as keys and beans as values
     * @see #setMappings
     */
    public void setUrlMap(Map<String, ?> urlMap) {
        this.urlMap.putAll(urlMap);
    }

    /**
     * Allow Map access to the URL path mappings, with the option to add or
     * override specific entries.
     * <p>Useful for specifying entries directly, for example via "urlMap[myKey]".
     * This is particularly useful for adding or overriding entries in child
     * bean definitions.
     */
    public Map<String, ?> getUrlMap() {
        return this.urlMap;
    }


    /**
     * Calls the {@link #registerHandlers} method in addition to the
     * superclass's initialization.
     */
    @Override
    public void initApplicationContext() throws BeansException {
        super.initApplicationContext();
        registerHandlers(this.urlMap);
    }

    /**
     * Register all handlers specified in the URL map for the corresponding paths.
     *
     * @param urlMap Map with URL paths as keys and handler beans or bean names as values
     * @throws BeansException        if a handler couldn't be registered
     * @throws IllegalStateException if there is a conflicting handler registered
     */
    protected void registerHandlers(Map<String, Object> urlMap) throws BeansException {
        if (urlMap.isEmpty()) {
            logger.warn("Neither 'urlMap' nor 'mappings' set on SimpleUrlHandlerMapping");
        } else {
            // 这里对bean的配置进行解析，然后调用基类的registerHandler完成注册
            for (Map.Entry<String, Object> entry : urlMap.entrySet()) {
                String url = entry.getKey();
                Object handler = entry.getValue();
                // Prepend with slash if not already present.
                if (!url.startsWith("/")) {
                    url = "/" + url;
                }
                // Remove whitespace from handler bean name.
                if (handler instanceof String) {
                    handler = ((String) handler).trim();
                }
                registerHandler(url, handler);
            }
        }
    }

}
