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

package org.springframework.beans.factory.xml;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Default implementation of the {@link BeanDefinitionDocumentReader} interface that
 * reads bean definitions according to the "spring-beans" DTD and XSD format
 * (Spring's default XML bean definition format).
 *
 * <p>The structure, elements, and attribute names of the required XML document
 * are hard-coded in this class. (Of course a transform could be run if necessary
 * to produce this format). {@code <beans>} does not need to be the root
 * element of the XML document: this class will parse all bean definition elements
 * in the XML file, regardless of the actual root element.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Erik Wiersma
 * @since 18.12.2003
 */
public class DefaultBeanDefinitionDocumentReader implements BeanDefinitionDocumentReader {

    public static final String BEAN_ELEMENT = BeanDefinitionParserDelegate.BEAN_ELEMENT;

    public static final String NESTED_BEANS_ELEMENT = "beans";

    public static final String ALIAS_ELEMENT = "alias";

    public static final String NAME_ATTRIBUTE = "name";

    public static final String ALIAS_ATTRIBUTE = "alias";

    public static final String IMPORT_ELEMENT = "import";

    public static final String RESOURCE_ATTRIBUTE = "resource";

    public static final String PROFILE_ATTRIBUTE = "profile";

    protected final Log logger = LogFactory.getLog(this.getClass());

    @Nullable
    private XmlReaderContext readerContext;

    @Nullable
    private BeanDefinitionParserDelegate delegate;

    /**
     * This implementation parses bean definitions according to the "spring-beans" XSD
     * (or DTD, historically).
     * <p>Opens a DOM Document; then initializes the default settings
     * specified at the {@code <beans/>} level; then parses the contained bean definitions.
     */
    @Override
    public void registerBeanDefinitions(Document doc, XmlReaderContext readerContext) {
        this.readerContext = readerContext;
        // 从文档的 ROOT 结点开始解析
        this.doRegisterBeanDefinitions(doc.getDocumentElement());
    }

    /**
     * Return the descriptor for the XML resource that this parser works on.
     */
    protected final XmlReaderContext getReaderContext() {
        Assert.state(this.readerContext != null, "No XmlReaderContext available");
        return this.readerContext;
    }

    /**
     * Invoke the {@link org.springframework.beans.factory.parsing.SourceExtractor}
     * to pull the source metadata from the supplied {@link Element}.
     */
    @Nullable
    protected Object extractSource(Element ele) {
        return this.getReaderContext().extractSource(ele);
    }

    /**
     * Register each bean definition within the given root {@code <beans/>} element.
     */
    @SuppressWarnings("deprecation")  // for Environment.acceptsProfiles(String...)
    protected void doRegisterBeanDefinitions(Element root) {
        // Any nested <beans> elements will cause recursion in this method. In
        // order to propagate and preserve <beans> default-* attributes correctly,
        // keep track of the current (parent) delegate, which may be null. Create
        // the new (child) delegate with a reference to the parent for fallback purposes,
        // then ultimately reset this.delegate back to its original (parent) reference.
        // this behavior emulates a stack of delegates without actually necessitating one.
        BeanDefinitionParserDelegate parent = this.delegate;
        this.delegate = this.createDelegate(this.getReaderContext(), root, parent);

        // 处理 profile 标签（其作用类比 pom.xml 中的 profile）
        if (this.delegate.isDefaultNamespace(root)) {
            String profileSpec = root.getAttribute(PROFILE_ATTRIBUTE);
            if (StringUtils.hasText(profileSpec)) {
                String[] specifiedProfiles = StringUtils.tokenizeToStringArray(
                        profileSpec, BeanDefinitionParserDelegate.MULTI_VALUE_ATTRIBUTE_DELIMITERS);
                // We cannot use Profiles.of(...) since profile expressions are not supported in XML config. See SPR-12458 for details.
                if (!this.getReaderContext().getEnvironment().acceptsProfiles(specifiedProfiles)) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Skipped XML bean definition file due to specified profiles [" + profileSpec +
                                "] not matching: " + this.getReaderContext().getResource());
                    }
                    return;
                }
            }
        }

        // 模板方法，预处理
        this.preProcessXml(root);
        // 解析并注册 BeanDefinition
        this.parseBeanDefinitions(root, this.delegate);
        // 模板方法，后处理
        this.postProcessXml(root);

        this.delegate = parent;
    }

    protected BeanDefinitionParserDelegate createDelegate(
            XmlReaderContext readerContext, Element root, @Nullable BeanDefinitionParserDelegate parentDelegate) {

        BeanDefinitionParserDelegate delegate = new BeanDefinitionParserDelegate(readerContext);
        delegate.initDefaults(root, parentDelegate);
        return delegate;
    }

    /**
     * Parse the elements at the root level in the document:
     * "import", "alias", "bean".
     *
     * @param root the DOM root element of the document
     */
    protected void parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) {
        // 解析默认标签
        if (delegate.isDefaultNamespace(root)) {
            NodeList nl = root.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                Node node = nl.item(i);
                if (node instanceof Element) {
                    Element ele = (Element) node;
                    // 解析默认标签
                    if (delegate.isDefaultNamespace(ele)) {
                        this.parseDefaultElement(ele, delegate);
                    }
                    // 解析自定义标签
                    else {
                        delegate.parseCustomElement(ele);
                    }
                }
            }
        }
        // 解析自定义标签
        else {
            delegate.parseCustomElement(root);
        }
    }

    /**
     * 处理默认标签
     *
     * @param ele
     * @param delegate
     */
    private void parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) {
        // 处理 import 标签，该标签用于引入其它的 xml 配置文件
        if (delegate.nodeNameEquals(ele, IMPORT_ELEMENT)) {
            this.importBeanDefinitionResource(ele);
        }
        // 处理 alias 标签，该标签用于为 bean 配置别名
        else if (delegate.nodeNameEquals(ele, ALIAS_ELEMENT)) {
            this.processAliasRegistration(ele);
        }
        // 处理 bean 标签
        else if (delegate.nodeNameEquals(ele, BEAN_ELEMENT)) {
            this.processBeanDefinition(ele, delegate);
        }
        // 处理 beans 标签，即在 <beans /> 中再嵌套 <beans /> 标签
        else if (delegate.nodeNameEquals(ele, NESTED_BEANS_ELEMENT)) {
            // recurse
            this.doRegisterBeanDefinitions(ele);
        }
    }

    /**
     * Parse an "import" element and load the bean definitions
     * from the given resource into the bean factory.
     */
    protected void importBeanDefinitionResource(Element ele) {
        // 获取 resource 属性，例如 <import resource="xx.xml"/>
        String location = ele.getAttribute(RESOURCE_ATTRIBUTE);
        if (!StringUtils.hasText(location)) {
            this.getReaderContext().error("Resource location must not be empty", ele);
            return;
        }

        // 解析路径中的系统属性，比如可能存在如 ${user.dir} 的占位符
        location = this.getReaderContext().getEnvironment().resolveRequiredPlaceholders(location);

        Set<Resource> actualResources = new LinkedHashSet<>(4);

        // 检测是绝对路径，还是相对路径
        boolean absoluteLocation = false;
        try {
            absoluteLocation = ResourcePatternUtils.isUrl(location) || ResourceUtils.toURI(location).isAbsolute();
        } catch (URISyntaxException ex) {
            // cannot convert to an URI, considering the location relative
            // unless it is the well-known Spring prefix "classpath*:"
        }

        // 绝对路径
        if (absoluteLocation) {
            try {
                // 加载 bean 定义，并返回加载的数目
                int importCount = this.getReaderContext().getReader().loadBeanDefinitions(location, actualResources);
                if (logger.isTraceEnabled()) {
                    logger.trace("Imported " + importCount + " bean definitions from URL location [" + location + "]");
                }
            } catch (BeanDefinitionStoreException ex) {
                this.getReaderContext().error("Failed to import bean definitions from URL location [" + location + "]", ele, ex);
            }
        }
        // 相对路径
        else {
            // No URL -> considering resource location as relative to the current file.
            try {
                int importCount;
                // Resource 存在多个子类，各子类的 createRelative 实现不一样，这里先使用子类的方法尝试解析
                Resource relativeResource = this.getReaderContext().getResource().createRelative(location);
                if (relativeResource.exists()) {
                    // 加载 bean 定义，并返回加载的数目
                    importCount = this.getReaderContext().getReader().loadBeanDefinitions(relativeResource);
                    actualResources.add(relativeResource);
                } else {
                    // 解析不成功，使用默认的解析器 ResourcePatternResolver 进行解析
                    String baseLocation = this.getReaderContext().getResource().getURL().toString();
                    importCount = this.getReaderContext().getReader().loadBeanDefinitions(
                            StringUtils.applyRelativePath(baseLocation, location), actualResources);
                }
                if (logger.isTraceEnabled()) {
                    logger.trace("Imported " + importCount + " bean definitions from relative location [" + location + "]");
                }
            } catch (IOException ex) {
                this.getReaderContext().error("Failed to resolve current resource location", ele, ex);
            } catch (BeanDefinitionStoreException ex) {
                this.getReaderContext().error("Failed to import bean definitions from relative location [" + location + "]", ele, ex);
            }
        }
        Resource[] actResArray = actualResources.toArray(new Resource[0]);
        // 解析完成之后，发布事件通知
        this.getReaderContext().fireImportProcessed(location, actResArray, this.extractSource(ele));
    }

    /**
     * Process the given alias element, registering the alias with the registry.
     */
    protected void processAliasRegistration(Element ele) {
        // 获取 name 属性
        String name = ele.getAttribute(NAME_ATTRIBUTE);
        // 获取 alias 属性
        String alias = ele.getAttribute(ALIAS_ATTRIBUTE);
        boolean valid = true;
        // name 不允许为空
        if (!StringUtils.hasText(name)) {
            this.getReaderContext().error("Name must not be empty", ele);
            valid = false;
        }
        // alias 不允许为空
        if (!StringUtils.hasText(alias)) {
            this.getReaderContext().error("Alias must not be empty", ele);
            valid = false;
        }
        if (valid) {
            try {
                // 注册 alias
                this.getReaderContext().getRegistry().registerAlias(name, alias);
            } catch (Exception ex) {
                this.getReaderContext().error("Failed to register alias '" + alias + "' for bean with name '" + name + "'", ele, ex);
            }
            // 注册完成，发布事件通知
            this.getReaderContext().fireAliasRegistered(name, alias, this.extractSource(ele));
        }
    }

    /**
     * Process the given bean element, parsing the bean definition
     * and registering it with the registry.
     */
    protected void processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) {
        // 1. 解析 bean 元素，包括 id、name、alias 和 class
        BeanDefinitionHolder bdHolder = delegate.parseBeanDefinitionElement(ele);
        if (bdHolder != null) {
            // 2. 如果默认标签下有自定义标签，则进行解析
            bdHolder = delegate.decorateBeanDefinitionIfRequired(ele, bdHolder);
            try {
                // 3. 注册解析得到的 BeanDefinitionHolder
                BeanDefinitionReaderUtils.registerBeanDefinition(bdHolder, this.getReaderContext().getRegistry());
            } catch (BeanDefinitionStoreException ex) {
                this.getReaderContext().error("Failed to register bean definition with name '" + bdHolder.getBeanName() + "'", ele, ex);
            }
            /*
             * 4. 发出响应事件，通知相关监听器这个 bean 定义已经加载完了
             *
             * 这里的实现只是为了扩展，Spring 自己并没有对注册实现做任何逻辑处理
             */
            this.getReaderContext().fireComponentRegistered(new BeanComponentDefinition(bdHolder));
        }
    }

    /**
     * Allow the XML to be extensible by processing any custom element types first,
     * before we start to process the bean definitions. This method is a natural
     * extension point for any other custom pre-processing of the XML.
     * <p>The default implementation is empty. Subclasses can override this method to
     * convert custom elements into standard Spring bean definitions, for example.
     * Implementors have access to the parser's bean definition reader and the
     * underlying XML resource, through the corresponding accessors.
     *
     * @see #getReaderContext()
     */
    protected void preProcessXml(Element root) {
    }

    /**
     * Allow the XML to be extensible by processing any custom element types last,
     * after we finished processing the bean definitions. This method is a natural
     * extension point for any other custom post-processing of the XML.
     * <p>The default implementation is empty. Subclasses can override this method to
     * convert custom elements into standard Spring bean definitions, for example.
     * Implementors have access to the parser's bean definition reader and the
     * underlying XML resource, through the corresponding accessors.
     *
     * @see #getReaderContext()
     */
    protected void postProcessXml(Element root) {
    }

}
