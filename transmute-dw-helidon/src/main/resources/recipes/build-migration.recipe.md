---
name: Build File Migration
type: recipe
order: 1
triggers:
  - files: [pom.xml, build.gradle, build.gradle.kts, build.xml]
---

Migrate the project build system from Dropwizard 3 to Helidon 4 SE declarative.

## Step 1 — Detect the build file

The project contains one or more of the following build files. Use the first match in priority order:
1. `pom.xml` — Maven
2. `build.gradle.kts` — Gradle (Kotlin DSL)
3. `build.gradle` — Gradle (Groovy DSL)
4. `build.xml` — Ant

## Step 2 — Extract project identity

From the existing build file, capture:
- `groupId`, `artifactId`, `version`, `name` (Maven) or `group`, `version` (Gradle)
- The application main class (from a `mainClass` property or plugin config)

## Step 3 — Produce the migrated build file

### If Maven (`pom.xml`)

Replace the entire `pom.xml` using the Helidon 4 SE template below as the structural base.
Fill in the captured identity values. Then:

> **CRITICAL — DO NOT remove `com.fasterxml.jackson.*` dependencies.** Jackson is used by
> application DTOs and is NOT part of the Dropwizard framework. Helidon does not bundle it.
> Dropping Jackson will cause `@JsonProperty` compile errors in DTO classes.
> Treat Jackson as an application dependency and always carry it over.

- **Remove** all dependencies whose `groupId` matches any of:
  - `io.dropwizard`
  - `org.glassfish.jersey`
  - `com.codahale.metrics`
  - `javax.ws.rs` / `jakarta.ws.rs`
  - `org.eclipse.jetty`
- **Keep `com.fasterxml.jackson`** dependencies — Jackson is used by application code (DTOs with
  `@JsonProperty`, etc.) and is NOT a Dropwizard-only dependency. Helidon does not bundle Jackson
  by default, so these must be preserved. Common ones:
  - `com.fasterxml.jackson.core:jackson-annotations`
  - `com.fasterxml.jackson.core:jackson-databind`
  - `com.fasterxml.jackson.core:jackson-core`
  - Any `com.fasterxml.jackson.datatype:*` or `com.fasterxml.jackson.module:*`
  If they were managed by the Dropwizard parent BOM (no explicit version), add explicit versions:
  ```xml
  <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-annotations</artifactId>
      <version>2.18.3</version>
  </dependency>
  ```
- **If the project uses Bean Validation** (i.e., has `javax.validation`, `jakarta.validation`,
  `org.hibernate.validator.constraints`, or `io.dropwizard.validation` imports), add:
  ```xml
  <dependency>
      <groupId>io.helidon.validation</groupId>
      <artifactId>helidon-validation</artifactId>
  </dependency>
  ```
  `helidon-bundles-apt` (already in the template's annotation processor paths) covers the
  `@Validation.Validated` code-generation processor — no additional processor path is needed.
- **If the project uses metrics annotations** (i.e., has `com.codahale.metrics.annotation.Timed`,
  `@Metered`, `@Counted`, or `MetricRegistry`), add Helidon Metrics dependencies:
  ```xml
  <dependency>
      <groupId>io.helidon.metrics</groupId>
      <artifactId>helidon-metrics-api</artifactId>
  </dependency>
  <dependency>
      <groupId>io.helidon.webserver.observe</groupId>
      <artifactId>helidon-webserver-observe-metrics</artifactId>
  </dependency>
  ```
- **If the project uses Hibernate/JPA** (i.e., has `io.dropwizard:dropwizard-hibernate` or
  `javax.persistence`/`jakarta.persistence` imports), add the Helidon Data dependencies:
  ```xml
  <dependency>
      <groupId>io.helidon.data</groupId>
      <artifactId>helidon-data</artifactId>
  </dependency>
  <dependency>
      <groupId>io.helidon.data.sql.datasource</groupId>
      <artifactId>helidon-data-sql-datasource-hikari</artifactId>
  </dependency>
  <dependency>
      <groupId>io.helidon.data.jakarta.persistence</groupId>
      <artifactId>helidon-data-jakarta-persistence</artifactId>
  </dependency>
  <dependency>
      <groupId>jakarta.persistence</groupId>
      <artifactId>jakarta.persistence-api</artifactId>
  </dependency>
  ```
  Also keep the JPA provider that was already present (e.g., `org.eclipse.persistence:org.eclipse.persistence.jpa`
  or `org.hibernate.orm:hibernate-core`). If the original project used Hibernate via Dropwizard
  (`dropwizard-hibernate`), add Hibernate explicitly:
  ```xml
  <dependency>
      <groupId>org.hibernate.orm</groupId>
      <artifactId>hibernate-core</artifactId>
      <version>6.6.13.Final</version>
  </dependency>
  ```
  And add the Helidon Data codegen annotation processor alongside `helidon-bundles-apt`:
  ```xml
  <path>
      <groupId>io.helidon.data.jakarta.persistence</groupId>
      <artifactId>helidon-data-jakarta-persistence-codegen</artifactId>
      <version>${helidon.version}</version>
  </path>
  ```
  Keep any JDBC driver dependencies (e.g., `com.h2database:h2`, `com.mysql:mysql-connector-j`).
- **If the project uses authentication/authorization** (i.e., has `io.dropwizard:dropwizard-auth`,
  `@RolesAllowed`, `@PermitAll`, or `@DenyAll` annotations), add the Helidon Security dependencies:
  ```xml
  <dependency>
      <groupId>io.helidon.webserver</groupId>
      <artifactId>helidon-webserver-security</artifactId>
  </dependency>
  <dependency>
      <groupId>io.helidon.security</groupId>
      <artifactId>helidon-security</artifactId>
  </dependency>
  <dependency>
      <groupId>io.helidon.security</groupId>
      <artifactId>helidon-security-annotations</artifactId>
  </dependency>
  <dependency>
      <groupId>io.helidon.security.providers</groupId>
      <artifactId>helidon-security-providers-http-auth</artifactId>
  </dependency>
  <dependency>
      <groupId>io.helidon.security.abac</groupId>
      <artifactId>helidon-security-abac-role</artifactId>
  </dependency>
  ```
- **Keep** all other application dependencies (databases, serialization, utilities, etc.) and
  carry them into the `<dependencies>` section of the new POM.
- **Keep** any `<dependencyManagement>` entries that are not Dropwizard-related.
- **Keep** any `<properties>` entries that are not Dropwizard-related (including `mainClass`).
- **Override** any `maven.compiler.release`, `maven.compiler.source`, or `maven.compiler.target`
  property to `21`. Helidon 4.x requires Java 21. Remove any old Java version settings (e.g., `11`, `17`).
- **Add explicit versions** for any kept test dependencies that were previously managed by the
  Dropwizard parent BOM (`mockito-core`, `assertj-core`, `junit-jupiter`, etc.). Use recent
  stable versions. If the original POM had version properties for these, keep them.
- Do **not** duplicate dependencies already present in the template.

#### Helidon 4 SE Maven template

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.helidon.applications</groupId>
        <artifactId>helidon-se</artifactId>
        <version>4.4.0</version>
        <relativePath/>
    </parent>

    <groupId>FILL_GROUP_ID</groupId>
    <artifactId>FILL_ARTIFACT_ID</artifactId>
    <version>FILL_VERSION</version>
    <name>FILL_NAME</name>

    <properties>
        <mainClass>FILL_MAIN_CLASS</mainClass>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.release>21</maven.compiler.release>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.helidon.webserver</groupId>
            <artifactId>helidon-webserver</artifactId>
        </dependency>
        <dependency>
            <groupId>io.helidon.http.media</groupId>
            <artifactId>helidon-http-media-jsonb</artifactId>
        </dependency>
        <dependency>
            <groupId>io.helidon.service</groupId>
            <artifactId>helidon-service-registry</artifactId>
        </dependency>
        <dependency>
            <groupId>io.helidon.config</groupId>
            <artifactId>helidon-config-yaml</artifactId>
        </dependency>
        <dependency>
            <groupId>io.helidon.logging</groupId>
            <artifactId>helidon-logging-jul</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.helidon.health</groupId>
            <artifactId>helidon-health</artifactId>
        </dependency>
        <!-- helidon-validation added conditionally above if project uses Bean Validation -->

        <!-- REQUIRED: Jackson — application DTOs use @JsonProperty, @JsonIgnore, etc.
             Helidon does NOT bundle Jackson. These MUST be carried over from the original POM.
             If the original POM had no explicit version (managed by Dropwizard BOM), use 2.18.3. -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
            <version>2.18.3</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.18.3</version>
        </dependency>
        <!-- Add any other com.fasterxml.jackson.* deps that were in the original POM -->

        <!-- Preserved application dependencies go here -->
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <executions>
                    <execution>
                        <id>copy-libs</id>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>io.helidon.bundles</groupId>
                            <artifactId>helidon-bundles-apt</artifactId>
                            <version>${helidon.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
            <plugin>
                <groupId>io.helidon.service</groupId>
                <artifactId>helidon-service-maven-plugin</artifactId>
                <version>${helidon.version}</version>
                <executions>
                    <execution>
                        <id>create-application</id>
                        <goals>
                            <goal>create-application</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### If Gradle (Kotlin DSL — `build.gradle.kts`)

Produce a `build.gradle.kts` that:
- Applies the Helidon Gradle plugin (if available) or configures dependencies manually
- Declares `group`, `version`, and `application { mainClass.set("...") }`
- Adds Helidon 4 SE dependencies equivalent to those in the Maven template above
- Removes all Dropwizard/Jersey/Jetty/Codahale dependencies
- Preserves non-framework application dependencies

### If Gradle (Groovy DSL — `build.gradle`) or Ant (`build.xml`)

Apply the same logic: remove Dropwizard deps, add Helidon 4 SE deps, preserve identity and
non-framework dependencies, using idiomatic syntax for the detected build system.

## Step 4 — Write back

Write the migrated build file to the same path in the output directory.
