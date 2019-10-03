/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2019 microBean™.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.jaxrs.cdi;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;

import javax.enterprise.context.ContextNotActiveException;
import javax.enterprise.context.Dependent;

import javax.enterprise.context.spi.AlterableContext;
import javax.enterprise.context.spi.Context;

import javax.enterprise.context.spi.CreationalContext;

import javax.enterprise.event.Observes;

import javax.enterprise.inject.Any;

import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanAttributes;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessBeanAttributes;
import javax.enterprise.inject.spi.WithAnnotations;

import javax.enterprise.inject.spi.configurator.BeanConfigurator;

import javax.enterprise.util.AnnotationLiteral;

import javax.inject.Qualifier;
import javax.inject.Singleton;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.Path;

import javax.ws.rs.core.Application;
import javax.ws.rs.ApplicationPath;

/**
 * An {@link Extension} that makes {@link Application}s and resource
 * classes available as CDI beans.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 */
public class JaxRsExtension implements Extension {

  private final Set<Class<?>> potentialResourceClasses;

  private final Set<Class<?>> potentialProviderClasses;

  private final Map<Class<?>, BeanAttributes<?>> resourceBeans;

  private final Map<Class<?>, BeanAttributes<?>> providerBeans;

  private final Set<Set<Annotation>> qualifiers;

  /**
   * Creates a new {@link JaxRsExtension}.
   */
  public JaxRsExtension() {
    super();
    this.potentialResourceClasses = new HashSet<>();
    this.potentialProviderClasses = new HashSet<>();
    this.resourceBeans = new HashMap<>();
    this.providerBeans = new HashMap<>();
    this.qualifiers = new HashSet<>();
  }

  private final <T> void discoverRootResourceClasses(@Observes
                                                     @WithAnnotations({ Path.class })
                                                     final ProcessAnnotatedType<T> event) {
    Objects.requireNonNull(event);
    final AnnotatedType<T> annotatedType = event.getAnnotatedType();
    if (annotatedType != null && isRootResourceClass(annotatedType)) {
      final Class<T> javaClass = annotatedType.getJavaClass();
      if (javaClass != null) {
        this.potentialResourceClasses.add(javaClass);
      }
    }
  }

  private final <T> void discoverProviderClasses(@Observes
                                                 @WithAnnotations({ javax.ws.rs.ext.Provider.class })
                                                 final ProcessAnnotatedType<T> event) {
    Objects.requireNonNull(event);
    final AnnotatedType<T> annotatedType = event.getAnnotatedType();
    if (annotatedType != null) {
      final Class<T> javaClass = annotatedType.getJavaClass();
      if (javaClass != null) {
        this.potentialProviderClasses.add(javaClass);
      }
    }
  }

  private final <T> void forAllBeanAttributes(@Observes
                                              final ProcessBeanAttributes<T> event) {
    Objects.requireNonNull(event);
    final BeanAttributes<T> beanAttributes = event.getBeanAttributes();
    if (beanAttributes != null) {
      final Set<Type> beanTypes = beanAttributes.getTypes();
      if (beanTypes != null && !beanTypes.isEmpty()) {
        for (final Type beanType : beanTypes) {
          final Class<?> beanTypeClass;
          if (beanType instanceof Class) {
            beanTypeClass = (Class<?>)beanType;
          } else if (beanType instanceof ParameterizedType) {
            final Object rawBeanType = ((ParameterizedType)beanType).getRawType();
            if (rawBeanType instanceof Class) {
              beanTypeClass = (Class<?>) rawBeanType;
            } else {
              beanTypeClass = null;
            }
          } else {
            beanTypeClass = null;
          }
          if (beanTypeClass != null) {
            if (Application.class.isAssignableFrom(beanTypeClass)) {
              this.qualifiers.add(beanAttributes.getQualifiers()); // yes, add the set as an element, not the set's elements
            }
            
            // Edge case: it could be an application whose methods are
            // annotated with @Path, so it could still be a resource
            // class.  That's why this isn't an else if.
            if (this.potentialResourceClasses.remove(beanTypeClass)) {
              // This bean has a beanType that we previously
              // identified as a JAX-RS resource.
              event.configureBeanAttributes().addQualifiers(ResourceClass.Literal.INSTANCE);
              this.resourceBeans.put(beanTypeClass, beanAttributes);
            }
            
            if (this.potentialProviderClasses.remove(beanTypeClass)) {
              // This bean has a beanType that we previously
              // identified as a Provider class.
              this.providerBeans.put(beanTypeClass, beanAttributes);
            }
          }
        }
      }
    }
  }

  /**
   * Returns an {@linkplain Collections#unmodifiableSet(Set)
   * unmodifiable <code>Set</code>} of {@link Set}s of {@linkplain
   * Qualifier qualifier annotations} that have been found annotating
   * {@link Application}s.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * @return a non-{@code null}, {@linkplain Collections#unmodifiableSet(Set)
   * unmodifiable <code>Set</code>} of {@link Set}s of {@linkplain
   * Qualifier qualifier annotations} that have been found annotating
   * {@link Application}s
   */
  public final Set<Set<Annotation>> getAllApplicationQualifiers() {
    return Collections.unmodifiableSet(this.qualifiers);
  }

  private final void afterNonSyntheticBeansAreEnabled(@Observes
                                                      final AfterBeanDiscovery event,
                                                      final BeanManager beanManager) {
    Objects.requireNonNull(event);
    Objects.requireNonNull(beanManager);
    final Set<Bean<?>> applicationBeans = beanManager.getBeans(Application.class, Any.Literal.INSTANCE);
    if (applicationBeans != null && !applicationBeans.isEmpty()) {
      for (final Bean<?> bean : applicationBeans) {
        @SuppressWarnings("unchecked")
        final Bean<Application> applicationBean = (Bean<Application>)bean;
        final CreationalContext<Application> cc = beanManager.createCreationalContext(applicationBean);
        final Class<? extends Annotation> applicationScope = applicationBean.getScope();
        assert applicationScope != null;
        final Context context = beanManager.getContext(applicationScope);        
        assert context != null;
        final AlterableContext alterableContext = context instanceof AlterableContext ? (AlterableContext)context : null;
        Application application = null;                
        try {
          if (alterableContext == null) {
            application = applicationBean.create(cc);
          } else {
            try {
              application = alterableContext.get(applicationBean, cc);
            } catch (final ContextNotActiveException ok) {
              application = applicationBean.create(cc);
            }
          }
          if (application != null) {
            final Set<Annotation> applicationQualifiers = applicationBean.getQualifiers();
            final ApplicationPath applicationPath = application.getClass().getAnnotation(ApplicationPath.class);
            if (applicationPath != null) {
              event.addBean()
                .types(ApplicationPath.class)
                .scope(Singleton.class)
                .qualifiers(applicationQualifiers)
                .createWith(ignored -> applicationPath);
            }
            final Set<Class<?>> classes = application.getClasses();
            if (classes != null && !classes.isEmpty()) {
              for (final Class<?> cls : classes) {
                final Object resourceBean = this.resourceBeans.remove(cls);
                final Object providerBean = this.providerBeans.remove(cls);
                if (resourceBean == null && providerBean == null) {
                  final BeanConfigurator<?> bc = event.addBean()
                    .scope(Dependent.class) // by default; possibly overridden by read()
                    .read(beanManager.createAnnotatedType(cls))
                    .addQualifiers(applicationQualifiers)
                    .addQualifiers(ResourceClass.Literal.INSTANCE);
                }
              }
            }
            // Deliberately don't try to deal with getSingletons().
          }
        } finally {
          try {
            if (application != null) {
              if (alterableContext == null) {
                applicationBean.destroy(application, cc);
              } else {
                try {
                  alterableContext.destroy(applicationBean);
                } catch (final UnsupportedOperationException ok) {
                    
                }
              }
            }
          } finally {
            cc.release();
          }
        }
      }
    }
      
    // Any potentialResourceClasses left over here are annotated
    // types we discovered, but for whatever reason were not made
    // into beans.  Maybe they were vetoed.
    this.potentialResourceClasses.clear();
      
    // Any potentialProviderClasses left over here are annotated
    // types we discovered, but for whatever reason were not made
    // into beans.  Maybe they were vetoed.
    this.potentialProviderClasses.clear();
      
    // OK, when we get here, if there are any resource beans left
    // lying around they went "unclaimed".  Build a synthetic
    // Application for them.
    if (!this.resourceBeans.isEmpty()) {
      final Set<Entry<Class<?>, BeanAttributes<?>>> resourceBeansEntrySet = this.resourceBeans.entrySet();
      assert resourceBeansEntrySet != null;
      assert !resourceBeansEntrySet.isEmpty();
      final Map<Set<Annotation>, Set<Class<?>>> resourceClassesByQualifiers = new HashMap<>();
      for (final Entry<Class<?>, BeanAttributes<?>> entry : resourceBeansEntrySet) {
        assert entry != null;
        final Set<Annotation> qualifiers = entry.getValue().getQualifiers();
        Set<Class<?>> resourceClasses = resourceClassesByQualifiers.get(qualifiers);
        if (resourceClasses == null) {
          resourceClasses = new HashSet<>();
          resourceClassesByQualifiers.put(qualifiers, resourceClasses);
        }
        resourceClasses.add(entry.getKey());
      }

      final Set<Entry<Set<Annotation>, Set<Class<?>>>> entrySet = resourceClassesByQualifiers.entrySet();
      assert entrySet != null;
      assert !entrySet.isEmpty();
      for (final Entry<Set<Annotation>, Set<Class<?>>> entry : entrySet) {
        assert entry != null;
        final Set<Annotation> resourceBeanQualifiers = entry.getKey();
        final Set<Class<?>> resourceClasses = entry.getValue();
        assert resourceClasses != null;
        assert !resourceClasses.isEmpty();
        final Set<Class<?>> allClasses;
        if (this.providerBeans.isEmpty()) {
          allClasses = resourceClasses;
        } else {
          allClasses = new HashSet<>(resourceClasses);
          final Set<Entry<Class<?>, BeanAttributes<?>>> providerBeansEntrySet = this.providerBeans.entrySet();
          assert providerBeansEntrySet != null;
          assert !providerBeansEntrySet.isEmpty();
          final Iterator<Entry<Class<?>, BeanAttributes<?>>> providerBeansIterator = providerBeansEntrySet.iterator();
          assert providerBeansIterator != null;
          while (providerBeansIterator.hasNext()) {
            final Entry<Class<?>, BeanAttributes<?>> providerBeansEntry = providerBeansIterator.next();
            assert providerBeansEntry != null;
            final Set<Annotation> providerBeanQualifiers = providerBeansEntry.getValue().getQualifiers();
            boolean match = false;
            if (resourceBeanQualifiers == null) {
              if (providerBeanQualifiers == null) {
                match = true;
              }
            } else if (resourceBeanQualifiers.equals(providerBeanQualifiers)) {
              match = true;
            }
            if (match) {
              allClasses.add(providerBeansEntry.getKey());
              providerBeansIterator.remove();
            }              
          }
        }
        assert resourceBeanQualifiers != null;
        assert !resourceBeanQualifiers.isEmpty();
        final Set<Annotation> syntheticApplicationQualifiers = new HashSet<>(resourceBeanQualifiers);
        syntheticApplicationQualifiers.remove(ResourceClass.Literal.INSTANCE);

        event.addBean()
          .addTransitiveTypeClosure(SyntheticApplication.class)
          .scope(Singleton.class)
          .addQualifiers(syntheticApplicationQualifiers)
          .createWith(cc -> new SyntheticApplication(allClasses));
        this.qualifiers.add(syntheticApplicationQualifiers);
      }
      this.resourceBeans.clear();
    }

    if (!this.providerBeans.isEmpty()) {
      // TODO: we found some provider class beans but never associated
      // them with any application.  This would only happen if they
      // were not qualified with qualifiers that also qualified
      // unclaimed resource beans.  That would be odd.  Either we
      // should throw a deployment error or just ignore them.
    }
    this.providerBeans.clear();
  }

  private static final <T> boolean isRootResourceClass(final AnnotatedType<T> type) {
    return type != null && type.isAnnotationPresent(Path.class);
  }

  private static final <T> boolean isResourceClass(final AnnotatedType<T> type) {
    // Section 3.1: "Resource classes are POJOs that have at least one
    // method annotated with @Path or a request method designator."
    //
    // Not sure whether POJO here means "concrete class" or not.
    boolean returnValue = false;
    if (type != null) {
      final Class<?> javaClass = type.getJavaClass();
      if (javaClass != null && !javaClass.isInterface() && !Modifier.isAbstract(javaClass.getModifiers())) {
        final Set<AnnotatedMethod<? super T>> methods = type.getMethods();
        if (methods != null && !methods.isEmpty()) {
          METHOD_LOOP:
          for (final AnnotatedMethod<? super T> method : methods) {
            final Set<Annotation> annotations = method.getAnnotations();
            if (annotations != null && !annotations.isEmpty()) {
              for (final Annotation annotation : annotations) {
                if (annotation != null) {
                  final Class<?> annotationType = annotation.annotationType();
                  if (Path.class.isAssignableFrom(annotationType)) {
                    returnValue = true;
                    break METHOD_LOOP;
                  } else {
                    final Annotation[] metaAnnotations = annotationType.getAnnotations();
                    if (metaAnnotations != null && metaAnnotations.length > 0) {
                      for (final Annotation metaAnnotation : metaAnnotations) {
                        if (metaAnnotation != null) {
                          final Class<?> metaAnnotationType = metaAnnotation.annotationType();
                          if (HttpMethod.class.isAssignableFrom(metaAnnotationType)) {
                            returnValue = true;
                            break METHOD_LOOP;
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    return returnValue;
  }

  /**
   * An {@link Application} that has been synthesized out of resource
   * classes found on the classpath that have not otherwise been
   * {@linkplain Application#getClasses() claimed} by other {@link
   * Application} instances.
   *
   * @author <a href="https://about.me/lairdnelson"
   * target="_parent">Laird Nelson</a>
   *
   * @see Application
   */
  public static final class SyntheticApplication extends Application {

    private final Set<Class<?>> classes;
    
    SyntheticApplication(final Set<Class<?>> classes) {
      super();
      if (classes == null || classes.isEmpty()) {
        this.classes = Collections.emptySet();
      } else {
        this.classes = Collections.unmodifiableSet(classes);
      }
    }

    /**
     * Returns an {@linkplain Collections#unmodifiableSet(Set)
     * unmodifiable <code>Set</code>} of resource and provider
     * classes.
     *
     * <p>This method never returns {@code null}.</p>
     *
     * @return a non-{@code null}, {@linkplain
     * Collections#unmodifiableSet(Set) unmodifiable <code>Set</code>}
     * of resource and provider classes.
     */
    @Override
    public final Set<Class<?>> getClasses() {
      return this.classes;
    }
    
  }

  /**
   * A {@link Qualifier} annotation indicating that a {@link
   * BeanAttributes} implementation is a JAX-RS resource class.
   *
   * <p>This annotation cannot be applied manually to any Java element
   * but can be used as an input to the {@link
   * BeanManager#getBeans(Type, Annotation...)} method.</p>
   *
   * @author <a href="https://about.me/lairdnelson"
   * target="_parent">Laird Nelson</a>
   */
  @Documented
  @Inherited
  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ })
  public @interface ResourceClass {

    /**
     * A {@link ResourceClass} implementation.
     *
     * @author <a href="https://about.me/lairdnelson"
     * target="_parent">Laird Nelson</a>
     *
     * @see #INSTANCE
     */
    public static final class Literal extends AnnotationLiteral<ResourceClass> implements ResourceClass {

      private static final long serialVersionUID = 1L;

      /**
       * The sole instance of this class.
       *
       * <p>This field is never {@code null}.</p>
       */
      public static final ResourceClass INSTANCE = new Literal();
      
    }
    
  }
  
}
