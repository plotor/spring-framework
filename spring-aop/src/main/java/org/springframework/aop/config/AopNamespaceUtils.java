/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.aop.config;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.lang.Nullable;
import org.w3c.dom.Element;

/**
 * Utility class for handling registration of auto-proxy creators used internally
 * by the '{@code aop}' namespace tags.
 *
 * <p>Only a single auto-proxy creator should be registered and multiple configuration
 * elements may wish to register different concrete implementations. As such this class
 * delegates to {@link AopConfigUtils} which provides a simple escalation protocol.
 * Callers may request a particular auto-proxy creator and know that creator,
 * <i>or a more capable variant thereof</i>, will be registered as a post-processor.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @see AopConfigUtils
 * @since 2.0
 */
public abstract class AopNamespaceUtils {

    /**
     * The {@code proxy-target-class} attribute as found on AOP-related XML tags.
     */
    public static final String PROXY_TARGET_CLASS_ATTRIBUTE = "proxy-target-class";

    /**
     * The {@code expose-proxy} attribute as found on AOP-related XML tags.
     */
    private static final String EXPOSE_PROXY_ATTRIBUTE = "expose-proxy";

    public static void registerAutoProxyCreatorIfNecessary(
            ParserContext parserContext, Element sourceElement) {

        BeanDefinition beanDefinition = AopConfigUtils.registerAutoProxyCreatorIfNecessary(
                parserContext.getRegistry(), parserContext.extractSource(sourceElement));
        useClassProxyingIfNecessary(parserContext.getRegistry(), sourceElement);
        registerComponentIfNecessary(beanDefinition, parserContext);
    }

    public static void registerAspectJAutoProxyCreatorIfNecessary(
            ParserContext parserContext, Element sourceElement) {

        BeanDefinition beanDefinition = AopConfigUtils.registerAspectJAutoProxyCreatorIfNecessary(
                parserContext.getRegistry(), parserContext.extractSource(sourceElement));
        useClassProxyingIfNecessary(parserContext.getRegistry(), sourceElement);
        registerComponentIfNecessary(beanDefinition, parserContext);
    }

    public static void registerAspectJAnnotationAutoProxyCreatorIfNecessary(ParserContext parserContext, Element sourceElement) {
        // 1. 注册或更新代理创建器 ProxyCreator 的 BeanDefinition 对象
        BeanDefinition beanDefinition = AopConfigUtils.registerAspectJAnnotationAutoProxyCreatorIfNecessary(
                parserContext.getRegistry(), parserContext.extractSource(sourceElement));
        // 2. 获取并处理标签的 proxy-target-class 和 expose-proxy 属性
        useClassProxyingIfNecessary(parserContext.getRegistry(), sourceElement);
        // 3. 注册组件，并发布事件通知
        registerComponentIfNecessary(beanDefinition, parserContext);
    }

    private static void useClassProxyingIfNecessary(BeanDefinitionRegistry registry, @Nullable Element sourceElement) {
        if (sourceElement != null) {
            /*
             * 获取并处理 proxy-target-class 属性：
             * - false 表示使用 java 原生动态代理
             * - true 表示使用 CGLib 动态
             *
             * 但是对于一些没有接口实现的类来说，即使设置为 false 也会使用 CGlib 进行代理
             */
            boolean proxyTargetClass = Boolean.parseBoolean(sourceElement.getAttribute(PROXY_TARGET_CLASS_ATTRIBUTE));
            if (proxyTargetClass) {
                // 为之前注册的 ProxyCreator 添加一个名为 proxyTargetClass 的属性，值为 true
                AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
            }

            /*
             * 获取并处理 expose-proxy 标签，实现对于内部方法调用的 AOP 增强
             */
            boolean exposeProxy = Boolean.parseBoolean(sourceElement.getAttribute(EXPOSE_PROXY_ATTRIBUTE));
            if (exposeProxy) {
                // 为之前注册的 ProxyCreator 添加一个名为 exposeProxy 的属性，值为 true
                AopConfigUtils.forceAutoProxyCreatorToExposeProxy(registry);
            }
        }
    }

    private static void registerComponentIfNecessary(@Nullable BeanDefinition beanDefinition, ParserContext parserContext) {
        if (beanDefinition != null) {
            parserContext.registerComponent(
                    new BeanComponentDefinition(beanDefinition, AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME));
        }
    }

}
