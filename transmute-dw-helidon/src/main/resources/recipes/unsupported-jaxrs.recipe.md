---
name: Unsupported JAX-RS / Dropwizard Patterns
type: recipe
order: 25
triggers:
  - superTypes: [javax.ws.rs.container.ContainerRequestFilter,
                  javax.ws.rs.container.ContainerResponseFilter,
                  javax.ws.rs.container.DynamicFeature,
                  jakarta.ws.rs.container.ContainerRequestFilter,
                  jakarta.ws.rs.container.ContainerResponseFilter,
                  jakarta.ws.rs.container.DynamicFeature]
  - superTypes: [io.dropwizard.views.common.View, io.dropwizard.views.View]
  - superTypes: [javax.ws.rs.ext.ExceptionMapper, jakarta.ws.rs.ext.ExceptionMapper]
  - superTypes: [io.dropwizard.servlets.tasks.Task, io.dropwizard.core.cli.Command,
                  io.dropwizard.core.cli.ConfiguredCommand]
postchecks:
  forbidImports:
    - javax.ws.rs.container
    - javax.ws.rs.ext.ExceptionMapper
    - jakarta.ws.rs.container
    - jakarta.ws.rs.ext.ExceptionMapper
    - io.dropwizard.views
    - io.dropwizard.servlets.tasks
    - io.dropwizard.core.cli
---

This file implements a JAX-RS or Dropwizard pattern that has **no direct equivalent** in
Helidon 4 SE. The code must be commented out so the project compiles.

## What to do

1. **Remove** the `extends` or `implements` clause that references the unsupported type.
2. **Keep** everything that uses only application-domain types (your own classes, `java.*`,
   `jakarta.persistence.*`, etc.) — fields, constructors, getters, inner types. These may
   be called by other files and must remain for the project to compile.
3. **Comment out** only the parts that reference unsupported framework types (JAX-RS
   container/ext, Dropwizard, etc.) using line comments or a block comment `/* ... */`.
   - For `super(...)` calls to the removed base class: comment out or replace with a TODO.
4. **Keep inner types** (enums, nested classes, nested interfaces) — never comment these out.
5. **Remove** all imports that no longer compile (JAX-RS container/ext, Dropwizard, etc.).
6. **Keep** the package declaration, any application-domain imports that are still valid,
   and the class declaration.
7. **Add a `TRANSMUTE[unsupported]` comment** above the class explaining what was removed and why.

The goal is a file that **compiles** and clearly marks what needs manual attention.
Retain as much of the class as possible — only remove what directly references unsupported types.

## Example — ContainerRequestFilter

```java
// Before
package com.example.filter;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

@Provider
public class DateNotSpecifiedFilter implements ContainerRequestFilter {
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String dateHeader = requestContext.getHeaderString(HttpHeaders.DATE);
        if (dateHeader == null) {
            throw new WebApplicationException(
                    new IllegalArgumentException("Date Header was not specified"),
                    Response.Status.BAD_REQUEST);
        }
    }
}
```

```java
// After
package com.example.filter;

// TRANSMUTE[unsupported]: This class implemented javax.ws.rs.container.ContainerRequestFilter
//   which has no direct equivalent in Helidon 4 SE.
//   Options:
//   - Rewrite as a Helidon HttpFilter (io.helidon.webserver.http.HttpFilter)
//   - Implement the logic inline in endpoint methods
//   - Remove if no longer needed
//   Original code is preserved in the block comment below.
public class DateNotSpecifiedFilter {
    /*
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String dateHeader = requestContext.getHeaderString(HttpHeaders.DATE);
        if (dateHeader == null) {
            throw new WebApplicationException(
                    new IllegalArgumentException("Date Header was not specified"),
                    Response.Status.BAD_REQUEST);
        }
    }
    */
}
```

## Example — DynamicFeature

```java
// Before
package com.example.filter;

import javax.ws.rs.container.DynamicFeature;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.ext.Provider;

@Provider
public class DateRequiredFeature implements DynamicFeature {
    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        if (resourceInfo.getResourceMethod().getAnnotation(DateRequired.class) != null) {
            context.register(DateNotSpecifiedFilter.class);
        }
    }
}
```

```java
// After
package com.example.filter;

// TRANSMUTE[unsupported]: This class implemented javax.ws.rs.container.DynamicFeature
//   which has no direct equivalent in Helidon 4 SE.
//   DynamicFeature was used for conditional filter registration.
//   In Helidon, use HttpFilter with conditional logic, or apply
//   filtering directly in the endpoint method.
//   Original code is preserved in the block comment below.
public class DateRequiredFeature {
    /*
    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        if (resourceInfo.getResourceMethod().getAnnotation(DateRequired.class) != null) {
            context.register(DateNotSpecifiedFilter.class);
        }
    }
    */
}
```

## Example — Dropwizard View

```java
// Before
package com.example.views;

import io.dropwizard.views.common.View;
import com.example.core.Person;

public class PersonView extends View {
    private final Person person;

    public enum Template { FREEMARKER, MUSTACHE }

    public PersonView(Template template, Person person) {
        super(template == Template.FREEMARKER
                ? "/views/person.ftl" : "/views/person.mustache");
        this.person = person;
    }

    public Person getPerson() { return person; }
}
```

```java
// After — fields, constructor, and getter are kept because PersonResource calls them
package com.example.views;

import com.example.core.Person;

// TRANSMUTE[unsupported]: This class extended io.dropwizard.views.common.View
//   which has no direct equivalent in Helidon 4 SE.
//   Options:
//   - Use a template engine directly (e.g., Freemarker, Mustache, Qute)
//   - Return JSON from endpoints instead of rendered views
//   - Serve static HTML with client-side rendering
public class PersonView {

    public enum Template { FREEMARKER, MUSTACHE }

    private final Person person;

    public PersonView(Template template, Person person) {
        // TRANSMUTE[unsupported]: super(templatePath) removed — View base class gone
        this.person = person;
    }

    public Person getPerson() { return person; }
}
```

## Rules

- **The file MUST compile after transformation.** Remove every import and type reference
  that would cause a compilation error.
- Keep the class declaration so that other files referencing this type do not break.
  If other files call methods on this class, those will fail to compile — that is expected
  and will be handled by the compile-fix agent.
- Remove `@Provider` and any other JAX-RS annotations from the class.
- Keep application-domain imports (e.g., `com.example.*`) that are still valid.
- Do NOT delete the file — keep it as a stub with the commented-out code for reference.

## DO NOT touch

Do NOT modify anything related to:
- `@Http.GET`, `@Http.POST`, `@RestServer.Endpoint` — handled by the REST Resource recipe.
- `@Service.Singleton`, `@Service.Inject` — handled by the Injection Migration feature.
- `@RolesAllowed`, `@PermitAll` — handled by the Security Migration feature.
