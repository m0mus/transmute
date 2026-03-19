---
name: Security / Auth Migration
type: feature
order: 7
triggers:
  - imports: [io.dropwizard.auth]
  - annotations: [io.dropwizard.auth.Auth]
  - annotations: [javax.annotation.security.RolesAllowed,
                   javax.annotation.security.PermitAll,
                   javax.annotation.security.DenyAll]
  - imports: [javax.annotation.security]
owns:
  # annotations auto-inherited from trigger groups:
  #   io.dropwizard.auth.Auth
  #   javax.annotation.security.RolesAllowed, PermitAll, DenyAll
  types:
    - io.dropwizard.auth.Authenticator
    - io.dropwizard.auth.Authorizer
    - io.dropwizard.auth.UnauthorizedHandler
    - io.dropwizard.auth.AuthDynamicFeature
    - io.dropwizard.auth.AuthValueFactoryProvider
postchecks:
  forbidImports:
    - io.dropwizard.auth
    - javax.annotation.security
---

This file uses Dropwizard authentication/authorization APIs or `javax.annotation.security`
annotations that must be migrated to Helidon 4 SE Declarative Security.

Helidon Declarative Security supports `jakarta.annotation.security.RolesAllowed`,
`jakarta.annotation.security.PermitAll`, and `jakarta.annotation.security.DenyAll`
natively on `@RestServer.Endpoint` classes. Authentication/authorization providers are
configured in `application.yaml`, not wired in Java code.

## Security annotations (`@RolesAllowed`, `@PermitAll`, `@DenyAll`)

Replace `javax.annotation.security` imports with `jakarta.annotation.security`:

| Remove                                   | Add                                      |
|------------------------------------------|------------------------------------------|
| `javax.annotation.security.RolesAllowed` | `jakarta.annotation.security.RolesAllowed` |
| `javax.annotation.security.PermitAll`    | `jakarta.annotation.security.PermitAll`    |
| `javax.annotation.security.DenyAll`      | `jakarta.annotation.security.DenyAll`      |

The annotations themselves stay exactly the same — only the import changes.

### Example

```java
// Before
import javax.annotation.security.RolesAllowed;
import javax.annotation.security.PermitAll;

@RolesAllowed("BASIC_GUY")
public class ProtectedClassResource {
    @PermitAll
    public String guestEndpoint() { ... }
}
```

```java
// After — only imports change
import jakarta.annotation.security.RolesAllowed;
import jakarta.annotation.security.PermitAll;

@RolesAllowed("BASIC_GUY")
public class ProtectedClassResource {
    @PermitAll
    public String guestEndpoint() { ... }
}
```

## @Auth parameter annotation

For every method parameter annotated with `@Auth` (from `io.dropwizard.auth`), **remove
the parameter entirely** from the method signature. Add a `TRANSMUTE[manual]` comment above the method:

```java
// TRANSMUTE[manual]: @Auth User parameter removed — inject via Helidon Security
//   Use io.helidon.security.SecurityContext injected via @Service.Inject,
//   then call securityContext.userPrincipal() to retrieve the authenticated user.
```

If the method body references the removed parameter, replace usages with a placeholder
or `TRANSMUTE[manual]` comment so the code compiles:
- For string formatting: use a placeholder like `"anonymous"`
- For method calls on the removed object: comment them out with a TODO

## Authenticator<C, P> implementations

If this file's class implements `Authenticator<C, P>`:

1. Remove `implements Authenticator<C, P>` from the class declaration.
2. Add `@Service.Singleton` to the class if not already present.
3. Remove the `@Override` annotation from the `authenticate()` method.
4. Simplify the method signature — remove Dropwizard credential types from the parameter
   (e.g., `BasicCredentials` → plain `String username, String password`).
5. Remove `throws AuthenticationException` from the method signature.
6. Add a class-level `TRANSMUTE[manual]` comment:

```java
// Before
import io.dropwizard.auth.AuthenticationException;
import io.dropwizard.auth.Authenticator;
import io.dropwizard.auth.basic.BasicCredentials;

public class ExampleAuthenticator implements Authenticator<BasicCredentials, User> {
    @Override
    public Optional<User> authenticate(BasicCredentials credentials)
            throws AuthenticationException {
        if ("secret".equals(credentials.getPassword())) {
            return Optional.of(new User(credentials.getUsername()));
        }
        return Optional.empty();
    }
}
```

```java
// After
import io.helidon.service.registry.Service;
import java.util.Optional;

// TRANSMUTE[manual]: was Authenticator — authentication is now config-driven.
//   Configure in application.yaml under security.providers (e.g., http-basic-auth).
//   This class is kept as reference for the credential validation logic.
//   See: https://helidon.io/docs/v4/se/security
@Service.Singleton
public class ExampleAuthenticator {
    public Optional<User> authenticate(String username, String password) {
        if ("secret".equals(password)) {
            return Optional.of(new User(username));
        }
        return Optional.empty();
    }
}
```

## Authorizer<P> implementations

If this file's class implements `Authorizer<P>`:

1. Remove `implements Authorizer<P>` from the class declaration.
2. Remove the `ContainerRequestContext` parameter (Helidon does not use JAX-RS contexts).
3. Remove Dropwizard auth and JAX-RS container imports.
4. Add `@Service.Singleton`.
5. Add a class-level `TRANSMUTE[manual]` comment:

```java
// Before
import io.dropwizard.auth.Authorizer;
import javax.ws.rs.container.ContainerRequestContext;

public class ExampleAuthorizer implements Authorizer<User> {
    @Override
    public boolean authorize(User user, String role, ContainerRequestContext ctx) {
        return user.getRoles().contains(role);
    }
}
```

```java
// After
import io.helidon.service.registry.Service;

// TRANSMUTE[manual]: was Authorizer — role-based authorization is now handled
//   declaratively via @RolesAllowed annotations and the ABAC role provider.
//   Configure security.providers.abac in application.yaml.
@Service.Singleton
public class ExampleAuthorizer {
    public boolean authorize(User user, String role) {
        return user.getRoles().contains(role);
    }
}
```

## UnauthorizedHandler implementations

If this file's class implements `UnauthorizedHandler`:

1. Remove `implements UnauthorizedHandler` from the class declaration.
2. Add a `TRANSMUTE[manual]` comment on the class declaration line:
   ```java
   // TRANSMUTE[manual]: was UnauthorizedHandler — configure via Helidon Security
   ```

## AuthFactory / AuthDynamicFeature / AuthValueFactoryProvider registrations

If this file references `AuthFactory`, `AuthDynamicFeature`, or `AuthValueFactoryProvider`,
comment out the usage and add a `TRANSMUTE[manual]` marker:

```java
// TRANSMUTE[manual]: was AuthDynamicFeature — remove; auth is now config-driven
//   in application.yaml under security.providers.
```

## Imports

Remove:
- `io.dropwizard.auth.*`
- `io.dropwizard.auth.basic.*`
- `javax.annotation.security.*` (replaced with `jakarta.annotation.security.*`)
- `javax.ws.rs.container.ContainerRequestContext` (if only used for Authorizer)
- `org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature`

Add:
- `jakarta.annotation.security.RolesAllowed` (if `@RolesAllowed` is used)
- `jakarta.annotation.security.PermitAll` (if `@PermitAll` is used)
- `jakarta.annotation.security.DenyAll` (if `@DenyAll` is used)
- `io.helidon.service.registry.Service` (if `@Service.Singleton` was added)

## DO NOT touch

Do NOT modify anything related to:
- `@Inject`, `@Named`, Guice `Injector` or `Module` — handled by the Injection feature.
- `@Timed`, `@Metered`, `MetricRegistry` — handled by the Metrics Migration recipe.
- JAX-RS HTTP method annotations (`@GET`, `@POST`, `@Path`) — handled by the REST Resource recipe.
