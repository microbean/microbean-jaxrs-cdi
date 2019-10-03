# microBean™ Jakarta RESTful Web Services CDI Integration

[![Build Status](https://travis-ci.com/microbean/microbean-jaxrs-cdi.svg?branch=master)](https://travis-ci.com/microbean/microbean-jaxrs-cdi)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.microbean/microbean-jaxrs-cdi/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.microbean/microbean-jaxrs-cdi)

The microBean™ Jakarta RESTful Web Services CDI Integration project
integrates certain implementation-agnostic [Jakarta RESTful Web
Services](https://jakarta.ee/specifications/restful-ws/2.1/)
constructs into [CDI 2.0](https://jakarta.ee/specifications/cdi/2.0/)
environments in an idiomatic way.

## Installation

Include this library as a `runtime`-scoped dependency of your CDI-based Maven project:
```
<dependency>
  <groupId>org.microbean</groupId>
  <artifactId>microbean-jaxrs-cdi</artifactId>
  <version>0.1.7</version>
  <scope>runtime</scope>
</dependency>
```

## What This Project Does Not Provide

This project does not provide a Jakarta RESTful Web Services
implementation (such as [Eclipse
Jersey](https://projects.eclipse.org/projects/ee4j.jersey)).  However,
if such a product is present on the runtime classpath and integrated
into CDI, then the beans synthesized by this project may prove useful
in such a situation.

This project does not provide a web server or a [Jakarta
Servlet](https://jakarta.ee/specifications/servlet/) implementation.
It is solely concerned with ensuring that certain Jakarta RESTful Web
Services constructs are properly represented as CDI beans, to be used,
or not, in an unspecified manner, by other CDI-based projects.

## Usage

The CDI portable extension supplied by this project, when present on
the runtime classpath, will, at application startup time, begin by
asking CDI for beans that can produce instances of
[`javax.ws.rs.core.Application`](https://jakarta.ee/specifications/restful-ws/2.1/apidocs/javax/ws/rs/core/Application.html).

### No `Application` Present

Let's say that for any one of a variety of possible reasons CDI is
unable to locate any such beans.

The portable extension will ask CDI for all beans that it knows about
that meet the conditions for being a root resource class (typically
this means having a
[`@Path`](https://jakarta.ee/specifications/restful-ws/2.1/apidocs/javax/ws/rs/Path.html)
annotation on the class).  Beans will be synthesized for all of them,
and all of them will gain the additional
[`ResourceClass`](https://microbean.github.io/microbean-jaxrs-cdi/apidocs/org/microbean/jaxrs/cdi/JaxRsExtension.ResourceClass.html)
qualifier, and all of them will be placed into the CDI container for
injection and processing elsewhere within the CDI ecosystem.

The portable extension will also ask CDI for all provider classes
(typically ones annotated with
[`Provider`](https://jakarta.ee/specifications/restful-ws/2.1/apidocs/javax/ws/rs/ext/Provider.html))
and will synthesize beans for all of them.

Additionally, an
[`Application`](https://jakarta.ee/specifications/restful-ws/2.1/apidocs/javax/ws/rs/core/Application.html)
implementation will be synthesized just-in-time whose
[`getClasses()`](https://jakarta.ee/specifications/restful-ws/2.1/apidocs/javax/ws/rs/core/Application.html#getClasses--)
method, if called, would return all the classes (root resource classes
and provider classes) for which beans will have been just synthesized.
The `Application` and all of the synthetic beans will be made
injectable according to CDI's rules.

### `Application` Present

Let's now consider the case where CDI finds an `Application` bean.

The portable extension will [create a contextual instance of, not
acquire a contextual reference
to](https://jakarta.ee/specifications/cdi/2.0/apidocs/javax/enterprise/context/spi/Context.html#get-javax.enterprise.context.spi.Contextual-javax.enterprise.context.spi.CreationalContext-),
that `Application` bean that will live just long enough for its
[`getClasses()`](https://jakarta.ee/specifications/restful-ws/2.1/apidocs/javax/ws/rs/core/Application.html#getClasses--)
method to be invoked.  (The `Application` instance so created will be
[destroyed
properly](https://jakarta.ee/specifications/cdi/2.0/apidocs/javax/enterprise/context/spi/AlterableContext.html#destroy-javax.enterprise.context.spi.Contextual-)
shortly thereafter.)

If that `Application`'s `getClasses()` method returns `null` or an
empty `Set`, then the behavior of the portable extension will be
exactly that described above.

If instead that `Application`'s `getClasses()` returns a non-`null`,
non-empty `Set` of classes, then beans will be synthesized for all
elements in that return value.

#### `ApplicationPath` Support

The portable extension supports
[`ApplicationPath`](https://jakarta.ee/specifications/restful-ws/2.1/apidocs/javax/ws/rs/ApplicationPath.html)
by creating synthesizing a bean for it in `Singleton` scope.

### Qualifiers and Multiple `Application`s

Throughout this process, CDI
[qualifiers](https://jakarta.ee/specifications/cdi/2.0/cdi-spec-2.0.html#qualifiers)
are respected and used to group applications and their resources
together.

By default, every bean in the CDI ecosystem, unless otherwise
qualified, behaves as though it has the
[`Default`](https://jakarta.ee/specifications/cdi/2.0/apidocs/javax/enterprise/inject/Default.html)
and
[`Any`](https://jakarta.ee/specifications/cdi/2.0/apidocs/javax/enterprise/inject/Any.html)
qualifiers on it.  So if no qualifiers appear on `Application` classes
and no qualifiers appear on any root resource classes or provider
classes everything works the way you would expect.

Everything also works if there are different sets of qualifiers
present on things.  So, for example, if an `Application` bean is
qualified with, say, `@Yellow`, and returns `null` from its
`getClasses()` method, then any beans representing root resource classes that are also qualified with
`@Yellow` will be associated with that `Application`, and with none
other.  On the other hand, if there are beans in this same CDI
application representing root resource classes but that are not
qualified with `@Yellow`, then those root resource classes will form
an _additional_ `Application`, in this case a synthetic one.

The net result here would be _two_ `Application`s running together
under the same classloader: a synthetic `Application` qualified with
`@Default` and `@Any`, whose `getClasses()` method will return root
resource classes and provider classes also qualified with `@Default`
and `@Any`, and the user-supplied, `@Yellow`-qualified `Application`
whose affiliated root resource classes and provider classes are
qualified with `@Yellow`.

Obviously with most Jakarta RESTful Web Services implementations two
`Application`s may have trouble running together, particularly if they
are not further distinguished by non-equal
[`@ApplicationPath`](https://jakarta.ee/specifications/restful-ws/2.1/apidocs/javax/ws/rs/ApplicationPath.html)
annotations.

## Related Projects

* [microBean™ Jersey Netty Integration](https://microbean.github.io/microbean-jersey-netty/)
* [microBean™ Jersey Netty CDI Integration](https://microbean.github.io/microbean-jersey-netty-cdi/)
* [Eclipse Jersey](https://projects.eclipse.org/projects/ee4j.jersey)
