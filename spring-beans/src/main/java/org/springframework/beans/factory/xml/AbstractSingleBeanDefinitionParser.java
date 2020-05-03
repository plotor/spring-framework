/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.beans.factory.xml;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.lang.Nullable;
import org.w3c.dom.Element;

/**
 * Base class for those {@link BeanDefinitionParser} implementations that
 * need to parse and define just a <i>single</i> {@code BeanDefinition}.
 *
 * <p>Extend this parser class when you want to create a single bean definition
 * from an arbitrarily complex XML element. You may wish to consider extending
 * the {@link AbstractSimpleBeanDefinitionParser} when you want to create a
 * single bean definition from a relatively simple custom XML element.
 *
 * <p>The resulting {@code BeanDefinition} will be automatically registered
 * with the {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}.
 * Your job simply is to {@link #doParse parse} the custom XML {@link Element}
 * into a single {@code BeanDefinition}.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Rick Evans
 * @see #getBeanClass
 * @see #getBeanClassName
 * @see #doParse
 * @since 2.0
 */
public abstract class AbstractSingleBeanDefinitionParser extends AbstractBeanDefinitionParser {

    /**
     * Creates a {@link BeanDefinitionBuilder} instance for the
     * {@link #getBeanClass bean Class} and passes it to the
     * {@link #doParse} strategy method.
     *
     * @param element the element that is to be parsed into a single BeanDefinition
     * @param parserContext the object encapsulating the current state of the parsing process
     * @return the BeanDefinition resulting from the parsing of the supplied {@link Element}
     * @throws IllegalStateException if the bean {@link Class} returned from
     *                               {@link #getBeanClass(org.w3c.dom.Element)} is {@code null}
     * @see #doParse
     */
    @Override
    protected final AbstractBeanDefinition parseInternal(Element element, ParserContext parserContext) {
        // 初始化自定义标签实例
        BeanDefinitionBuilder builder = BeanDefinitionBuilder.genericBeanDefinition();
        // 获取并设置 parentName
        String parentName = this.getParentName(element);
        if (parentName != null) {
            builder.getRawBeanDefinition().setParentName(parentName);
        }

        // 调用自定义 BeanDefinitionParser 中的 getBeanClass 方法
        Class<?> beanClass = this.getBeanClass(element);
        if (beanClass != null) {
            builder.getRawBeanDefinition().setBeanClass(beanClass);
        } else {
            // 如果自定义解析器没有重写 getBeanClass 方法，则检查子类是否重写了getBeanClassName 方法
            String beanClassName = this.getBeanClassName(element);
            if (beanClassName != null) {
                builder.getRawBeanDefinition().setBeanClassName(beanClassName);
            }
        }
        builder.getRawBeanDefinition().setSource(parserContext.extractSource(element));

        // 如果当前标签是嵌套的，则继承外围 bean 的 scope 属性
        BeanDefinition containingBd = parserContext.getContainingBeanDefinition();
        if (containingBd != null) {
            // Inner bean definition must receive same scope as containing bean.
            builder.setScope(containingBd.getScope());
        }

        // 解析并设置延迟加载
        if (parserContext.isDefaultLazyInit()) {
            // Default-lazy-init applies to custom bean definitions as well.
            builder.setLazyInit(true);
        }

        // 调用自定义解析器覆盖实现的 doParse 方法进行解析
        this.doParse(element, parserContext, builder);

        // 返回自定义标签的 BeanDefinition 实例
        return builder.getBeanDefinition();
    }

    /**
     * Determine the name for the parent of the currently parsed bean,
     * in case of the current bean being defined as a child bean.
     * <p>The default implementation returns {@code null},
     * indicating a root bean definition.
     *
     * @param element the {@code Element} that is being parsed
     * @return the name of the parent bean for the currently parsed bean,
     * or {@code null} if none
     */
    @Nullable
    protected String getParentName(Element element) {
        return null;
    }

    /**
     * Determine the bean class corresponding to the supplied {@link Element}.
     * <p>Note that, for application classes, it is generally preferable to
     * override {@link #getBeanClassName} instead, in order to avoid a direct
     * dependence on the bean implementation class. The BeanDefinitionParser
     * and its NamespaceHandler can be used within an IDE plugin then, even
     * if the application classes are not available on the plugin's classpath.
     *
     * @param element the {@code Element} that is being parsed
     * @return the {@link Class} of the bean that is being defined via parsing
     * the supplied {@code Element}, or {@code null} if none
     * @see #getBeanClassName
     */
    @Nullable
    protected Class<?> getBeanClass(Element element) {
        return null;
    }

    /**
     * Determine the bean class name corresponding to the supplied {@link Element}.
     *
     * @param element the {@code Element} that is being parsed
     * @return the class name of the bean that is being defined via parsing
     * the supplied {@code Element}, or {@code null} if none
     * @see #getBeanClass
     */
    @Nullable
    protected String getBeanClassName(Element element) {
        return null;
    }

    /**
     * Parse the supplied {@link Element} and populate the supplied
     * {@link BeanDefinitionBuilder} as required.
     * <p>The default implementation delegates to the {@code doParse}
     * version without ParserContext argument.
     *
     * @param element the XML element being parsed
     * @param parserContext the object encapsulating the current state of the parsing process
     * @param builder used to define the {@code BeanDefinition}
     * @see #doParse(Element, BeanDefinitionBuilder)
     */
    protected void doParse(Element element, ParserContext parserContext, BeanDefinitionBuilder builder) {
        this.doParse(element, builder);
    }

    /**
     * Parse the supplied {@link Element} and populate the supplied
     * {@link BeanDefinitionBuilder} as required.
     * <p>The default implementation does nothing.
     *
     * @param element the XML element being parsed
     * @param builder used to define the {@code BeanDefinition}
     */
    protected void doParse(Element element, BeanDefinitionBuilder builder) {
    }

}
