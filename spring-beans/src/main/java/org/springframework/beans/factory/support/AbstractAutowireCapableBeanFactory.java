/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.beans.factory.support;

import org.apache.commons.logging.Log;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeansException;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyAccessorUtils;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.AutowiredPropertyMarker;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.NamedThreadLocal;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.ReflectionUtils.MethodCallback;
import org.springframework.util.StringUtils;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Abstract bean factory superclass that implements default bean creation,
 * with the full capabilities specified by the {@link RootBeanDefinition} class.
 * Implements the {@link org.springframework.beans.factory.config.AutowireCapableBeanFactory}
 * interface in addition to AbstractBeanFactory's {@link #createBean} method.
 *
 * <p>Provides bean creation (with constructor resolution), property population,
 * wiring (including autowiring), and initialization. Handles runtime bean
 * references, resolves managed collections, calls initialization methods, etc.
 * Supports autowiring constructors, properties by name, and properties by type.
 *
 * <p>The main template method to be implemented by subclasses is
 * {@link #resolveDependency(DependencyDescriptor, String, Set, TypeConverter)},
 * used for autowiring by type. In case of a factory which is capable of searching
 * its bean definitions, matching beans will typically be implemented through such
 * a search. For other factory styles, simplified matching algorithms can be implemented.
 *
 * <p>Note that this class does <i>not</i> assume or implement bean definition
 * registry capabilities. See {@link DefaultListableBeanFactory} for an implementation
 * of the {@link org.springframework.beans.factory.ListableBeanFactory} and
 * {@link BeanDefinitionRegistry} interfaces, which represent the API and SPI
 * view of such a factory, respectively.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Mark Fisher
 * @author Costin Leau
 * @author Chris Beams
 * @author Sam Brannen
 * @author Phillip Webb
 * @see RootBeanDefinition
 * @see DefaultListableBeanFactory
 * @see BeanDefinitionRegistry
 * @since 13.02.2004
 */
public abstract class AbstractAutowireCapableBeanFactory extends AbstractBeanFactory
        implements AutowireCapableBeanFactory {

    /** Strategy for creating bean instances. */
    private InstantiationStrategy instantiationStrategy = new CglibSubclassingInstantiationStrategy();

    /** Resolver strategy for method parameter names. */
    @Nullable
    private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /** Whether to automatically try to resolve circular references between beans. */
    private boolean allowCircularReferences = true;

    /**
     * Whether to resort to injecting a raw bean instance in case of circular reference,
     * even if the injected bean eventually got wrapped.
     */
    private boolean allowRawInjectionDespiteWrapping = false;

    /**
     * Dependency types to ignore on dependency check and autowire, as Set of
     * Class objects: for example, String. Default is none.
     */
    private final Set<Class<?>> ignoredDependencyTypes = new HashSet<>();

    /**
     * Dependency interfaces to ignore on dependency check and autowire, as Set of
     * Class objects. By default, only the BeanFactory interface is ignored.
     */
    private final Set<Class<?>> ignoredDependencyInterfaces = new HashSet<>();

    /**
     * The name of the currently created bean, for implicit dependency registration
     * on getBean etc invocations triggered from a user-specified Supplier callback.
     */
    private final NamedThreadLocal<String> currentlyCreatedBean = new NamedThreadLocal<>("Currently created bean");

    /** Cache of unfinished FactoryBean instances: FactoryBean name to BeanWrapper. */
    private final ConcurrentMap<String, BeanWrapper> factoryBeanInstanceCache = new ConcurrentHashMap<>();

    /** Cache of candidate factory methods per factory class. */
    private final ConcurrentMap<Class<?>, Method[]> factoryMethodCandidateCache = new ConcurrentHashMap<>();

    /** Cache of filtered PropertyDescriptors: bean Class to PropertyDescriptor array. */
    private final ConcurrentMap<Class<?>, PropertyDescriptor[]> filteredPropertyDescriptorsCache =
            new ConcurrentHashMap<>();

    /**
     * Create a new AbstractAutowireCapableBeanFactory.
     */
    public AbstractAutowireCapableBeanFactory() {
        super();
        this.ignoreDependencyInterface(BeanNameAware.class);
        this.ignoreDependencyInterface(BeanFactoryAware.class);
        this.ignoreDependencyInterface(BeanClassLoaderAware.class);
    }

    /**
     * Create a new AbstractAutowireCapableBeanFactory with the given parent.
     *
     * @param parentBeanFactory parent bean factory, or {@code null} if none
     */
    public AbstractAutowireCapableBeanFactory(@Nullable BeanFactory parentBeanFactory) {
        this();
        this.setParentBeanFactory(parentBeanFactory);
    }

    /**
     * Set the instantiation strategy to use for creating bean instances.
     * Default is CglibSubclassingInstantiationStrategy.
     *
     * @see CglibSubclassingInstantiationStrategy
     */
    public void setInstantiationStrategy(InstantiationStrategy instantiationStrategy) {
        this.instantiationStrategy = instantiationStrategy;
    }

    /**
     * Return the instantiation strategy to use for creating bean instances.
     */
    protected InstantiationStrategy getInstantiationStrategy() {
        return this.instantiationStrategy;
    }

    /**
     * Set the ParameterNameDiscoverer to use for resolving method parameter
     * names if needed (e.g. for constructor names).
     * <p>Default is a {@link DefaultParameterNameDiscoverer}.
     */
    public void setParameterNameDiscoverer(@Nullable ParameterNameDiscoverer parameterNameDiscoverer) {
        this.parameterNameDiscoverer = parameterNameDiscoverer;
    }

    /**
     * Return the ParameterNameDiscoverer to use for resolving method parameter
     * names if needed.
     */
    @Nullable
    protected ParameterNameDiscoverer getParameterNameDiscoverer() {
        return this.parameterNameDiscoverer;
    }

    /**
     * Set whether to allow circular references between beans - and automatically
     * try to resolve them.
     * <p>Note that circular reference resolution means that one of the involved beans
     * will receive a reference to another bean that is not fully initialized yet.
     * This can lead to subtle and not-so-subtle side effects on initialization;
     * it does work fine for many scenarios, though.
     * <p>Default is "true". Turn this off to throw an exception when encountering
     * a circular reference, disallowing them completely.
     * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
     * between your beans. Refactor your application logic to have the two beans
     * involved delegate to a third bean that encapsulates their common logic.
     */
    public void setAllowCircularReferences(boolean allowCircularReferences) {
        this.allowCircularReferences = allowCircularReferences;
    }

    /**
     * Set whether to allow the raw injection of a bean instance into some other
     * bean's property, despite the injected bean eventually getting wrapped
     * (for example, through AOP auto-proxying).
     * <p>This will only be used as a last resort in case of a circular reference
     * that cannot be resolved otherwise: essentially, preferring a raw instance
     * getting injected over a failure of the entire bean wiring process.
     * <p>Default is "false", as of Spring 2.0. Turn this on to allow for non-wrapped
     * raw beans injected into some of your references, which was Spring 1.2's
     * (arguably unclean) default behavior.
     * <p><b>NOTE:</b> It is generally recommended to not rely on circular references
     * between your beans, in particular with auto-proxying involved.
     *
     * @see #setAllowCircularReferences
     */
    public void setAllowRawInjectionDespiteWrapping(boolean allowRawInjectionDespiteWrapping) {
        this.allowRawInjectionDespiteWrapping = allowRawInjectionDespiteWrapping;
    }

    /**
     * Ignore the given dependency type for autowiring:
     * for example, String. Default is none.
     */
    public void ignoreDependencyType(Class<?> type) {
        this.ignoredDependencyTypes.add(type);
    }

    /**
     * Ignore the given dependency interface for autowiring.
     * <p>This will typically be used by application contexts to register
     * dependencies that are resolved in other ways, like BeanFactory through
     * BeanFactoryAware or ApplicationContext through ApplicationContextAware.
     * <p>By default, only the BeanFactoryAware interface is ignored.
     * For further types to ignore, invoke this method for each type.
     *
     * @see org.springframework.beans.factory.BeanFactoryAware
     * @see org.springframework.context.ApplicationContextAware
     */
    public void ignoreDependencyInterface(Class<?> ifc) {
        this.ignoredDependencyInterfaces.add(ifc);
    }

    @Override
    public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
        super.copyConfigurationFrom(otherFactory);
        if (otherFactory instanceof AbstractAutowireCapableBeanFactory) {
            AbstractAutowireCapableBeanFactory otherAutowireFactory =
                    (AbstractAutowireCapableBeanFactory) otherFactory;
            this.instantiationStrategy = otherAutowireFactory.instantiationStrategy;
            this.allowCircularReferences = otherAutowireFactory.allowCircularReferences;
            this.ignoredDependencyTypes.addAll(otherAutowireFactory.ignoredDependencyTypes);
            this.ignoredDependencyInterfaces.addAll(otherAutowireFactory.ignoredDependencyInterfaces);
        }
    }

    //-------------------------------------------------------------------------
    // Typical methods for creating and populating external bean instances
    //-------------------------------------------------------------------------

    @Override
    @SuppressWarnings("unchecked")
    public <T> T createBean(Class<T> beanClass) throws BeansException {
        // Use prototype bean definition, to avoid registering bean as dependent bean.
        RootBeanDefinition bd = new RootBeanDefinition(beanClass);
        bd.setScope(SCOPE_PROTOTYPE);
        bd.allowCaching = ClassUtils.isCacheSafe(beanClass, this.getBeanClassLoader());
        return (T) this.createBean(beanClass.getName(), bd, null);
    }

    @Override
    public void autowireBean(Object existingBean) {
        // Use non-singleton bean definition, to avoid registering bean as dependent bean.
        RootBeanDefinition bd = new RootBeanDefinition(ClassUtils.getUserClass(existingBean));
        bd.setScope(SCOPE_PROTOTYPE);
        bd.allowCaching = ClassUtils.isCacheSafe(bd.getBeanClass(), this.getBeanClassLoader());
        BeanWrapper bw = new BeanWrapperImpl(existingBean);
        this.initBeanWrapper(bw);
        this.populateBean(bd.getBeanClass().getName(), bd, bw);
    }

    @Override
    public Object configureBean(Object existingBean, String beanName) throws BeansException {
        this.markBeanAsCreated(beanName);
        BeanDefinition mbd = this.getMergedBeanDefinition(beanName);
        RootBeanDefinition bd = null;
        if (mbd instanceof RootBeanDefinition) {
            RootBeanDefinition rbd = (RootBeanDefinition) mbd;
            bd = (rbd.isPrototype() ? rbd : rbd.cloneBeanDefinition());
        }
        if (bd == null) {
            bd = new RootBeanDefinition(mbd);
        }
        if (!bd.isPrototype()) {
            bd.setScope(SCOPE_PROTOTYPE);
            bd.allowCaching = ClassUtils.isCacheSafe(ClassUtils.getUserClass(existingBean), this.getBeanClassLoader());
        }
        BeanWrapper bw = new BeanWrapperImpl(existingBean);
        this.initBeanWrapper(bw);
        this.populateBean(beanName, bd, bw);
        return this.initializeBean(beanName, existingBean, bd);
    }

    //-------------------------------------------------------------------------
    // Specialized methods for fine-grained control over the bean lifecycle
    //-------------------------------------------------------------------------

    @Override
    public Object createBean(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
        // Use non-singleton bean definition, to avoid registering bean as dependent bean.
        RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
        bd.setScope(SCOPE_PROTOTYPE);
        return this.createBean(beanClass.getName(), bd, null);
    }

    @Override
    public Object autowire(Class<?> beanClass, int autowireMode, boolean dependencyCheck) throws BeansException {
        // Use non-singleton bean definition, to avoid registering bean as dependent bean.
        final RootBeanDefinition bd = new RootBeanDefinition(beanClass, autowireMode, dependencyCheck);
        bd.setScope(SCOPE_PROTOTYPE);
        if (bd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR) {
            return this.autowireConstructor(beanClass.getName(), bd, null, null).getWrappedInstance();
        } else {
            Object bean;
            final BeanFactory parent = this;
            if (System.getSecurityManager() != null) {
                bean = AccessController.doPrivileged((PrivilegedAction<Object>) () ->
                                this.getInstantiationStrategy().instantiate(bd, null, parent),
                        this.getAccessControlContext());
            } else {
                bean = this.getInstantiationStrategy().instantiate(bd, null, parent);
            }
            this.populateBean(beanClass.getName(), bd, new BeanWrapperImpl(bean));
            return bean;
        }
    }

    @Override
    public void autowireBeanProperties(Object existingBean, int autowireMode, boolean dependencyCheck)
            throws BeansException {

        if (autowireMode == AUTOWIRE_CONSTRUCTOR) {
            throw new IllegalArgumentException("AUTOWIRE_CONSTRUCTOR not supported for existing bean instance");
        }
        // Use non-singleton bean definition, to avoid registering bean as dependent bean.
        RootBeanDefinition bd =
                new RootBeanDefinition(ClassUtils.getUserClass(existingBean), autowireMode, dependencyCheck);
        bd.setScope(SCOPE_PROTOTYPE);
        BeanWrapper bw = new BeanWrapperImpl(existingBean);
        this.initBeanWrapper(bw);
        this.populateBean(bd.getBeanClass().getName(), bd, bw);
    }

    @Override
    public void applyBeanPropertyValues(Object existingBean, String beanName) throws BeansException {
        this.markBeanAsCreated(beanName);
        BeanDefinition bd = this.getMergedBeanDefinition(beanName);
        BeanWrapper bw = new BeanWrapperImpl(existingBean);
        this.initBeanWrapper(bw);
        this.applyPropertyValues(beanName, bd, bw, bd.getPropertyValues());
    }

    @Override
    public Object initializeBean(Object existingBean, String beanName) {
        return this.initializeBean(beanName, existingBean, null);
    }

    @Override
    public Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName)
            throws BeansException {

        Object result = existingBean;
        for (BeanPostProcessor processor : this.getBeanPostProcessors()) {
            Object current = processor.postProcessBeforeInitialization(result, beanName);
            if (current == null) {
                return result;
            }
            result = current;
        }
        return result;
    }

    @Override
    public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName) throws BeansException {
        Object result = existingBean;
        // 遍历应用注册的 InstantiationAwareBeanPostProcessor 的 postProcessAfterInitialization 方法
        for (BeanPostProcessor processor : this.getBeanPostProcessors()) {
            Object current = processor.postProcessAfterInitialization(result, beanName);
            if (current == null) {
                return result;
            }
            result = current;
        }
        return result;
    }

    @Override
    public void destroyBean(Object existingBean) {
        new DisposableBeanAdapter(existingBean, this.getBeanPostProcessors(), this.getAccessControlContext()).destroy();
    }

    //-------------------------------------------------------------------------
    // Delegate methods for resolving injection points
    //-------------------------------------------------------------------------

    @Override
    public Object resolveBeanByName(String name, DependencyDescriptor descriptor) {
        InjectionPoint previousInjectionPoint = ConstructorResolver.setCurrentInjectionPoint(descriptor);
        try {
            return this.getBean(name, descriptor.getDependencyType());
        } finally {
            ConstructorResolver.setCurrentInjectionPoint(previousInjectionPoint);
        }
    }

    @Override
    @Nullable
    public Object resolveDependency(DependencyDescriptor descriptor, @Nullable String requestingBeanName) throws BeansException {
        return this.resolveDependency(descriptor, requestingBeanName, null, null);
    }

    //---------------------------------------------------------------------
    // Implementation of relevant AbstractBeanFactory template methods
    //---------------------------------------------------------------------

    /**
     * Central method of this class: creates a bean instance,
     * populates the bean instance, applies post-processors, etc.
     *
     * @see #doCreateBean
     */
    @Override
    protected Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object[] args)
            throws BeanCreationException {

        if (logger.isTraceEnabled()) {
            logger.trace("Creating instance of bean '" + beanName + "'");
        }
        RootBeanDefinition mbdToUse = mbd;

        // 1.根据设置的 class 属性或 className 解析得到对应的 Class 引用
        Class<?> resolvedClass = this.resolveBeanClass(mbd, beanName);
        if (resolvedClass != null && !mbd.hasBeanClass() && mbd.getBeanClassName() != null) {
            mbdToUse = new RootBeanDefinition(mbd);
            mbdToUse.setBeanClass(resolvedClass);
        }

        // 2.校验 lookup-method 和 replaced-method 标签应用的方法是否存在
        try {
            mbdToUse.prepareMethodOverrides();
        } catch (BeanDefinitionValidationException ex) {
            throw new BeanDefinitionStoreException(
                    mbdToUse.getResourceDescription(), beanName, "Validation of method overrides failed", ex);
        }

        // 3. 应用 InstantiationAwareBeanPostProcessor 处理器
        try {
            // Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
            Object bean = this.resolveBeforeInstantiation(beanName, mbdToUse);
            // 如果在处理器中已经完成了对 bean 的实例化操作，则直接返回
            if (bean != null) {
                return bean;
            }
        } catch (Throwable ex) {
            throw new BeanCreationException(
                    mbdToUse.getResourceDescription(), beanName, "BeanPostProcessor before instantiation of bean failed", ex);
        }

        // 4. 创建 bean 实例
        try {
            Object beanInstance = this.doCreateBean(beanName, mbdToUse, args);
            if (logger.isTraceEnabled()) {
                logger.trace("Finished creating instance of bean '" + beanName + "'");
            }
            return beanInstance;
        } catch (BeanCreationException | ImplicitlyAppearedSingletonException ex) {
            // A previously detected exception with proper bean creation context already,
            // or illegal singleton state to be communicated up to DefaultSingletonBeanRegistry.
            throw ex;
        } catch (Throwable ex) {
            throw new BeanCreationException(
                    mbdToUse.getResourceDescription(), beanName, "Unexpected exception during bean creation", ex);
        }
    }

    /**
     * Actually create the specified bean. Pre-creation processing has already happened
     * at this point, e.g. checking {@code postProcessBeforeInstantiation} callbacks.
     * <p>Differentiates between default bean instantiation, use of a
     * factory method, and autowiring a constructor.
     *
     * @param beanName the name of the bean
     * @param mbd the merged bean definition for the bean
     * @param args explicit arguments to use for constructor or factory method invocation
     * @return a new instance of the bean
     * @throws BeanCreationException if the bean could not be created
     * @see #instantiateBean
     * @see #instantiateUsingFactoryMethod
     * @see #autowireConstructor
     */
    protected Object doCreateBean(final String beanName, final RootBeanDefinition mbd, final @Nullable Object[] args)
            throws BeanCreationException {

        /* 1. 采用合适的方式创建 bean 对象 */

        // 尝试获取对应的 FactoryBean 的 BeanWrapper 对象，如果存在则基于对应的 FactoryBean 创建 bean 对象
        BeanWrapper instanceWrapper = null;
        if (mbd.isSingleton()) {
            instanceWrapper = this.factoryBeanInstanceCache.remove(beanName);
        }

        // 如果对应的 FactoryBean 不存在，则采用适当的策略实例化 bean 对象
        if (instanceWrapper == null) {
            /*
             * 采用一定的策略创建 bean 实例：
             *
             * 1. 如果设置了 instanceSupplier 回调，则基于该 Supplier 获取 bean 对象；
             * 2. 否则，如果指定了工厂方法，则使用工厂方法创建 bean 对象；
             * 3. 否则，调用相应的构造方法创建 bean 对象。
             */
            instanceWrapper = this.createBeanInstance(beanName, mbd, args);
        }
        // 获取 bean 实例
        final Object bean = instanceWrapper.getWrappedInstance();
        // 获取 bean 实例对应的 Class 对象
        Class<?> beanType = instanceWrapper.getWrappedClass();
        if (beanType != NullBean.class) {
            mbd.resolvedTargetType = beanType;
        }

        // 2. 应用 MergedBeanDefinitionPostProcessor 处理器
        synchronized (mbd.postProcessingLock) {
            if (!mbd.postProcessed) {
                try {
                    // 应用 postProcessMergedBeanDefinition 方法，@Autowired 注解即依赖此处理器实现
                    this.applyMergedBeanDefinitionPostProcessors(mbd, beanType, beanName);
                } catch (Throwable ex) {
                    throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                            "Post-processing of merged bean definition failed", ex);
                }
                mbd.postProcessed = true;
            }
        }

        // 3. 检查是否需要提前曝光 bean 实例，用于解决循环依赖
        boolean earlySingletonExposure = (mbd.isSingleton() // 单例
                && this.allowCircularReferences // 允许自动解决循环依赖
                && this.isSingletonCurrentlyInCreation(beanName)); // 当前 bean 正在创建中
        if (earlySingletonExposure) {
            if (logger.isTraceEnabled()) {
                logger.trace("Eagerly caching bean '" + beanName + "' to allow for resolving potential circular references");
            }
            // 为避免循环依赖，在完成 bean 实例化之前，将对应的 ObjectFactory 注册到容器中
            this.addSingletonFactory(beanName,
                    // 获取 bean 的提前引用
                    () -> this.getEarlyBeanReference(beanName, mbd, bean));
        }

        // 4. 初始化 bean 实例
        Object exposedObject = bean;
        try {
            // 对 bean 进行填充，注入各个属性值，如果存在依赖的 bean 则递归初始化
            this.populateBean(beanName, mbd, instanceWrapper);
            // 初始化 bean，调用初始化方法，比如 init-method
            exposedObject = this.initializeBean(beanName, exposedObject, mbd);
        } catch (Throwable ex) {
            if (ex instanceof BeanCreationException && beanName.equals(((BeanCreationException) ex).getBeanName())) {
                throw (BeanCreationException) ex;
            } else {
                throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Initialization of bean failed", ex);
            }
        }

        // 5. 基于依赖关系验证是否存在循环依赖
        if (earlySingletonExposure) {
            // 获取 bean 实例
            Object earlySingletonReference = this.getSingleton(beanName, false);
            if (earlySingletonReference != null) {
                if (exposedObject == bean) {
                    exposedObject = earlySingletonReference;
                }
                // 如果不允许注入 raw bean 实例 && 存在依赖的 bean
                else if (!this.allowRawInjectionDespiteWrapping && this.hasDependentBean(beanName)) {
                    // 获取依赖的 bean 的 beanName 集合
                    String[] dependentBeans = this.getDependentBeans(beanName);
                    Set<String> actualDependentBeans = new LinkedHashSet<>(dependentBeans.length);
                    // 遍历逐个检测依赖的 bean 实例，记录未完成创建的 bean 实例
                    for (String dependentBean : dependentBeans) {
                        if (!this.removeSingletonIfCreatedForTypeCheckOnly(dependentBean)) {
                            actualDependentBeans.add(dependentBean);
                        }
                    }
                    /*
                     * 因为 bean 在实例化完成之后，其依赖的 bean 实例一定也是完成实例化的，
                     * 如果 actualDependentBeans 不为空，则说明依赖的 bean 实例没有完成创建，存在循环依赖
                     */
                    if (!actualDependentBeans.isEmpty()) {
                        throw new BeanCurrentlyInCreationException(beanName,
                                "Bean with name '" + beanName + "' has been injected into other beans [" +
                                        StringUtils.collectionToCommaDelimitedString(actualDependentBeans) +
                                        "] in its raw version as part of a circular reference, but has eventually been " +
                                        "wrapped. This means that said other beans do not use the final version of the " +
                                        "bean. This is often the result of over-eager type matching - consider using " +
                                        "'getBeanNamesOfType' with the 'allowEagerInit' flag turned off, for example.");
                    }
                }
            }
        }

        // 6. 注册销毁机制
        try {
            this.registerDisposableBeanIfNecessary(beanName, bean, mbd);
        } catch (BeanDefinitionValidationException ex) {
            throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Invalid destruction signature", ex);
        }

        return exposedObject;
    }

    @Override
    @Nullable
    protected Class<?> predictBeanType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
        Class<?> targetType = this.determineTargetType(beanName, mbd, typesToMatch);
        // Apply SmartInstantiationAwareBeanPostProcessors to predict the
        // eventual type after a before-instantiation shortcut.
        if (targetType != null && !mbd.isSynthetic() && this.hasInstantiationAwareBeanPostProcessors()) {
            boolean matchingOnlyFactoryBean = typesToMatch.length == 1 && typesToMatch[0] == FactoryBean.class;
            for (BeanPostProcessor bp : this.getBeanPostProcessors()) {
                if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
                    SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
                    Class<?> predicted = ibp.predictBeanType(targetType, beanName);
                    if (predicted != null &&
                            (!matchingOnlyFactoryBean || FactoryBean.class.isAssignableFrom(predicted))) {
                        return predicted;
                    }
                }
            }
        }
        return targetType;
    }

    /**
     * Determine the target type for the given bean definition.
     *
     * @param beanName the name of the bean (for error handling purposes)
     * @param mbd the merged bean definition for the bean
     * @param typesToMatch the types to match in case of internal type matching purposes
     * (also signals that the returned {@code Class} will never be exposed to application code)
     * @return the type for the bean if determinable, or {@code null} otherwise
     */
    @Nullable
    protected Class<?> determineTargetType(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
        Class<?> targetType = mbd.getTargetType();
        if (targetType == null) {
            targetType = (mbd.getFactoryMethodName() != null ?
                    this.getTypeForFactoryMethod(beanName, mbd, typesToMatch) :
                    this.resolveBeanClass(mbd, beanName, typesToMatch));
            if (ObjectUtils.isEmpty(typesToMatch) || this.getTempClassLoader() == null) {
                mbd.resolvedTargetType = targetType;
            }
        }
        return targetType;
    }

    /**
     * Determine the target type for the given bean definition which is based on
     * a factory method. Only called if there is no singleton instance registered
     * for the target bean already.
     * <p>This implementation determines the type matching {@link #createBean}'s
     * different creation strategies. As far as possible, we'll perform static
     * type checking to avoid creation of the target bean.
     *
     * @param beanName the name of the bean (for error handling purposes)
     * @param mbd the merged bean definition for the bean
     * @param typesToMatch the types to match in case of internal type matching purposes
     * (also signals that the returned {@code Class} will never be exposed to application code)
     * @return the type for the bean if determinable, or {@code null} otherwise
     * @see #createBean
     */
    @Nullable
    protected Class<?> getTypeForFactoryMethod(String beanName, RootBeanDefinition mbd, Class<?>... typesToMatch) {
        ResolvableType cachedReturnType = mbd.factoryMethodReturnType;
        if (cachedReturnType != null) {
            return cachedReturnType.resolve();
        }

        Class<?> commonType = null;
        Method uniqueCandidate = mbd.factoryMethodToIntrospect;

        if (uniqueCandidate == null) {
            Class<?> factoryClass;
            boolean isStatic = true;

            String factoryBeanName = mbd.getFactoryBeanName();
            if (factoryBeanName != null) {
                if (factoryBeanName.equals(beanName)) {
                    throw new BeanDefinitionStoreException(mbd.getResourceDescription(), beanName,
                            "factory-bean reference points back to the same bean definition");
                }
                // Check declared factory method return type on factory class.
                factoryClass = this.getType(factoryBeanName);
                isStatic = false;
            } else {
                // Check declared factory method return type on bean class.
                factoryClass = this.resolveBeanClass(mbd, beanName, typesToMatch);
            }

            if (factoryClass == null) {
                return null;
            }
            factoryClass = ClassUtils.getUserClass(factoryClass);

            // If all factory methods have the same return type, return that type.
            // Can't clearly figure out exact method due to type converting / autowiring!
            int minNrOfArgs =
                    (mbd.hasConstructorArgumentValues() ? mbd.getConstructorArgumentValues().getArgumentCount() : 0);
            Method[] candidates = this.factoryMethodCandidateCache.computeIfAbsent(factoryClass,
                    clazz -> ReflectionUtils.getUniqueDeclaredMethods(clazz, ReflectionUtils.USER_DECLARED_METHODS));

            for (Method candidate : candidates) {
                if (Modifier.isStatic(candidate.getModifiers()) == isStatic && mbd.isFactoryMethod(candidate) &&
                        candidate.getParameterCount() >= minNrOfArgs) {
                    // Declared type variables to inspect?
                    if (candidate.getTypeParameters().length > 0) {
                        try {
                            // Fully resolve parameter names and argument values.
                            Class<?>[] paramTypes = candidate.getParameterTypes();
                            String[] paramNames = null;
                            ParameterNameDiscoverer pnd = this.getParameterNameDiscoverer();
                            if (pnd != null) {
                                paramNames = pnd.getParameterNames(candidate);
                            }
                            ConstructorArgumentValues cav = mbd.getConstructorArgumentValues();
                            Set<ConstructorArgumentValues.ValueHolder> usedValueHolders = new HashSet<>(paramTypes.length);
                            Object[] args = new Object[paramTypes.length];
                            for (int i = 0; i < args.length; i++) {
                                ConstructorArgumentValues.ValueHolder valueHolder = cav.getArgumentValue(
                                        i, paramTypes[i], (paramNames != null ? paramNames[i] : null), usedValueHolders);
                                if (valueHolder == null) {
                                    valueHolder = cav.getGenericArgumentValue(null, null, usedValueHolders);
                                }
                                if (valueHolder != null) {
                                    args[i] = valueHolder.getValue();
                                    usedValueHolders.add(valueHolder);
                                }
                            }
                            Class<?> returnType = AutowireUtils.resolveReturnTypeForFactoryMethod(
                                    candidate, args, this.getBeanClassLoader());
                            uniqueCandidate = (commonType == null && returnType == candidate.getReturnType() ?
                                    candidate : null);
                            commonType = ClassUtils.determineCommonAncestor(returnType, commonType);
                            if (commonType == null) {
                                // Ambiguous return types found: return null to indicate "not determinable".
                                return null;
                            }
                        } catch (Throwable ex) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Failed to resolve generic return type for factory method: " + ex);
                            }
                        }
                    } else {
                        uniqueCandidate = (commonType == null ? candidate : null);
                        commonType = ClassUtils.determineCommonAncestor(candidate.getReturnType(), commonType);
                        if (commonType == null) {
                            // Ambiguous return types found: return null to indicate "not determinable".
                            return null;
                        }
                    }
                }
            }

            mbd.factoryMethodToIntrospect = uniqueCandidate;
            if (commonType == null) {
                return null;
            }
        }

        // Common return type found: all factory methods return same type. For a non-parameterized
        // unique candidate, cache the full type declaration context of the target factory method.
        cachedReturnType = (uniqueCandidate != null ?
                ResolvableType.forMethodReturnType(uniqueCandidate) : ResolvableType.forClass(commonType));
        mbd.factoryMethodReturnType = cachedReturnType;
        return cachedReturnType.resolve();
    }

    /**
     * This implementation attempts to query the FactoryBean's generic parameter metadata
     * if present to determine the object type. If not present, i.e. the FactoryBean is
     * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
     * on a plain instance of the FactoryBean, without bean properties applied yet.
     * If this doesn't return a type yet, and {@code allowInit} is {@code true} a
     * full creation of the FactoryBean is used as fallback (through delegation to the
     * superclass's implementation).
     * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
     * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
     * it will be fully created to check the type of its exposed object.
     */
    @Override
    protected ResolvableType getTypeForFactoryBean(String beanName, RootBeanDefinition mbd, boolean allowInit) {
        // Check if the bean definition itself has defined the type with an attribute
        ResolvableType result = this.getTypeForFactoryBeanFromAttributes(mbd);
        if (result != ResolvableType.NONE) {
            return result;
        }

        ResolvableType beanType =
                (mbd.hasBeanClass() ? ResolvableType.forClass(mbd.getBeanClass()) : ResolvableType.NONE);

        // For instance supplied beans try the target type and bean class
        if (mbd.getInstanceSupplier() != null) {
            result = this.getFactoryBeanGeneric(mbd.targetType);
            if (result.resolve() != null) {
                return result;
            }
            result = this.getFactoryBeanGeneric(beanType);
            if (result.resolve() != null) {
                return result;
            }
        }

        // Consider factory methods
        String factoryBeanName = mbd.getFactoryBeanName();
        String factoryMethodName = mbd.getFactoryMethodName();

        // Scan the factory bean methods
        if (factoryBeanName != null) {
            if (factoryMethodName != null) {
                // Try to obtain the FactoryBean's object type from its factory method
                // declaration without instantiating the containing bean at all.
                BeanDefinition factoryBeanDefinition = this.getBeanDefinition(factoryBeanName);
                Class<?> factoryBeanClass;
                if (factoryBeanDefinition instanceof AbstractBeanDefinition &&
                        ((AbstractBeanDefinition) factoryBeanDefinition).hasBeanClass()) {
                    factoryBeanClass = ((AbstractBeanDefinition) factoryBeanDefinition).getBeanClass();
                } else {
                    RootBeanDefinition fbmbd = this.getMergedBeanDefinition(factoryBeanName, factoryBeanDefinition);
                    factoryBeanClass = this.determineTargetType(factoryBeanName, fbmbd);
                }
                if (factoryBeanClass != null) {
                    result = this.getTypeForFactoryBeanFromMethod(factoryBeanClass, factoryMethodName);
                    if (result.resolve() != null) {
                        return result;
                    }
                }
            }
            // If not resolvable above and the referenced factory bean doesn't exist yet,
            // exit here - we don't want to force the creation of another bean just to
            // obtain a FactoryBean's object type...
            if (!this.isBeanEligibleForMetadataCaching(factoryBeanName)) {
                return ResolvableType.NONE;
            }
        }

        // If we're allowed, we can create the factory bean and call getObjectType() early
        if (allowInit) {
            FactoryBean<?> factoryBean = (mbd.isSingleton() ?
                    this.getSingletonFactoryBeanForTypeCheck(beanName, mbd) :
                    this.getNonSingletonFactoryBeanForTypeCheck(beanName, mbd));
            if (factoryBean != null) {
                // Try to obtain the FactoryBean's object type from this early stage of the instance.
                Class<?> type = this.getTypeForFactoryBean(factoryBean);
                if (type != null) {
                    return ResolvableType.forClass(type);
                }
                // No type found for shortcut FactoryBean instance:
                // fall back to full creation of the FactoryBean instance.
                return super.getTypeForFactoryBean(beanName, mbd, true);
            }
        }

        if (factoryBeanName == null && mbd.hasBeanClass() && factoryMethodName != null) {
            // No early bean instantiation possible: determine FactoryBean's type from
            // static factory method signature or from class inheritance hierarchy...
            return this.getTypeForFactoryBeanFromMethod(mbd.getBeanClass(), factoryMethodName);
        }
        result = this.getFactoryBeanGeneric(beanType);
        if (result.resolve() != null) {
            return result;
        }
        return ResolvableType.NONE;
    }

    private ResolvableType getFactoryBeanGeneric(@Nullable ResolvableType type) {
        if (type == null) {
            return ResolvableType.NONE;
        }
        return type.as(FactoryBean.class).getGeneric();
    }

    /**
     * Introspect the factory method signatures on the given bean class,
     * trying to find a common {@code FactoryBean} object type declared there.
     *
     * @param beanClass the bean class to find the factory method on
     * @param factoryMethodName the name of the factory method
     * @return the common {@code FactoryBean} object type, or {@code null} if none
     */
    private ResolvableType getTypeForFactoryBeanFromMethod(Class<?> beanClass, String factoryMethodName) {
        // CGLIB subclass methods hide generic parameters; look at the original user class.
        Class<?> factoryBeanClass = ClassUtils.getUserClass(beanClass);
        FactoryBeanMethodTypeFinder finder = new FactoryBeanMethodTypeFinder(factoryMethodName);
        ReflectionUtils.doWithMethods(factoryBeanClass, finder, ReflectionUtils.USER_DECLARED_METHODS);
        return finder.getResult();
    }

    /**
     * This implementation attempts to query the FactoryBean's generic parameter metadata
     * if present to determine the object type. If not present, i.e. the FactoryBean is
     * declared as a raw type, checks the FactoryBean's {@code getObjectType} method
     * on a plain instance of the FactoryBean, without bean properties applied yet.
     * If this doesn't return a type yet, a full creation of the FactoryBean is
     * used as fallback (through delegation to the superclass's implementation).
     * <p>The shortcut check for a FactoryBean is only applied in case of a singleton
     * FactoryBean. If the FactoryBean instance itself is not kept as singleton,
     * it will be fully created to check the type of its exposed object.
     */
    @Override
    @Deprecated
    @Nullable
    protected Class<?> getTypeForFactoryBean(String beanName, RootBeanDefinition mbd) {
        return this.getTypeForFactoryBean(beanName, mbd, true).resolve();
    }

    /**
     * Obtain a reference for early access to the specified bean,
     * typically for the purpose of resolving a circular reference.
     *
     * @param beanName the name of the bean (for error handling purposes)
     * @param mbd the merged bean definition for the bean
     * @param bean the raw bean instance
     * @return the object to expose as bean reference
     */
    protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
        Object exposedObject = bean;
        if (!mbd.isSynthetic() && this.hasInstantiationAwareBeanPostProcessors()) {
            for (BeanPostProcessor bp : this.getBeanPostProcessors()) {
                if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
                    SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
                    exposedObject = ibp.getEarlyBeanReference(exposedObject, beanName);
                }
            }
        }
        return exposedObject;
    }

    //---------------------------------------------------------------------
    // Implementation methods
    //---------------------------------------------------------------------

    /**
     * Obtain a "shortcut" singleton FactoryBean instance to use for a
     * {@code getObjectType()} call, without full initialization of the FactoryBean.
     *
     * @param beanName the name of the bean
     * @param mbd the bean definition for the bean
     * @return the FactoryBean instance, or {@code null} to indicate
     * that we couldn't obtain a shortcut FactoryBean instance
     */
    @Nullable
    private FactoryBean<?> getSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
        synchronized (this.getSingletonMutex()) {
            BeanWrapper bw = this.factoryBeanInstanceCache.get(beanName);
            if (bw != null) {
                return (FactoryBean<?>) bw.getWrappedInstance();
            }
            Object beanInstance = this.getSingleton(beanName, false);
            if (beanInstance instanceof FactoryBean) {
                return (FactoryBean<?>) beanInstance;
            }
            if (this.isSingletonCurrentlyInCreation(beanName) ||
                    (mbd.getFactoryBeanName() != null && this.isSingletonCurrentlyInCreation(mbd.getFactoryBeanName()))) {
                return null;
            }

            Object instance;
            try {
                // Mark this bean as currently in creation, even if just partially.
                this.beforeSingletonCreation(beanName);
                // Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
                instance = this.resolveBeforeInstantiation(beanName, mbd);
                if (instance == null) {
                    bw = this.createBeanInstance(beanName, mbd, null);
                    instance = bw.getWrappedInstance();
                }
            } catch (UnsatisfiedDependencyException ex) {
                // Don't swallow, probably misconfiguration...
                throw ex;
            } catch (BeanCreationException ex) {
                // Instantiation failure, maybe too early...
                if (logger.isDebugEnabled()) {
                    logger.debug("Bean creation exception on singleton FactoryBean type check: " + ex);
                }
                this.onSuppressedException(ex);
                return null;
            } finally {
                // Finished partial creation of this bean.
                this.afterSingletonCreation(beanName);
            }

            FactoryBean<?> fb = this.getFactoryBean(beanName, instance);
            if (bw != null) {
                this.factoryBeanInstanceCache.put(beanName, bw);
            }
            return fb;
        }
    }

    /**
     * Obtain a "shortcut" non-singleton FactoryBean instance to use for a
     * {@code getObjectType()} call, without full initialization of the FactoryBean.
     *
     * @param beanName the name of the bean
     * @param mbd the bean definition for the bean
     * @return the FactoryBean instance, or {@code null} to indicate
     * that we couldn't obtain a shortcut FactoryBean instance
     */
    @Nullable
    private FactoryBean<?> getNonSingletonFactoryBeanForTypeCheck(String beanName, RootBeanDefinition mbd) {
        if (this.isPrototypeCurrentlyInCreation(beanName)) {
            return null;
        }

        Object instance;
        try {
            // Mark this bean as currently in creation, even if just partially.
            this.beforePrototypeCreation(beanName);
            // Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
            instance = this.resolveBeforeInstantiation(beanName, mbd);
            if (instance == null) {
                BeanWrapper bw = this.createBeanInstance(beanName, mbd, null);
                instance = bw.getWrappedInstance();
            }
        } catch (UnsatisfiedDependencyException ex) {
            // Don't swallow, probably misconfiguration...
            throw ex;
        } catch (BeanCreationException ex) {
            // Instantiation failure, maybe too early...
            if (logger.isDebugEnabled()) {
                logger.debug("Bean creation exception on non-singleton FactoryBean type check: " + ex);
            }
            this.onSuppressedException(ex);
            return null;
        } finally {
            // Finished partial creation of this bean.
            this.afterPrototypeCreation(beanName);
        }

        return this.getFactoryBean(beanName, instance);
    }

    /**
     * Apply MergedBeanDefinitionPostProcessors to the specified bean definition,
     * invoking their {@code postProcessMergedBeanDefinition} methods.
     *
     * @param mbd the merged bean definition for the bean
     * @param beanType the actual type of the managed bean instance
     * @param beanName the name of the bean
     * @see MergedBeanDefinitionPostProcessor#postProcessMergedBeanDefinition
     */
    protected void applyMergedBeanDefinitionPostProcessors(RootBeanDefinition mbd, Class<?> beanType, String beanName) {
        // 获取并遍历所有的后置处理器
        for (BeanPostProcessor bp : this.getBeanPostProcessors()) {
            // 如果是 MergedBeanDefinitionPostProcessor，则进行应用 postProcessMergedBeanDefinition 方法
            if (bp instanceof MergedBeanDefinitionPostProcessor) {
                MergedBeanDefinitionPostProcessor bdp = (MergedBeanDefinitionPostProcessor) bp;
                bdp.postProcessMergedBeanDefinition(mbd, beanType, beanName);
            }
        }
    }

    /**
     * Apply before-instantiation post-processors, resolving whether there is a
     * before-instantiation shortcut for the specified bean.
     *
     * @param beanName the name of the bean
     * @param mbd the bean definition for the bean
     * @return the shortcut-determined bean instance, or {@code null} if none
     */
    @Nullable
    protected Object resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) {
        Object bean = null;
        // 如果 bean 尚未实例化
        if (!Boolean.FALSE.equals(mbd.beforeInstantiationResolved)) {
            // 当前 bean 不是合成的，且注册了 InstantiationAwareBeanPostProcessor
            if (!mbd.isSynthetic() && this.hasInstantiationAwareBeanPostProcessors()) {
                // 获取最终的 Class 引用，如果是工厂方法则获取工厂所创建的实例类型
                Class<?> targetType = this.determineTargetType(beanName, mbd);
                if (targetType != null) {
                    // 应用实例化前置处理
                    bean = this.applyBeanPostProcessorsBeforeInstantiation(targetType, beanName);
                    if (bean != null) {
                        // 应用实例初始化后置处理
                        bean = this.applyBeanPostProcessorsAfterInitialization(bean, beanName);
                    }
                }
            }
            // 标识对于当前 bean 已经应用过该处理器，避免重复应用
            mbd.beforeInstantiationResolved = (bean != null);
        }
        return bean;
    }

    /**
     * Apply InstantiationAwareBeanPostProcessors to the specified bean definition
     * (by class and name), invoking their {@code postProcessBeforeInstantiation} methods.
     * <p>Any returned object will be used as the bean instead of actually instantiating
     * the target bean. A {@code null} return value from the post-processor will
     * result in the target bean being instantiated.
     *
     * @param beanClass the class of the bean to be instantiated
     * @param beanName the name of the bean
     * @return the bean object to use instead of a default instance of the target bean, or {@code null}
     * @see InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation
     */
    @Nullable
    protected Object applyBeanPostProcessorsBeforeInstantiation(Class<?> beanClass, String beanName) {
        // 遍历应用注册的 InstantiationAwareBeanPostProcessor 的 postProcessBeforeInstantiation 方法
        for (BeanPostProcessor bp : this.getBeanPostProcessors()) {
            if (bp instanceof InstantiationAwareBeanPostProcessor) {
                InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
                Object result = ibp.postProcessBeforeInstantiation(beanClass, beanName);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Create a new instance for the specified bean, using an appropriate instantiation strategy:
     * factory method, constructor autowiring, or simple instantiation.
     *
     * @param beanName the name of the bean
     * @param mbd the bean definition for the bean
     * @param args explicit arguments to use for constructor or factory method invocation
     * @return a BeanWrapper for the new instance
     * @see #obtainFromSupplier
     * @see #instantiateUsingFactoryMethod
     * @see #autowireConstructor
     * @see #instantiateBean
     */
    protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, @Nullable Object[] args) {
        // 解析 Class 对象
        Class<?> beanClass = this.resolveBeanClass(mbd, beanName);
        // 不是 public 类，或者对应的构造方法不能访问
        if (beanClass != null && !Modifier.isPublic(beanClass.getModifiers()) && !mbd.isNonPublicAccessAllowed()) {
            throw new BeanCreationException(mbd.getResourceDescription(), beanName,
                    "Bean class isn't public, and non-public access not allowed: " + beanClass.getName());
        }

        // 1. 如果设置了 instanceSupplier，则调用 Supplier#get 获取 bean 实例
        Supplier<?> instanceSupplier = mbd.getInstanceSupplier();
        if (instanceSupplier != null) {
            return this.obtainFromSupplier(instanceSupplier, beanName);
        }

        // 2. 如果指定了工厂方法，则使用工厂方法创建 bean 实例
        if (mbd.getFactoryMethodName() != null) {
            return this.instantiateUsingFactoryMethod(beanName, mbd, args);
        }

        // 3. 解析并确定构造方法版本，调用构造方法创建 bean 实例
        boolean resolved = false;
        boolean autowireNecessary = false;
        if (args == null) {
            synchronized (mbd.constructorArgumentLock) {
                if (mbd.resolvedConstructorOrFactoryMethod != null) {
                    resolved = true;
                    autowireNecessary = mbd.constructorArgumentsResolved;
                }
            }
        }
        // 之前已经解析过构造方法版本，直接复用，避免重复解析
        if (resolved) {
            // 使用之前确定的构造方法版本
            if (autowireNecessary) {
                return this.autowireConstructor(beanName, mbd, null, null);
            }
            // 使用默认构造方法
            else {
                return this.instantiateBean(beanName, mbd);
            }
        }

        // 依据参数决定使用哪个构造方法
        Constructor<?>[] ctors = this.determineConstructorsFromBeanPostProcessors(beanClass, beanName);
        if (ctors != null || mbd.getResolvedAutowireMode() == AUTOWIRE_CONSTRUCTOR
                || mbd.hasConstructorArgumentValues() || !ObjectUtils.isEmpty(args)) {
            return this.autowireConstructor(beanName, mbd, ctors, args);
        }

        // 解析构造方法失败，使用首选的构造方法，如果有指定的话
        ctors = mbd.getPreferredConstructors();
        if (ctors != null) {
            return this.autowireConstructor(beanName, mbd, ctors, null);
        }

        // 使用默认的构造方法
        return this.instantiateBean(beanName, mbd);
    }

    /**
     * Obtain a bean instance from the given supplier.
     *
     * @param instanceSupplier the configured supplier
     * @param beanName the corresponding bean name
     * @return a BeanWrapper for the new instance
     * @see #getObjectForBeanInstance
     * @since 5.0
     */
    protected BeanWrapper obtainFromSupplier(Supplier<?> instanceSupplier, String beanName) {
        Object instance;

        String outerBean = this.currentlyCreatedBean.get();
        this.currentlyCreatedBean.set(beanName);
        try {
            instance = instanceSupplier.get();
        } finally {
            if (outerBean != null) {
                this.currentlyCreatedBean.set(outerBean);
            } else {
                this.currentlyCreatedBean.remove();
            }
        }

        if (instance == null) {
            instance = new NullBean();
        }
        BeanWrapper bw = new BeanWrapperImpl(instance);
        this.initBeanWrapper(bw);
        return bw;
    }

    /**
     * Overridden in order to implicitly register the currently created bean as
     * dependent on further beans getting programmatically retrieved during a
     * {@link Supplier} callback.
     *
     * @see #obtainFromSupplier
     * @since 5.0
     */
    @Override
    protected Object getObjectForBeanInstance(
            Object beanInstance, String name, String beanName, @Nullable RootBeanDefinition mbd) {

        String currentlyCreatedBean = this.currentlyCreatedBean.get();
        if (currentlyCreatedBean != null) {
            this.registerDependentBean(beanName, currentlyCreatedBean);
        }

        return super.getObjectForBeanInstance(beanInstance, name, beanName, mbd);
    }

    /**
     * Determine candidate constructors to use for the given bean, checking all registered
     * {@link SmartInstantiationAwareBeanPostProcessor SmartInstantiationAwareBeanPostProcessors}.
     *
     * @param beanClass the raw class of the bean
     * @param beanName the name of the bean
     * @return the candidate constructors, or {@code null} if none specified
     * @throws org.springframework.beans.BeansException in case of errors
     * @see org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor#determineCandidateConstructors
     */
    @Nullable
    protected Constructor<?>[] determineConstructorsFromBeanPostProcessors(@Nullable Class<?> beanClass, String beanName)
            throws BeansException {

        if (beanClass != null && this.hasInstantiationAwareBeanPostProcessors()) {
            for (BeanPostProcessor bp : this.getBeanPostProcessors()) {
                if (bp instanceof SmartInstantiationAwareBeanPostProcessor) {
                    SmartInstantiationAwareBeanPostProcessor ibp = (SmartInstantiationAwareBeanPostProcessor) bp;
                    Constructor<?>[] ctors = ibp.determineCandidateConstructors(beanClass, beanName);
                    if (ctors != null) {
                        return ctors;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Instantiate the given bean using its default constructor.
     *
     * @param beanName the name of the bean
     * @param mbd the bean definition for the bean
     * @return a BeanWrapper for the new instance
     */
    protected BeanWrapper instantiateBean(final String beanName, final RootBeanDefinition mbd) {
        try {
            Object beanInstance;
            final BeanFactory parent = this;
            if (System.getSecurityManager() != null) {
                beanInstance = AccessController.doPrivileged((PrivilegedAction<Object>) () ->
                                this.getInstantiationStrategy().instantiate(mbd, beanName, parent),
                        this.getAccessControlContext());
            } else {
                beanInstance = this.getInstantiationStrategy().instantiate(mbd, beanName, parent);
            }
            BeanWrapper bw = new BeanWrapperImpl(beanInstance);
            this.initBeanWrapper(bw);
            return bw;
        } catch (Throwable ex) {
            throw new BeanCreationException(
                    mbd.getResourceDescription(), beanName, "Instantiation of bean failed", ex);
        }
    }

    /**
     * Instantiate the bean using a named factory method. The method may be static, if the
     * mbd parameter specifies a class, rather than a factoryBean, or an instance variable
     * on a factory object itself configured using Dependency Injection.
     *
     * @param beanName the name of the bean
     * @param mbd the bean definition for the bean
     * @param explicitArgs argument values passed in programmatically via the getBean method,
     * or {@code null} if none (-> use constructor argument values from bean definition)
     * @return a BeanWrapper for the new instance
     * @see #getBean(String, Object[])
     */
    protected BeanWrapper instantiateUsingFactoryMethod(
            String beanName, RootBeanDefinition mbd, @Nullable Object[] explicitArgs) {
        return new ConstructorResolver(this).instantiateUsingFactoryMethod(beanName, mbd, explicitArgs);
    }

    /**
     * "autowire constructor" (with constructor arguments by type) behavior.
     * Also applied if explicit constructor argument values are specified,
     * matching all remaining arguments with beans from the bean factory.
     * <p>This corresponds to constructor injection: In this mode, a Spring
     * bean factory is able to host components that expect constructor-based
     * dependency resolution.
     *
     * @param beanName the name of the bean
     * @param mbd the bean definition for the bean
     * @param ctors the chosen candidate constructors
     * @param explicitArgs argument values passed in programmatically via the getBean method,
     * or {@code null} if none (-> use constructor argument values from bean definition)
     * @return a BeanWrapper for the new instance
     */
    protected BeanWrapper autowireConstructor(
            String beanName, RootBeanDefinition mbd, @Nullable Constructor<?>[] ctors, @Nullable Object[] explicitArgs) {

        return new ConstructorResolver(this).autowireConstructor(beanName, mbd, ctors, explicitArgs);
    }

    /**
     * Populate the bean instance in the given BeanWrapper with the property values
     * from the bean definition.
     *
     * @param beanName the name of the bean
     * @param mbd the bean definition for the bean
     * @param bw the BeanWrapper with bean instance
     */
    @SuppressWarnings("deprecation")  // for postProcessPropertyValues
    protected void populateBean(String beanName, RootBeanDefinition mbd, @Nullable BeanWrapper bw) {
        // 未创建 bean 对象
        if (bw == null) {
            // 但是存在需要注入的属性
            if (mbd.hasPropertyValues()) {
                throw new BeanCreationException(
                        mbd.getResourceDescription(), beanName, "Cannot apply property values to null instance");
            } else {
                // Skip property population phase for null instance.
                return;
            }
        }

        // 调用 InstantiationAwareBeanPostProcessor#postProcessAfterInstantiation 方法对初始化前的 bean 实例进行处理
        if (!mbd.isSynthetic() && this.hasInstantiationAwareBeanPostProcessors()) {
            for (BeanPostProcessor bp : this.getBeanPostProcessors()) {
                if (bp instanceof InstantiationAwareBeanPostProcessor) {
                    InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
                    if (!ibp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
                        return;
                    }
                }
            }
        }

        // 获取 bean 实例的属性值集合
        PropertyValues pvs = (mbd.hasPropertyValues() ? mbd.getPropertyValues() : null);

        // 获取注入类型
        int resolvedAutowireMode = mbd.getResolvedAutowireMode();
        if (resolvedAutowireMode == AUTOWIRE_BY_NAME || resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
            MutablePropertyValues newPvs = new MutablePropertyValues(pvs);
            // 根据名称注入
            if (resolvedAutowireMode == AUTOWIRE_BY_NAME) {
                this.autowireByName(beanName, mbd, bw, newPvs);
            }
            // 根据类型注入
            if (resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
                this.autowireByType(beanName, mbd, bw, newPvs);
            }
            pvs = newPvs;
        }

        boolean hasInstAwareBpps = this.hasInstantiationAwareBeanPostProcessors();
        boolean needsDepCheck = (mbd.getDependencyCheck() != AbstractBeanDefinition.DEPENDENCY_CHECK_NONE);

        // 应用 InstantiationAwareBeanPostProcessor#postProcessProperties 方法在注入属性之前对属性值进行处理
        PropertyDescriptor[] filteredPds = null;
        if (hasInstAwareBpps) {
            if (pvs == null) {
                pvs = mbd.getPropertyValues();
            }
            for (BeanPostProcessor bp : this.getBeanPostProcessors()) {
                if (bp instanceof InstantiationAwareBeanPostProcessor) {
                    InstantiationAwareBeanPostProcessor ibp = (InstantiationAwareBeanPostProcessor) bp;
                    PropertyValues pvsToUse = ibp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
                    if (pvsToUse == null) {
                        if (filteredPds == null) {
                            filteredPds = this.filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
                        }
                        // 兼容已过期的方法
                        pvsToUse = ibp.postProcessPropertyValues(pvs, filteredPds, bw.getWrappedInstance(), beanName);
                        // 处理器把属性值处理没了，继续执行属性注入已经没有意义，直接返回
                        if (pvsToUse == null) {
                            return;
                        }
                    }
                    pvs = pvsToUse;
                }
            }
        }

        // 依赖检查，对应 dependency-check 属性，该属性已过期
        if (needsDepCheck) {
            if (filteredPds == null) {
                filteredPds = this.filterPropertyDescriptorsForDependencyCheck(bw, mbd.allowCaching);
            }
            this.checkDependencies(beanName, mbd, filteredPds, pvs);
        }

        // 执行属性注入
        if (pvs != null) {
            this.applyPropertyValues(beanName, mbd, bw, pvs);
        }
    }

    /**
     * Fill in any missing property values with references to
     * other beans in this factory if autowire is set to "byName".
     *
     * @param beanName the name of the bean we're wiring up.
     * Useful for debugging messages; not used functionally.
     * @param mbd bean definition to update through autowiring
     * @param bw the BeanWrapper from which we can obtain information about the bean
     * @param pvs the PropertyValues to register wired objects with
     */
    protected void autowireByName(
            String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {
        // 获取需要注入的属性名称集合
        String[] propertyNames = this.unsatisfiedNonSimpleProperties(mbd, bw);
        for (String propertyName : propertyNames) {
            // 当前属性是由容器管理
            if (this.containsBean(propertyName)) {
                // 获取 bean 实例，如果没有实例化则执行实例化操作
                Object bean = this.getBean(propertyName);
                // 记录到属性集合中
                pvs.add(propertyName, bean);
                // 记录 bean 之间的依赖关系
                this.registerDependentBean(propertyName, beanName);
                if (logger.isTraceEnabled()) {
                    logger.trace("Added autowiring by name from bean name '" + beanName +
                            "' via property '" + propertyName + "' to bean named '" + propertyName + "'");
                }
            } else {
                if (logger.isTraceEnabled()) {
                    logger.trace("Not autowiring property '" + propertyName + "' of bean '" + beanName + "' by name: no matching bean found");
                }
            }
        }
    }

    /**
     * Abstract method defining "autowire by type" (bean properties by type) behavior.
     * <p>This is like PicoContainer default, in which there must be exactly one bean
     * of the property type in the bean factory. This makes bean factories simple to
     * configure for small namespaces, but doesn't work as well as standard Spring
     * behavior for bigger applications.
     *
     * @param beanName the name of the bean to autowire by type
     * @param mbd the merged bean definition to update through autowiring
     * @param bw the BeanWrapper from which we can obtain information about the bean
     * @param pvs the PropertyValues to register wired objects with
     */
    protected void autowireByType(
            String beanName, AbstractBeanDefinition mbd, BeanWrapper bw, MutablePropertyValues pvs) {

        TypeConverter converter = this.getCustomTypeConverter();
        if (converter == null) {
            converter = bw;
        }

        Set<String> autowiredBeanNames = new LinkedHashSet<>(4);
        // 获取需要注入的属性名称集合
        String[] propertyNames = this.unsatisfiedNonSimpleProperties(mbd, bw);
        for (String propertyName : propertyNames) {
            try {
                PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
                // Don't try autowiring by type for type Object: never makes sense,
                // even if it technically is a unsatisfied, non-simple property.
                if (Object.class != pd.getPropertyType()) {
                    // 获取对应的 setter 方法
                    MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
                    // Do not allow eager init for type matching in case of a prioritized post-processor.
                    boolean eager = !(bw.getWrappedInstance() instanceof PriorityOrdered);
                    DependencyDescriptor desc = new AutowireByTypeDependencyDescriptor(methodParam, eager);
                    /*
                     * 解析指定 beanName 属性所匹配的值，并把解析到的属性存储在 autowiredBeanNames 中，当属性存在多个候选 bean 时，比如：
                     *
                     * @Autowired
                     * private List<A> list
                     *
                     * 则会注入找到的所有匹配 A 类型的 bean 实例
                     */
                    Object autowiredArgument = this.resolveDependency(desc, beanName, autowiredBeanNames, converter);
                    // 记录到属性集合中
                    if (autowiredArgument != null) {
                        pvs.add(propertyName, autowiredArgument);
                    }
                    // 记录 bean 之间的依赖关系
                    for (String autowiredBeanName : autowiredBeanNames) {
                        this.registerDependentBean(autowiredBeanName, beanName);
                        if (logger.isTraceEnabled()) {
                            logger.trace("Autowiring by type from bean name '" + beanName + "' via property '" +
                                    propertyName + "' to bean named '" + autowiredBeanName + "'");
                        }
                    }
                    autowiredBeanNames.clear();
                }
            } catch (BeansException ex) {
                throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, propertyName, ex);
            }
        }
    }

    /**
     * Return an array of non-simple bean properties that are unsatisfied.
     * These are probably unsatisfied references to other beans in the
     * factory. Does not include simple properties like primitives or Strings.
     *
     * @param mbd the merged bean definition the bean was created with
     * @param bw the BeanWrapper the bean was created with
     * @return an array of bean property names
     * @see org.springframework.beans.BeanUtils#isSimpleProperty
     */
    protected String[] unsatisfiedNonSimpleProperties(AbstractBeanDefinition mbd, BeanWrapper bw) {
        Set<String> result = new TreeSet<>();
        PropertyValues pvs = mbd.getPropertyValues();
        PropertyDescriptor[] pds = bw.getPropertyDescriptors();
        for (PropertyDescriptor pd : pds) {
            if (pd.getWriteMethod() != null && !this.isExcludedFromDependencyCheck(pd) && !pvs.contains(pd.getName()) &&
                    !BeanUtils.isSimpleProperty(pd.getPropertyType())) {
                result.add(pd.getName());
            }
        }
        return StringUtils.toStringArray(result);
    }

    /**
     * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
     * excluding ignored dependency types or properties defined on ignored dependency interfaces.
     *
     * @param bw the BeanWrapper the bean was created with
     * @param cache whether to cache filtered PropertyDescriptors for the given bean Class
     * @return the filtered PropertyDescriptors
     * @see #isExcludedFromDependencyCheck
     * @see #filterPropertyDescriptorsForDependencyCheck(org.springframework.beans.BeanWrapper)
     */
    protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw, boolean cache) {
        PropertyDescriptor[] filtered = this.filteredPropertyDescriptorsCache.get(bw.getWrappedClass());
        if (filtered == null) {
            filtered = this.filterPropertyDescriptorsForDependencyCheck(bw);
            if (cache) {
                PropertyDescriptor[] existing =
                        this.filteredPropertyDescriptorsCache.putIfAbsent(bw.getWrappedClass(), filtered);
                if (existing != null) {
                    filtered = existing;
                }
            }
        }
        return filtered;
    }

    /**
     * Extract a filtered set of PropertyDescriptors from the given BeanWrapper,
     * excluding ignored dependency types or properties defined on ignored dependency interfaces.
     *
     * @param bw the BeanWrapper the bean was created with
     * @return the filtered PropertyDescriptors
     * @see #isExcludedFromDependencyCheck
     */
    protected PropertyDescriptor[] filterPropertyDescriptorsForDependencyCheck(BeanWrapper bw) {
        List<PropertyDescriptor> pds = new ArrayList<>(Arrays.asList(bw.getPropertyDescriptors()));
        pds.removeIf(this::isExcludedFromDependencyCheck);
        return pds.toArray(new PropertyDescriptor[0]);
    }

    /**
     * Determine whether the given bean property is excluded from dependency checks.
     * <p>This implementation excludes properties defined by CGLIB and
     * properties whose type matches an ignored dependency type or which
     * are defined by an ignored dependency interface.
     *
     * @param pd the PropertyDescriptor of the bean property
     * @return whether the bean property is excluded
     * @see #ignoreDependencyType(Class)
     * @see #ignoreDependencyInterface(Class)
     */
    protected boolean isExcludedFromDependencyCheck(PropertyDescriptor pd) {
        return (AutowireUtils.isExcludedFromDependencyCheck(pd) ||
                this.ignoredDependencyTypes.contains(pd.getPropertyType()) ||
                AutowireUtils.isSetterDefinedInInterface(pd, this.ignoredDependencyInterfaces));
    }

    /**
     * Perform a dependency check that all properties exposed have been set,
     * if desired. Dependency checks can be objects (collaborating beans),
     * simple (primitives and String), or all (both).
     *
     * @param beanName the name of the bean
     * @param mbd the merged bean definition the bean was created with
     * @param pds the relevant property descriptors for the target bean
     * @param pvs the property values to be applied to the bean
     * @see #isExcludedFromDependencyCheck(java.beans.PropertyDescriptor)
     */
    protected void checkDependencies(
            String beanName, AbstractBeanDefinition mbd, PropertyDescriptor[] pds, @Nullable PropertyValues pvs)
            throws UnsatisfiedDependencyException {

        int dependencyCheck = mbd.getDependencyCheck();
        for (PropertyDescriptor pd : pds) {
            if (pd.getWriteMethod() != null && (pvs == null || !pvs.contains(pd.getName()))) {
                boolean isSimple = BeanUtils.isSimpleProperty(pd.getPropertyType());
                boolean unsatisfied = (dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_ALL) ||
                        (isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_SIMPLE) ||
                        (!isSimple && dependencyCheck == AbstractBeanDefinition.DEPENDENCY_CHECK_OBJECTS);
                if (unsatisfied) {
                    throw new UnsatisfiedDependencyException(mbd.getResourceDescription(), beanName, pd.getName(),
                            "Set this property value or disable dependency checking for this bean.");
                }
            }
        }
    }

    /**
     * Apply the given property values, resolving any runtime references
     * to other beans in this bean factory. Must use deep copy, so we
     * don't permanently modify this property.
     *
     * @param beanName the bean name passed for better exception information
     * @param mbd the merged bean definition
     * @param bw the BeanWrapper wrapping the target object
     * @param pvs the new property values
     */
    protected void applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs) {
        if (pvs.isEmpty()) {
            return;
        }

        if (System.getSecurityManager() != null && bw instanceof BeanWrapperImpl) {
            ((BeanWrapperImpl) bw).setSecurityContext(this.getAccessControlContext());
        }

        MutablePropertyValues mpvs = null;
        // 记录待执行类型转换的属性值
        List<PropertyValue> original;

        if (pvs instanceof MutablePropertyValues) {
            mpvs = (MutablePropertyValues) pvs;
            // 之前已经完成了类型转换，直接注入
            if (mpvs.isConverted()) {
                // Shortcut: use the pre-converted values as-is.
                try {
                    bw.setPropertyValues(mpvs);
                    return;
                } catch (BeansException ex) {
                    throw new BeanCreationException(
                            mbd.getResourceDescription(), beanName, "Error setting property values", ex);
                }
            }
            original = mpvs.getPropertyValueList();
        } else {
            original = Arrays.asList(pvs.getPropertyValues());
        }

        TypeConverter converter = this.getCustomTypeConverter();
        if (converter == null) {
            converter = bw;
        }
        // 创建属性值解析器
        BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this, beanName, mbd, converter);

        // Create a deep copy, resolving any references for values.
        List<PropertyValue> deepCopy = new ArrayList<>(original.size());
        boolean resolveNecessary = false;
        // 遍历属性值，执行类型转换
        for (PropertyValue pv : original) {
            // 已经转换过
            if (pv.isConverted()) {
                deepCopy.add(pv);
            }
            // 未转换，执行类型转换
            else {
                String propertyName = pv.getName();
                Object originalValue = pv.getValue();
                if (originalValue == AutowiredPropertyMarker.INSTANCE) {
                    Method writeMethod = bw.getPropertyDescriptor(propertyName).getWriteMethod();
                    if (writeMethod == null) {
                        throw new IllegalArgumentException("Autowire marker for property without write method: " + pv);
                    }
                    originalValue = new DependencyDescriptor(new MethodParameter(writeMethod, 0), true);
                }
                Object resolvedValue = valueResolver.resolveValueIfNecessary(pv, originalValue);
                Object convertedValue = resolvedValue;
                boolean convertible = bw.isWritableProperty(propertyName)
                        && !PropertyAccessorUtils.isNestedOrIndexedProperty(propertyName);
                // 判定可以转换，执行转换
                if (convertible) {
                    convertedValue = this.convertForProperty(resolvedValue, propertyName, bw, converter);
                }
                // Possibly store converted value in merged bean definition,
                // in order to avoid re-conversion for every created bean instance.
                // 转换后的值等于原始值
                if (resolvedValue == originalValue) {
                    if (convertible) {
                        pv.setConvertedValue(convertedValue);
                    }
                    deepCopy.add(pv);
                }
                // 转换后的类型不是集合和数组类型
                else if (convertible && originalValue instanceof TypedStringValue
                        && !((TypedStringValue) originalValue).isDynamic()
                        && !(convertedValue instanceof Collection || ObjectUtils.isArray(convertedValue))) {
                    pv.setConvertedValue(convertedValue);
                    deepCopy.add(pv);
                }
                // 未解析完全（对应集合或数组类型），标记需要继续解析
                else {
                    resolveNecessary = true;
                    deepCopy.add(new PropertyValue(pv, convertedValue));
                }
            }
        }

        // 标记已经全部转换完成
        if (mpvs != null && !resolveNecessary) {
            mpvs.setConverted();
        }

        // 设置属性值，深拷贝
        try {
            bw.setPropertyValues(new MutablePropertyValues(deepCopy));
        } catch (BeansException ex) {
            throw new BeanCreationException(mbd.getResourceDescription(), beanName, "Error setting property values", ex);
        }
    }

    /**
     * Convert the given value for the specified target property.
     */
    @Nullable
    private Object convertForProperty(
            @Nullable Object value, String propertyName, BeanWrapper bw, TypeConverter converter) {

        if (converter instanceof BeanWrapperImpl) {
            return ((BeanWrapperImpl) converter).convertForProperty(value, propertyName);
        } else {
            PropertyDescriptor pd = bw.getPropertyDescriptor(propertyName);
            MethodParameter methodParam = BeanUtils.getWriteMethodParameter(pd);
            return converter.convertIfNecessary(value, pd.getPropertyType(), methodParam);
        }
    }

    /**
     * Initialize the given bean instance, applying factory callbacks
     * as well as init methods and bean post processors.
     * <p>Called from {@link #createBean} for traditionally defined beans,
     * and from {@link #initializeBean} for existing bean instances.
     *
     * @param beanName the bean name in the factory (for debugging purposes)
     * @param bean the new bean instance we may need to initialize
     * @param mbd the bean definition that the bean was created with
     * (can also be {@code null}, if given an existing bean instance)
     * @return the initialized bean instance (potentially wrapped)
     * @see BeanNameAware
     * @see BeanClassLoaderAware
     * @see BeanFactoryAware
     * @see #applyBeanPostProcessorsBeforeInitialization
     * @see #invokeInitMethods
     * @see #applyBeanPostProcessorsAfterInitialization
     */
    protected Object initializeBean(final String beanName, final Object bean, @Nullable RootBeanDefinition mbd) {
        // 1. 激活 bean 实现的 Aware 类：BeanNameAware, BeanClassLoaderAware, BeanFactoryAware
        if (System.getSecurityManager() != null) {
            AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                this.invokeAwareMethods(beanName, bean);
                return null;
            }, this.getAccessControlContext());
        } else {
            this.invokeAwareMethods(beanName, bean);
        }

        // 2. 应用 BeanPostProcessor#postProcessBeforeInitialization 方法
        Object wrappedBean = bean;
        if (mbd == null || !mbd.isSynthetic()) {
            wrappedBean = this.applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
        }

        // 3. 调用用户自定义的 init-method 方法，以及常用的 afterPropertiesSet 方法
        try {
            this.invokeInitMethods(beanName, wrappedBean, mbd);
        } catch (Throwable ex) {
            throw new BeanCreationException((mbd != null ? mbd.getResourceDescription() : null), beanName, "Invocation of init method failed", ex);
        }

        // 应用 BeanPostProcessor#postProcessAfterInitialization 方法
        if (mbd == null || !mbd.isSynthetic()) {
            wrappedBean = this.applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
        }

        return wrappedBean;
    }

    private void invokeAwareMethods(final String beanName, final Object bean) {
        if (bean instanceof Aware) {
            if (bean instanceof BeanNameAware) {
                ((BeanNameAware) bean).setBeanName(beanName);
            }
            if (bean instanceof BeanClassLoaderAware) {
                ClassLoader bcl = this.getBeanClassLoader();
                if (bcl != null) {
                    ((BeanClassLoaderAware) bean).setBeanClassLoader(bcl);
                }
            }
            if (bean instanceof BeanFactoryAware) {
                ((BeanFactoryAware) bean).setBeanFactory(AbstractAutowireCapableBeanFactory.this);
            }
        }
    }

    /**
     * Give a bean a chance to react now all its properties are set,
     * and a chance to know about its owning bean factory (this object).
     * This means checking whether the bean implements InitializingBean or defines
     * a custom init method, and invoking the necessary callback(s) if it does.
     *
     * @param beanName the bean name in the factory (for debugging purposes)
     * @param bean the new bean instance we may need to initialize
     * @param mbd the merged bean definition that the bean was created with
     * (can also be {@code null}, if given an existing bean instance)
     * @throws Throwable if thrown by init methods or by the invocation process
     * @see #invokeCustomInitMethod
     */
    protected void invokeInitMethods(String beanName, final Object bean, @Nullable RootBeanDefinition mbd)
            throws Throwable {

        boolean isInitializingBean = (bean instanceof InitializingBean);
        if (isInitializingBean && (mbd == null || !mbd.isExternallyManagedInitMethod("afterPropertiesSet"))) {
            if (logger.isTraceEnabled()) {
                logger.trace("Invoking afterPropertiesSet() on bean with name '" + beanName + "'");
            }
            if (System.getSecurityManager() != null) {
                try {
                    AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
                        ((InitializingBean) bean).afterPropertiesSet();
                        return null;
                    }, this.getAccessControlContext());
                } catch (PrivilegedActionException pae) {
                    throw pae.getException();
                }
            } else {
                ((InitializingBean) bean).afterPropertiesSet();
            }
        }

        if (mbd != null && bean.getClass() != NullBean.class) {
            String initMethodName = mbd.getInitMethodName();
            if (StringUtils.hasLength(initMethodName) &&
                    !(isInitializingBean && "afterPropertiesSet".equals(initMethodName)) &&
                    !mbd.isExternallyManagedInitMethod(initMethodName)) {
                this.invokeCustomInitMethod(beanName, bean, mbd);
            }
        }
    }

    /**
     * Invoke the specified custom init method on the given bean.
     * Called by invokeInitMethods.
     * <p>Can be overridden in subclasses for custom resolution of init
     * methods with arguments.
     *
     * @see #invokeInitMethods
     */
    protected void invokeCustomInitMethod(String beanName, final Object bean, RootBeanDefinition mbd)
            throws Throwable {

        String initMethodName = mbd.getInitMethodName();
        Assert.state(initMethodName != null, "No init method set");
        Method initMethod = (mbd.isNonPublicAccessAllowed() ?
                BeanUtils.findMethod(bean.getClass(), initMethodName) :
                ClassUtils.getMethodIfAvailable(bean.getClass(), initMethodName));

        if (initMethod == null) {
            if (mbd.isEnforceInitMethod()) {
                throw new BeanDefinitionValidationException("Could not find an init method named '" +
                        initMethodName + "' on bean with name '" + beanName + "'");
            } else {
                if (logger.isTraceEnabled()) {
                    logger.trace("No default init method named '" + initMethodName +
                            "' found on bean with name '" + beanName + "'");
                }
                // Ignore non-existent default lifecycle methods.
                return;
            }
        }

        if (logger.isTraceEnabled()) {
            logger.trace("Invoking init method  '" + initMethodName + "' on bean with name '" + beanName + "'");
        }
        Method methodToInvoke = ClassUtils.getInterfaceMethodIfPossible(initMethod);

        if (System.getSecurityManager() != null) {
            AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                ReflectionUtils.makeAccessible(methodToInvoke);
                return null;
            });
            try {
                AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () ->
                        methodToInvoke.invoke(bean), this.getAccessControlContext());
            } catch (PrivilegedActionException pae) {
                InvocationTargetException ex = (InvocationTargetException) pae.getException();
                throw ex.getTargetException();
            }
        } else {
            try {
                ReflectionUtils.makeAccessible(methodToInvoke);
                methodToInvoke.invoke(bean);
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }
    }

    /**
     * Applies the {@code postProcessAfterInitialization} callback of all
     * registered BeanPostProcessors, giving them a chance to post-process the
     * object obtained from FactoryBeans (for example, to auto-proxy them).
     *
     * @see #applyBeanPostProcessorsAfterInitialization
     */
    @Override
    protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
        return this.applyBeanPostProcessorsAfterInitialization(object, beanName);
    }

    /**
     * Overridden to clear FactoryBean instance cache as well.
     */
    @Override
    protected void removeSingleton(String beanName) {
        synchronized (this.getSingletonMutex()) {
            super.removeSingleton(beanName);
            this.factoryBeanInstanceCache.remove(beanName);
        }
    }

    /**
     * Overridden to clear FactoryBean instance cache as well.
     */
    @Override
    protected void clearSingletonCache() {
        synchronized (this.getSingletonMutex()) {
            super.clearSingletonCache();
            this.factoryBeanInstanceCache.clear();
        }
    }

    /**
     * Expose the logger to collaborating delegates.
     *
     * @since 5.0.7
     */
    Log getLogger() {
        return logger;
    }

    /**
     * Special DependencyDescriptor variant for Spring's good old autowire="byType" mode.
     * Always optional; never considering the parameter name for choosing a primary candidate.
     */
    @SuppressWarnings("serial")
    private static class AutowireByTypeDependencyDescriptor extends DependencyDescriptor {

        public AutowireByTypeDependencyDescriptor(MethodParameter methodParameter, boolean eager) {
            super(methodParameter, false, eager);
        }

        @Override
        public String getDependencyName() {
            return null;
        }
    }

    /**
     * {@link MethodCallback} used to find {@link FactoryBean} type information.
     */
    private static class FactoryBeanMethodTypeFinder implements MethodCallback {

        private final String factoryMethodName;

        private ResolvableType result = ResolvableType.NONE;

        FactoryBeanMethodTypeFinder(String factoryMethodName) {
            this.factoryMethodName = factoryMethodName;
        }

        @Override
        public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
            if (this.isFactoryBeanMethod(method)) {
                ResolvableType returnType = ResolvableType.forMethodReturnType(method);
                ResolvableType candidate = returnType.as(FactoryBean.class).getGeneric();
                if (this.result == ResolvableType.NONE) {
                    this.result = candidate;
                } else {
                    Class<?> resolvedResult = this.result.resolve();
                    Class<?> commonAncestor = ClassUtils.determineCommonAncestor(candidate.resolve(), resolvedResult);
                    if (!ObjectUtils.nullSafeEquals(resolvedResult, commonAncestor)) {
                        this.result = ResolvableType.forClass(commonAncestor);
                    }
                }
            }
        }

        private boolean isFactoryBeanMethod(Method method) {
            return (method.getName().equals(this.factoryMethodName) &&
                    FactoryBean.class.isAssignableFrom(method.getReturnType()));
        }

        ResolvableType getResult() {
            Class<?> resolved = this.result.resolve();
            boolean foundResult = resolved != null && resolved != Object.class;
            return (foundResult ? this.result : ResolvableType.NONE);
        }
    }

}
