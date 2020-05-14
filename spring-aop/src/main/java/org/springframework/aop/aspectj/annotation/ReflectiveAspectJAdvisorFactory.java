/*
 * Copyright 2002-2020 the original author or authors.
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

package org.springframework.aop.aspectj.annotation;

import org.aopalliance.aop.Advice;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.DeclareParents;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.aop.Advisor;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.aspectj.AbstractAspectJAdvice;
import org.springframework.aop.aspectj.AspectJAfterAdvice;
import org.springframework.aop.aspectj.AspectJAfterReturningAdvice;
import org.springframework.aop.aspectj.AspectJAfterThrowingAdvice;
import org.springframework.aop.aspectj.AspectJAroundAdvice;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.aspectj.AspectJMethodBeforeAdvice;
import org.springframework.aop.aspectj.DeclareParentsAdvisor;
import org.springframework.aop.framework.AopConfigException;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConvertingComparator;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.comparator.InstanceComparator;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Factory that can create Spring AOP Advisors given AspectJ classes from
 * classes honoring the AspectJ 5 annotation syntax, using reflection to
 * invoke the corresponding advice methods.
 *
 * @author Rod Johnson
 * @author Adrian Colyer
 * @author Juergen Hoeller
 * @author Ramnivas Laddad
 * @author Phillip Webb
 * @since 2.0
 */
@SuppressWarnings("serial")
public class ReflectiveAspectJAdvisorFactory extends AbstractAspectJAdvisorFactory implements Serializable {

    private static final Comparator<Method> METHOD_COMPARATOR;

    static {
        Comparator<Method> adviceKindComparator = new ConvertingComparator<>(
                new InstanceComparator<>(
                        Around.class, Before.class, After.class, AfterReturning.class, AfterThrowing.class),
                (Converter<Method, Annotation>) method -> {
                    AspectJAnnotation<?> annotation =
                            AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(method);
                    return (annotation != null ? annotation.getAnnotation() : null);
                });
        Comparator<Method> methodNameComparator = new ConvertingComparator<>(Method::getName);
        METHOD_COMPARATOR = adviceKindComparator.thenComparing(methodNameComparator);
    }

    @Nullable
    private final BeanFactory beanFactory;

    /**
     * Create a new {@code ReflectiveAspectJAdvisorFactory}.
     */
    public ReflectiveAspectJAdvisorFactory() {
        this(null);
    }

    /**
     * Create a new {@code ReflectiveAspectJAdvisorFactory}, propagating the given
     * {@link BeanFactory} to the created {@link AspectJExpressionPointcut} instances,
     * for bean pointcut handling as well as consistent {@link ClassLoader} resolution.
     *
     * @param beanFactory the BeanFactory to propagate (may be {@code null}}
     * @see AspectJExpressionPointcut#setBeanFactory
     * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#getBeanClassLoader()
     * @since 4.3.6
     */
    public ReflectiveAspectJAdvisorFactory(@Nullable BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public List<Advisor> getAdvisors(MetadataAwareAspectInstanceFactory aspectInstanceFactory) {
        // 获取切面 aspect 对应的 class 和 beanName
        Class<?> aspectClass = aspectInstanceFactory.getAspectMetadata().getAspectClass();
        String aspectName = aspectInstanceFactory.getAspectMetadata().getAspectName();
        // 校验切面定义的合法性
        this.validate(aspectClass);

        // We need to wrap the MetadataAwareAspectInstanceFactory with a decorator
        // so that it will only instantiate once.
        MetadataAwareAspectInstanceFactory lazySingletonAspectInstanceFactory =
                new LazySingletonAspectInstanceFactoryDecorator(aspectInstanceFactory);

        List<Advisor> advisors = new ArrayList<>();

        // 1. 遍历处理切面中除被 @Pointcut 注解以外的方法
        for (Method method : this.getAdvisorMethods(aspectClass)) {
            Advisor advisor = this.getAdvisor(method, lazySingletonAspectInstanceFactory, advisors.size(), aspectName);
            if (advisor != null) {
                advisors.add(advisor);
            }
        }

        // 2. 如果增强器不为空，同时又配置了增强延迟初始化，则需要追加实例化增强器 SyntheticInstantiationAdvisor
        if (!advisors.isEmpty() && lazySingletonAspectInstanceFactory.getAspectMetadata().isLazilyInstantiated()) {
            Advisor instantiationAdvisor = new SyntheticInstantiationAdvisor(lazySingletonAspectInstanceFactory);
            advisors.add(0, instantiationAdvisor);
        }

        // 3. 获取所有引介增强定义
        for (Field field : aspectClass.getDeclaredFields()) {
            // 创建引介增强器 DeclareParentsAdvisor
            Advisor advisor = this.getDeclareParentsAdvisor(field);
            if (advisor != null) {
                advisors.add(advisor);
            }
        }

        return advisors;
    }

    private List<Method> getAdvisorMethods(Class<?> aspectClass) {
        final List<Method> methods = new ArrayList<>();
        ReflectionUtils.doWithMethods(aspectClass, method -> {
            // Exclude pointcuts
            if (AnnotationUtils.getAnnotation(method, Pointcut.class) == null) {
                methods.add(method);
            }
        }, ReflectionUtils.USER_DECLARED_METHODS);
        if (methods.size() > 1) {
            methods.sort(METHOD_COMPARATOR);
        }
        return methods;
    }

    /**
     * Build a {@link org.springframework.aop.aspectj.DeclareParentsAdvisor}
     * for the given introduction field.
     * <p>Resulting Advisors will need to be evaluated for targets.
     *
     * @param introductionField the field to introspect
     * @return the Advisor instance, or {@code null} if not an Advisor
     */
    @Nullable
    private Advisor getDeclareParentsAdvisor(Field introductionField) {
        DeclareParents declareParents = introductionField.getAnnotation(DeclareParents.class);
        if (declareParents == null) {
            // Not an introduction field
            return null;
        }

        if (DeclareParents.class == declareParents.defaultImpl()) {
            throw new IllegalStateException("'defaultImpl' attribute must be set on DeclareParents");
        }

        return new DeclareParentsAdvisor(
                introductionField.getType(), declareParents.value(), declareParents.defaultImpl());
    }

    @Override
    @Nullable
    public Advisor getAdvisor(Method candidateAdviceMethod,
                              MetadataAwareAspectInstanceFactory aspectInstanceFactory,
                              int declarationOrderInAspect,
                              String aspectName) {

        // 校验切面类定义的合法性
        this.validate(aspectInstanceFactory.getAspectMetadata().getAspectClass());

        // 获取注解配置的切点信息，封装成 AspectJExpressionPointcut 对象
        AspectJExpressionPointcut expressionPointcut = this.getPointcut(
                candidateAdviceMethod, aspectInstanceFactory.getAspectMetadata().getAspectClass());
        if (expressionPointcut == null) {
            return null;
        }

        // 依据切点信息生成对应的增强器
        return new InstantiationModelAwarePointcutAdvisorImpl(
                expressionPointcut, candidateAdviceMethod, this, aspectInstanceFactory, declarationOrderInAspect, aspectName);
    }

    @Nullable
    private AspectJExpressionPointcut getPointcut(Method candidateAdviceMethod, Class<?> candidateAspectClass) {
        AspectJAnnotation<?> aspectJAnnotation =
                AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(candidateAdviceMethod);
        if (aspectJAnnotation == null) {
            return null;
        }

        AspectJExpressionPointcut ajexp =
                new AspectJExpressionPointcut(candidateAspectClass, new String[0], new Class<?>[0]);
        ajexp.setExpression(aspectJAnnotation.getPointcutExpression());
        if (this.beanFactory != null) {
            ajexp.setBeanFactory(this.beanFactory);
        }
        return ajexp;
    }

    @Override
    @Nullable
    public Advice getAdvice(Method candidateAdviceMethod,
                            AspectJExpressionPointcut expressionPointcut,
                            MetadataAwareAspectInstanceFactory aspectInstanceFactory,
                            int declarationOrder,
                            String aspectName) {

        // 获取切面 class 对象，并校验切面定义
        Class<?> candidateAspectClass = aspectInstanceFactory.getAspectMetadata().getAspectClass();
        this.validate(candidateAspectClass);

        // 获取方法的切点注解定义
        AspectJAnnotation<?> aspectJAnnotation =
                AbstractAspectJAdvisorFactory.findAspectJAnnotationOnMethod(candidateAdviceMethod);
        if (aspectJAnnotation == null) {
            return null;
        }

        // If we get here, we know we have an AspectJ method.
        // Check that it's an AspectJ-annotated class
        if (!this.isAspect(candidateAspectClass)) {
            throw new AopConfigException("Advice must be declared inside an aspect type: " +
                    "Offending method '" + candidateAdviceMethod + "' in class [" + candidateAspectClass.getName() + "]");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Found AspectJ method: " + candidateAdviceMethod);
        }

        AbstractAspectJAdvice springAdvice;

        // 依据切点注解类型使用对应的增强类进行封装
        switch (aspectJAnnotation.getAnnotationType()) {
            // @Pointcut
            case AtPointcut:
                if (logger.isDebugEnabled()) {
                    logger.debug("Processing pointcut '" + candidateAdviceMethod.getName() + "'");
                }
                return null;
            // @Around
            case AtAround:
                springAdvice = new AspectJAroundAdvice(candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
                break;
            // @Before
            case AtBefore:
                springAdvice = new AspectJMethodBeforeAdvice(candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
                break;
            // @After
            case AtAfter:
                springAdvice = new AspectJAfterAdvice(candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
                break;
            // @AfterReturning
            case AtAfterReturning:
                springAdvice = new AspectJAfterReturningAdvice(candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
                AfterReturning afterReturningAnnotation = (AfterReturning) aspectJAnnotation.getAnnotation();
                if (StringUtils.hasText(afterReturningAnnotation.returning())) {
                    springAdvice.setReturningName(afterReturningAnnotation.returning());
                }
                break;
            // @AfterThrowing
            case AtAfterThrowing:
                springAdvice = new AspectJAfterThrowingAdvice(
                        candidateAdviceMethod, expressionPointcut, aspectInstanceFactory);
                AfterThrowing afterThrowingAnnotation = (AfterThrowing) aspectJAnnotation.getAnnotation();
                if (StringUtils.hasText(afterThrowingAnnotation.throwing())) {
                    springAdvice.setThrowingName(afterThrowingAnnotation.throwing());
                }
                break;
            default:
                throw new UnsupportedOperationException("Unsupported advice type on method: " + candidateAdviceMethod);
        }

        // Now to configure the advice...
        springAdvice.setAspectName(aspectName);
        springAdvice.setDeclarationOrder(declarationOrder);
        String[] argNames = this.parameterNameDiscoverer.getParameterNames(candidateAdviceMethod);
        if (argNames != null) {
            springAdvice.setArgumentNamesFromStringArray(argNames);
        }
        springAdvice.calculateArgumentBindings();

        return springAdvice;
    }

    /**
     * Synthetic advisor that instantiates the aspect.
     * Triggered by per-clause pointcut on non-singleton aspect.
     * The advice has no effect.
     */
    @SuppressWarnings("serial")
    protected static class SyntheticInstantiationAdvisor extends DefaultPointcutAdvisor {

        public SyntheticInstantiationAdvisor(final MetadataAwareAspectInstanceFactory aif) {
            super(aif.getAspectMetadata().getPerClausePointcut(), (MethodBeforeAdvice)
                    (method, args, target) -> aif.getAspectInstance());
        }
    }

}
