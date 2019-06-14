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

import java.util.HashSet;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Initialized;

import javax.enterprise.event.Observes;

import javax.enterprise.inject.se.SeContainerInitializer;

import javax.enterprise.inject.spi.BeanManager;

import javax.ws.rs.Path;

import javax.ws.rs.core.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;

@ApplicationScoped
public class TestApplicationDiscovery {

  private AutoCloseable container;
  
  public TestApplicationDiscovery() {
    super();
  }

  @Before
  public void startContainer() throws Exception {
    this.stopContainer();
    final SeContainerInitializer initializer = SeContainerInitializer.newInstance();
    initializer.disableDiscovery();
    initializer.addExtensions(JaxRsExtension.class);
    initializer.addBeanClasses(this.getClass(), MyApplication.class);

    // Add a bean class for a resource class that is already "claimed"
    // by MyApplication.
    initializer.addBeanClasses(MyResource.class);

    // Add a "free-floating" resource classe.
    initializer.addBeanClasses(UnclaimedResource.class);
    
    this.container = initializer.initialize();
  }

  @After
  public void stopContainer() throws Exception {
    if (this.container != null) {
      this.container.close();
      this.container = null;
    }
  }

  private static final void onStartup(@Observes @Initialized(ApplicationScoped.class) final Object event,
                                      final MyApplication myApplication,
                                      final JaxRsExtension.SyntheticApplication syntheticApplication) {
    assertNotNull(myApplication);
    assertNotNull(syntheticApplication);
  }

  @Test
  public void test() throws Exception {

  }

  private static final class MyApplication extends Application {

    private final Set<Class<?>> classes;
    
    private MyApplication() {
      super();
      this.classes = new HashSet<>();
      this.classes.add(MyResource.class);
    }

    @Override
    public Set<Class<?>> getClasses() {
      return this.classes;
    }

  }

  private static final class MyResource {

    @Path("/myresource")
    public void myResource() {

    }
    
  }

  private static final class UnclaimedResource {

    @Path("/unclaimedResource")
    public void unclaimedResource() {

    }
    
  }
  
}
