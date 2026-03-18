---
name: Security / Auth Migration
type: feature
triggers:
  - imports: [io.dropwizard.auth]
  - annotations: [io.dropwizard.auth.Auth]
postchecks:
  forbidImports:
    - io.dropwizard.auth
---

This file uses Dropwizard authentication or authorisation APIs (`@Auth`, `Authenticator`,
`Authorizer`, `UnauthorizedHandler`, `AuthFactory`, `AuthDynamicFeature`) that must be
migrated to Helidon 4 SE Security.

## @Auth parameter annotation

For every method parameter annotated with `@Auth`, remove the annotation and add a TODO
comment on the parameter line:

```java
// Before
@GET
public Response getUser(@Auth Principal user) { ... }
```

```java
// After
// DW_MIGRATION_TODO[manual]: was @Auth Principal — implement Helidon security context:
// Use io.helidon.security.SecurityContext injected via @Service.Inject,
// then call securityContext.userPrincipal() to retrieve the authenticated user.
public void getUser(ServerRequest req, ServerResponse res) { ... }
```

Remove the `@Auth` annotation and add the TODO comment on the parameter line.

## Authenticator<C, P> implementations

If this file's class implements `Authenticator<C, P>` (where `C` is a credential type such
as `BasicCredentials` or `OAuthCredentials`, and `P` is the principal type):

1. Remove `implements Authenticator<C, P>` from the class declaration.
2. Add `@Service.Singleton` to the class if not already present.
3. Add a class-level TODO comment:
   ```java
   // DW_MIGRATION_TODO[manual]: was Authenticator<C, P> — implement Helidon Security provider.
   // See: https://helidon.io/docs/v4/se/security
   ```
4. Wrap the entire `authenticate()` method (signature + body) in a block comment `/* ... */`
   and add a TODO stub immediately after it:
   ```java
   /*
    * DW_MIGRATION_TODO[reference]: original Authenticator implementation
    * @Override
    * public Optional<User> authenticate(BasicCredentials credentials) throws AuthenticationException {
    *     ... original body ...
    * }
    */
   // DW_MIGRATION_TODO[manual]: implement Helidon Security provider — inject SecurityContext
   ```
   This ensures all `io.dropwizard.auth.*` types (BasicCredentials, AuthenticationException, etc.)
   are removed from active code and their imports can be safely removed.

## Authorizer<P> implementations

If this file's class implements `Authorizer<P>`:

1. Remove `implements Authorizer<P>` from the class declaration.
2. Add `@Service.Singleton` to the class if not already present.
3. Add a class-level TODO comment:
   ```java
   // DW_MIGRATION_TODO[manual]: was Authorizer<P> — implement Helidon Security authorizer.
   // See: https://helidon.io/docs/v4/se/security
   ```

## UnauthorizedHandler implementations

If this file's class implements `UnauthorizedHandler`:

1. Remove `implements UnauthorizedHandler` from the class declaration.
2. Add a TODO comment on the class declaration line:
   ```java
   // DW_MIGRATION_TODO[manual]: was UnauthorizedHandler — configure via Helidon Security
   ```

## AuthFactory / AuthDynamicFeature / AuthValueFactoryProvider registrations

If this file references `AuthFactory`, `AuthDynamicFeature`, or `AuthValueFactoryProvider`,
add a TODO comment on each usage site:

```java
// DW_MIGRATION_TODO[manual]: was AuthFactory/AuthDynamicFeature — remove; configure Helidon Security in Main.routing()
```

Do not remove the usage automatically — leave it in place with the TODO so the developer can
wire Helidon Security in the application's `Main.routing()` method.

## Imports

Remove:
- `io.dropwizard.auth.*`

Add (only if `@Service.Singleton` was added to this file):
- `io.helidon.service.registry.Service`

Do NOT add Helidon Security imports automatically — the developer must configure and inject
`SecurityContext` after reviewing the manual TODOs.

## DO NOT touch

Do NOT modify anything related to:
- `@Inject`, `@Named`, Guice `Injector` or `Module` — handled by the Injection feature.
- `@Timed`, `@Metered`, `MetricRegistry` — handled by the Metrics Migration recipe.
- JAX-RS HTTP method annotations — handled by the REST Resource recipe.
