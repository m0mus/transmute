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

- **Remove** all dependencies whose `groupId` matches any of:
  - `io.dropwizard`
  - `org.glassfish.jersey`
  - `com.codahale.metrics`
  - `javax.ws.rs` / `jakarta.ws.rs`
  - `org.eclipse.jetty`
- **Keep** all other application dependencies (databases, serialization, utilities, etc.) and
  carry them into the `<dependencies>` section of the new POM.
- **Keep** any `<dependencyManagement>` entries that are not Dropwizard-related.
- **Keep** any `<properties>` entries that are not Dropwizard-related (including `mainClass`).
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
        <version>4.3.4</version>
        <relativePath/>
    </parent>

    <groupId>FILL_GROUP_ID</groupId>
    <artifactId>FILL_ARTIFACT_ID</artifactId>
    <version>FILL_VERSION</version>
    <name>FILL_NAME</name>

    <properties>
        <mainClass>FILL_MAIN_CLASS</mainClass>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
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
