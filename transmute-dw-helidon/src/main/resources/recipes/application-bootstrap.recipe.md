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

This file is the Dropwizard `Application<T>` class. Rewrite it as a Helidon 4 SE `Main` class.

## Step 1 — Rewrite as Helidon SE Main

Replace the entire class body with the Helidon 4 SE `Main` pattern:

```java
// Before — Dropwizard
public class MyApp extends Application<MyConfig> {
    public static void main(String[] args) throws Exception { new MyApp().run(args); }

    @Override
    public void initialize(Bootstrap<MyConfig> bootstrap) { bootstrap.addBundle(...); }

    @Override
    public void run(MyConfig config, Environment environment) {
        environment.jersey().register(new MyResource());
        environment.lifecycle().manage(new MyManagedService());
    }
}
```

```java
// After — Helidon 4 SE
public class Main {
    public static void main(String[] args) {
        Config config = Config.create();
        Config.global(config);

        WebServer server = WebServer.builder()
                .config(config.get("server"))
                .routing(Main::routing)
                .build()
                .start();

        System.out.println("Server started on port " + server.port());
    }

    static void routing(HttpRouting.Builder routing) {
        // @Http.Endpoint classes are auto-registered via service registry.
        // Add explicit routes here only for non-annotated services.
    }
}
```

Rules:
- Rename the class to `Main` regardless of the original class name.
- Update `<mainClass>` in `pom.xml` (or equivalent) to reference the renamed class.
- Add required imports:
  - `io.helidon.config.Config`
  - `io.helidon.webserver.WebServer`
  - `io.helidon.webserver.http.HttpRouting`
- Remove all Dropwizard imports (`io.dropwizard.*`).

## Step 2 — Handle initialize() bundles

If the original `initialize()` method registered bundles via `bootstrap.addBundle(...)`, add a
TODO comment inside the `routing()` method:

```java
// TODO: review bundle migrations — bundle registrations from initialize() were removed
```

## Step 3 — Handle run() registrations

For each `environment.jersey().register(new XyzResource())` in the original `run()`:
- If `XyzResource` has been (or will be) migrated to use `@Http.Endpoint` + `@Service.Singleton`,
  no routing code is needed — it is auto-discovered.
- Otherwise, add a TODO comment in `routing()`:
  ```java
  // TODO: register XyzResource if not annotated with @Http.Endpoint
  ```

For each `environment.lifecycle().manage(new XyzManaged())`:
- If `XyzManaged` has been (or will be) migrated to use `@Service.Singleton` with
  `@Service.PostConstruct` / `@Service.PreDestroy`, no explicit wiring is needed.
- Otherwise, add a TODO comment:
  ```java
  // TODO: XyzManaged lifecycle — ensure @Service.PostConstruct/@Service.PreDestroy are set
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
