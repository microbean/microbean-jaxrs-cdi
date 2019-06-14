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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.enterprise.context.ContextNotActiveException;
import javax.enterprise.context.Dependent;

import javax.enterprise.context.spi.AlterableContext;
import javax.enterprise.context.spi.Context;

import javax.enterprise.context.spi.CreationalContext;

import javax.enterprise.event.Observes;

import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessBean;
import javax.enterprise.inject.spi.WithAnnotations;

import javax.inject.Singleton;

import javax.ws.rs.Path;

import javax.ws.rs.core.Application;

public class JaxRsExtension implements Extension {

  private final Set<Class<?>> potentialResourceClasses;

  private final Set<Class<?>> potentialProviderClasses;

  private final Set<Bean<?>> applicationBeans;
  
  private final Map<Class<?>, Bean<?>> resourceBeans;

  private final Map<Class<?>, Bean<?>> providerBeans;

  private final Set<Set<Annotation>> qualifiers;
  
  public JaxRsExtension() {
    super();
    this.potentialResourceClasses = new HashSet<>();
    this.potentialProviderClasses = new HashSet<>();
    this.applicationBeans = new HashSet<>();
    this.resourceBeans = new HashMap<>();
    this.providerBeans = new HashMap<>();
    this.qualifiers = new HashSet<>();
  }

  private final <T> void discoverResourceClasses(@Observes
                                                 @WithAnnotations({ Path.class })
                                                 final ProcessAnnotatedType<T> event) {
    if (event != null) {
      final AnnotatedType<T> annotatedType = event.getAnnotatedType();
      if (annotatedType != null) {
        final Class<T> javaClass = annotatedType.getJavaClass();
        if (javaClass != null) {
          this.potentialResourceClasses.add(javaClass);
        }
      }
    }
  }

  private final <T> void discoverProviderClasses(@Observes
                                                 @WithAnnotations({ javax.ws.rs.ext.Provider.class })
                                                 final ProcessAnnotatedType<T> event) {
    if (event != null) {
      final AnnotatedType<T> annotatedType = event.getAnnotatedType();
      if (annotatedType != null) {
        final Class<T> javaClass = annotatedType.getJavaClass();
        if (javaClass != null) {
          this.potentialProviderClasses.add(javaClass);
        }
      }
    }
  }

  private final <T> void forAllEnabledBeans(@Observes
                                            final ProcessBean<T> event) {
    if (event != null) {
      final Bean<T> bean = event.getBean();
      if (bean != null) {
        final Set<Type> beanTypes = bean.getTypes();
        if (beanTypes != null && !beanTypes.isEmpty()) {
          for (final Type beanType : beanTypes) {
            final Class<?> beanTypeClass;
            if (beanType instanceof Class) {
              beanTypeClass = (Class<?>)beanType;
            } else if (beanType instanceof ParameterizedType) {
              final ParameterizedType parameterizedBeanType = (ParameterizedType)beanType;
              final Type rawBeanType = parameterizedBeanType.getRawType();
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
                this.applicationBeans.add(bean);
                this.qualifiers.add(bean.getQualifiers()); // yes, add the set as an element, not the set's elements
              }

              // Edge case: it could be an application whose methods
              // are annotated with @Path, so it could still be a
              // resource class.  That's why this isn't an else if.
              if (this.potentialResourceClasses.remove(beanTypeClass)) {
                // This bean has a beanType that we previously identified as a JAX-RS resource.
                this.resourceBeans.put(beanTypeClass, bean);
              }

              if (this.potentialProviderClasses.remove(beanTypeClass)) {
                // This bean has a beanType that we previously identified as a Provider class.
                this.providerBeans.put(beanTypeClass, bean);
              }
            }
          }
        }
      }
    }
  }

  public final Set<Set<Annotation>> getApplicationQualifiers() {
    return Collections.unmodifiableSet(this.qualifiers);
  }

  private final void afterNonSyntheticBeansAreEnabled(@Observes
                                                      final AfterBeanDiscovery event,
                                                      final BeanManager beanManager) {
    if (event != null && beanManager != null) {
      for (final Bean<?> bean : this.applicationBeans) {
        assert bean != null;
        @SuppressWarnings("unchecked")
        final Bean<Application> applicationBean = (Bean<Application>)bean;
        final CreationalContext<Application> cc = beanManager.createCreationalContext(applicationBean);
        final Class<? extends Annotation> applicationScope = applicationBean.getScope();
        assert applicationScope != null;
        Context context = beanManager.getContext(applicationScope);        
        assert context != null;
        AlterableContext alterableContext = context instanceof AlterableContext ? (AlterableContext)context : null;
        Application application = null;                
        try {
          if (alterableContext == null) {
            application = applicationBean.create(cc);
          } else {
            try {
              application = alterableContext.get(applicationBean, cc);
            } catch (final ContextNotActiveException ok) {
              alterableContext = null;
              application = applicationBean.create(cc);
            }
          }
          if (application != null) {            
            final Set<Class<?>> classes = application.getClasses();
            if (classes != null && !classes.isEmpty()) {
              final Set<Annotation> applicationQualifiers = applicationBean.getQualifiers();
              for (final Class<?> cls : classes) {
                final Object resourceBean = this.resourceBeans.remove(cls);
                final Object providerBean = this.providerBeans.remove(cls);
                if (resourceBean == null && providerBean == null) {
                  event.addBean()
                    .scope(Dependent.class) // by default; possibly overridden by read()
                    .read(beanManager.createAnnotatedType(cls))
                    .addQualifiers(applicationQualifiers);
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
      this.applicationBeans.clear();

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
        final Set<Entry<Class<?>, Bean<?>>> resourceBeansEntrySet = this.resourceBeans.entrySet();
        assert resourceBeansEntrySet != null;
        assert !resourceBeansEntrySet.isEmpty();
        final Map<Set<Annotation>, Set<Class<?>>> resourceClassesByQualifiers = new HashMap<>();
        for (final Entry<Class<?>, Bean<?>> entry : resourceBeansEntrySet) {
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
            final Set<Entry<Class<?>, Bean<?>>> providerBeansEntrySet = this.providerBeans.entrySet();
            assert providerBeansEntrySet != null;
            assert !providerBeansEntrySet.isEmpty();
            final Iterator<Entry<Class<?>, Bean<?>>> providerBeansIterator = providerBeansEntrySet.iterator();
            assert providerBeansIterator != null;
            while (providerBeansIterator.hasNext()) {
              final Entry<Class<?>, Bean<?>> providerBeansEntry = providerBeansIterator.next();
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

          event.addBean()
            .addTransitiveTypeClosure(SyntheticApplication.class)
            .scope(Singleton.class)
            .addQualifiers(resourceBeanQualifiers)
            .createWith(cc -> new SyntheticApplication(allClasses));
          this.qualifiers.add(resourceBeanQualifiers);
        }

        this.resourceBeans.clear();
      }

      if (!this.providerBeans.isEmpty()) {
        // TODO: we found some provider class beans but never
        // associated them with any application.  This would only
        // happen if they were not qualified with qualifiers that also
        // qualified unclaimed resource beans.  That would be odd.
        // Either we should throw a deployment error or just ignore
        // them.
      }
      this.providerBeans.clear();

    }
  }

  public static final class SyntheticApplication extends Application {

    private final Set<Class<?>> classes;
    
    SyntheticApplication(final Set<Class<?>> classes) {
      super();
      this.classes = classes;
    }

    @Override
    public final Set<Class<?>> getClasses() {
      return this.classes;
    }
    
  }
  
}
