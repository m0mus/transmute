---
name: HTTP Client Migration
type: recipe
order: 12
triggers:
  - imports: [io.dropwizard.client.JerseyClientBuilder]
  - imports: [io.dropwizard.client.HttpClientBuilder]
  - imports: [io.dropwizard.client]
postchecks:
  forbidImports:
    - io.dropwizard.client
---

This file uses the Dropwizard Jersey or Apache HTTP client (`JerseyClientBuilder`,
`HttpClientBuilder`) and must be migrated to the Helidon 4 SE `WebClient`. The two APIs
differ significantly, so the migration produces guided TODO comments rather than a full
automated rewrite.

## JerseyClientBuilder usages

For each `new JerseyClientBuilder(environment).build(...)` call:

1. Remove the call expression.
2. Add a TODO comment in its place:
   ```java
   // DW_MIGRATION_TODO[manual]: was JerseyClientBuilder — use Helidon WebClient:
   // WebClient client = WebClient.create();
   // See: https://helidon.io/docs/v4/se/webclient
   ```

If the result is assigned to a `Client` variable, remove the variable declaration and replace it
with the TODO comment.

## HttpClientBuilder usages

For each `new HttpClientBuilder(environment).build(...)` call:

1. Remove the call expression.
2. Add a TODO comment in its place:
   ```java
   // DW_MIGRATION_TODO[manual]: was HttpClientBuilder — use Helidon WebClient:
   // WebClient client = WebClient.create();
   // See: https://helidon.io/docs/v4/se/webclient
   ```

## WebTarget / Response API usages

For each call chain involving `client.target(...)`, `target.path(...)`, `.request()`, `.get()`,
`.post(...)`, etc. that stems from a Dropwizard-built client:

1. Remove the call chain.
2. Add a TODO comment showing the Helidon WebClient equivalent pattern:
   ```java
   // DW_MIGRATION_TODO[manual]: was client.target(...).path(...).request().get() — use Helidon WebClient:
   // HttpClientResponse response = client.get("https://...").request();
   // String body = response.as(String.class);
   // See: https://helidon.io/docs/v4/se/webclient
   ```

## JerseyClientConfiguration / HttpClientConfiguration fields

If the class declares a `JerseyClientConfiguration` or `HttpClientConfiguration` field (commonly
injected from the Dropwizard config class):

1. Remove the field declaration and any associated getter/setter.
2. Add a TODO comment:
   ```java
   // DW_MIGRATION_TODO[manual]: was JerseyClientConfiguration/HttpClientConfiguration —
   // configure Helidon WebClient via application.yaml under a custom key, e.g.:
   // webclient:
   //   connect-timeout-millis: 5000
   //   read-timeout-millis: 10000
   ```

## Environment parameter used only for client building

If the class receives or injects `io.dropwizard.setup.Environment` solely to pass it to
`JerseyClientBuilder` or `HttpClientBuilder`:

1. Remove the `Environment` parameter or field.
2. Add a TODO comment if `Environment` was also used for other purposes:
   ```java
   // DW_MIGRATION_TODO[manual]: verify Environment was only used for client building — remove if so
   ```

If `Environment` is used for other purposes beyond client construction, do NOT remove it;
add the TODO comment only.

## Imports

Remove:
- `io.dropwizard.client.JerseyClientBuilder`
- `io.dropwizard.client.HttpClientBuilder`
- `io.dropwizard.client.JerseyClientConfiguration`
- `io.dropwizard.client.HttpClientConfiguration`
- Any other `io.dropwizard.client.*` imports
- `javax.ws.rs.client.Client`
- `jakarta.ws.rs.client.Client`
- `javax.ws.rs.client.WebTarget`
- `jakarta.ws.rs.client.WebTarget`
- `javax.ws.rs.core.Response`
- `jakarta.ws.rs.core.Response`

Do NOT add Helidon WebClient imports automatically — the developer must complete the migration
after reviewing the manual TODOs. The relevant import when ready is:
- `io.helidon.webclient.http1.Http1Client` (or `io.helidon.webclient.api.WebClient`)

## DO NOT touch

Do NOT modify anything related to:
- `@Inject`, `@Named`, Guice `Injector` or `Module` — handled by the Injection feature.
- `@Timed`, `@Metered`, `MetricRegistry` — handled by the Metrics Migration recipe.
- `@UnitOfWork`, `AbstractDAO`, `HibernateBundle` — handled by the Unit of Work feature.
