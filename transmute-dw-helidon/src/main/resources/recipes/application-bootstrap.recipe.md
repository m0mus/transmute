---
name: Application Bootstrap
type: recipe
order: 2
triggers:
  - superTypes: [io.dropwizard.Application, io.dropwizard.core.Application]
postchecks:
  forbidImports:
    - io.dropwizard.Application
    - io.dropwizard.core.Application
    - io.dropwizard.Bootstrap
    - io.dropwizard.setup.Bootstrap
    - io.dropwizard.setup.Environment
---

This file is the Dropwizard `Application<T>` class. Rewrite it as a Helidon 4 SE entry point.

## Step 1 — Rewrite as Helidon SE entry point

**Keep the original class name and file name.** The Helidon `main()` method does not need to
live in a class called `Main`. Replace the class body with the Helidon 4 SE pattern:

```java
// Before — Dropwizard
public class HelloWorldApplication extends Application<HelloWorldConfiguration> {
    public static void main(String[] args) throws Exception { new HelloWorldApplication().run(args); }

    @Override
    public void initialize(Bootstrap<HelloWorldConfiguration> bootstrap) { bootstrap.addBundle(...); }

    @Override
    public void run(HelloWorldConfiguration config, Environment environment) {
        environment.jersey().register(new MyResource());
        environment.lifecycle().manage(new MyManagedService());
    }
}
```

```java
// After — Helidon 4 SE (same class name, same file)
public class HelloWorldApplication {
    public static void main(String[] args) {
        Config config = Config.create();
        Config.global(config);

        WebServer server = WebServer.builder()
                .config(config.get("server"))
                .routing(HelloWorldApplication::routing)
                .build()
                .start();

        System.out.println("Server started on port " + server.port());
    }

    static void routing(HttpRouting.Builder routing) {
        // @RestServer.Endpoint classes are auto-registered via service registry.
        // Add explicit routes here only for non-annotated services.
    }
}
```

Rules:
- **Keep the original class name** — do NOT rename to `Main`. This avoids filename/classname mismatches.
- Remove `extends Application<T>` and all Dropwizard imports (`io.dropwizard.*`).
- Add required imports:
  - `io.helidon.config.Config`
  - `io.helidon.webserver.WebServer`
  - `io.helidon.webserver.http.HttpRouting`
- Ensure `<mainClass>` in `pom.xml` points to this class's fully qualified name.

## Step 2 — Handle initialize() bundles

If the original `initialize()` method registered bundles via `bootstrap.addBundle(...)`, add a
`TRANSMUTE[manual]` comment inside the `routing()` method:

```java
// TRANSMUTE[manual]: review bundle migrations — bundle registrations from initialize() were removed
```

## Step 3 — Handle run() registrations

For each `environment.jersey().register(new XyzResource())` in the original `run()`:
- If `XyzResource` has been (or will be) migrated to use `@RestServer.Endpoint` + `@Service.Singleton`,
  no routing code is needed — it is auto-discovered.
- Otherwise, add a `TRANSMUTE[manual]` comment in `routing()`:
  ```java
  // TRANSMUTE[manual]: register XyzResource if not annotated with @RestServer.Endpoint
  ```

For each `environment.lifecycle().manage(new XyzManaged())`:
- If `XyzManaged` has been (or will be) migrated to use `@Service.Singleton` with
  `@Service.PostConstruct` / `@Service.PreDestroy`, no explicit wiring is needed.
- Otherwise, add a `TRANSMUTE[manual]` comment:
  ```java
  // TRANSMUTE[manual]: XyzManaged lifecycle — ensure @Service.PostConstruct/@Service.PreDestroy are set
  ```

## Step 4 — Create application.yaml if absent

If no `application.yaml` (or `application.yml`) file exists under `src/main/resources/`, create
`src/main/resources/application.yaml` with:

```yaml
server:
  port: 8080
  host: 0.0.0.0
```

## Step 5 — Delete old Dropwizard YAML config

If a Dropwizard YAML configuration file exists at the project root (commonly named
`config.yml`, `dev.yml`, or matching the pattern `*-config.yml`), delete it.
