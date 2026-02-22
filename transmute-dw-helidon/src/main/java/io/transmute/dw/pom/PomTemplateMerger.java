package io.transmute.dw.pom;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a Helidon declarative pom.xml from a template and merges identity and
 * non-Dropwizard dependencies from the original pom.xml.
 */
public class PomTemplateMerger {

    private static final Pattern TAG = Pattern.compile("<(groupId|artifactId|version|name)>\\s*([^<]+)\\s*</\\1>");
    private static final Pattern PROJECT_BLOCK = Pattern.compile("(?s)<project[^>]*>(.*)</project>");
    private static final Pattern PARENT_BLOCK = Pattern.compile("(?s)<parent>.*?</parent>");
    private static final Pattern DEP_MGMT_BLOCK = Pattern.compile("(?s)<dependencyManagement>\\s*(.*?)\\s*</dependencyManagement>");
    private static final Pattern DEP_BLOCK = Pattern.compile("(?s)<dependency>\\s*(.*?)\\s*</dependency>");
    private static final Pattern DEP_COORDS = Pattern.compile(
            "<groupId>\\s*([^<]+)\\s*</groupId>\\s*<artifactId>\\s*([^<]+)\\s*</artifactId>(?:\\s*<version>\\s*([^<]+)\\s*</version>)?");

    private static final List<Pattern> DROPWIZARD_DEP_PATTERNS = List.of(
            Pattern.compile("io\\.dropwizard"),
            Pattern.compile("org\\.glassfish\\.jersey"),
            Pattern.compile("com\\.codahale\\.metrics"),
            Pattern.compile("javax\\.ws\\.rs"),
            Pattern.compile("jakarta\\.ws\\.rs"),
            Pattern.compile("org\\.eclipse\\.jetty")
    );
    private static final Set<String> BLOCKED_ARTIFACTS = Set.of(
            "io.helidon.webserver:helidon-webserver-http",
            "io.helidon.service:helidon-service-inject"
    );

    /**
     * Maps DW bundle artifactId patterns to the third-party transitive dependencies they carried.
     * When a DW bundle is removed from the POM, these transitive deps disappear from the classpath.
     * We re-add them explicitly to the Helidon POM so library code (Hibernate, Liquibase, etc.)
     * continues to compile.
     */
    private static final Map<String, List<String[]>> BUNDLE_TO_DEPS = Map.of(
            "dropwizard-hibernate", List.<String[]>of(
                    new String[]{"org.hibernate.orm", "hibernate-core", "6.4.4.Final"},
                    new String[]{"jakarta.persistence", "jakarta.persistence-api", "3.1.0"}),
            "dropwizard-db", List.<String[]>of(
                    new String[]{"com.zaxxer", "HikariCP", "5.1.0"}),
            "dropwizard-migrations", List.<String[]>of(
                    new String[]{"org.liquibase", "liquibase-core", "4.27.0"}),
            "dropwizard-views-freemarker", List.<String[]>of(
                    new String[]{"org.freemarker", "freemarker", "2.3.32"}),
            "dropwizard-views-mustache", List.<String[]>of(
                    new String[]{"com.github.spullara.mustache.java", "compiler", "0.9.14"}),
            "dropwizard-jdbi3", List.<String[]>of(
                    new String[]{"org.jdbi", "jdbi3-core", "3.45.0"},
                    new String[]{"org.jdbi", "jdbi3-sqlobject", "3.45.0"})
    );

    public String merge(Path originalPom, String template) throws IOException {
        var original = Files.readString(originalPom);

        var identity = parseIdentity(original);
        var props = parseProperties(original);
        var extraDeps = collectNonDropwizardDependencies(original);
        var extraDepMgmt = collectDependencyManagement(original);
        var bundleDeps = collectBundleTransitiveDeps(original, extraDeps);
        extraDeps.addAll(bundleDeps);

        var result = template
                .replace("${project.groupId}", identity.getOrDefault("groupId", "com.example"))
                .replace("${project.artifactId}", identity.getOrDefault("artifactId", "app"))
                .replace("${project.version}", identity.getOrDefault("version", "1.0.0-SNAPSHOT"))
                .replace("${project.name}", identity.getOrDefault("name", "Migrated Application"))
                .replace("${project.mainClass}", props.getOrDefault("mainClass", "Main"));

        result = injectProperties(result, props);
        result = injectDependencyManagement(result, extraDepMgmt);
        result = injectExtraDependencies(result, extraDeps);

        return result;
    }

    private Map<String, String> parseIdentity(String pom) {
        var projectSection = pom;
        var projectMatcher = PROJECT_BLOCK.matcher(pom);
        if (projectMatcher.find()) {
            projectSection = projectMatcher.group(1);
        }
        projectSection = PARENT_BLOCK.matcher(projectSection).replaceAll("");
        var values = new HashMap<String, String>();
        Matcher m = TAG.matcher(projectSection);
        while (m.find()) {
            var tag = m.group(1);
            if (!values.containsKey(tag)) {
                values.put(tag, m.group(2).trim());
            }
        }
        return values;
    }

    private Map<String, String> parseProperties(String pom) {
        var props = new HashMap<String, String>();
        var propBlock = Pattern.compile("(?s)<properties>\\s*(.*?)\\s*</properties>").matcher(pom);
        if (propBlock.find()) {
            var body = propBlock.group(1);
            var propTag = Pattern.compile("<([a-zA-Z0-9_.-]+)>\\s*([^<]+)\\s*</\\1>");
            var matcher = propTag.matcher(body);
            while (matcher.find()) {
                props.put(matcher.group(1).trim(), matcher.group(2).trim());
            }
        }
        return props;
    }

    private List<String> collectNonDropwizardDependencies(String pom) {
        var extra = new ArrayList<String>();
        var seen = new HashSet<String>();

        var withoutDepMgmt = DEP_MGMT_BLOCK.matcher(pom).replaceAll("");
        var matcher = DEP_BLOCK.matcher(withoutDepMgmt);
        while (matcher.find()) {
            var block = matcher.group(0);
            var coords = DEP_COORDS.matcher(block);
            if (!coords.find()) {
                continue;
            }
            var groupId = coords.group(1).trim();
            var artifactId = coords.group(2).trim();
            var key = groupId + ":" + artifactId;

            if (isDropwizardDependency(groupId, artifactId)) {
                continue;
            }
            if (isHelidonDependency(groupId)) {
                continue;
            }
            if (BLOCKED_ARTIFACTS.contains(key)) {
                continue;
            }
            if (isImportScopedBom(block)) {
                continue;
            }
            if (seen.add(key)) {
                extra.add(block.trim());
            }
        }
        return extra;
    }

    private List<String> collectDependencyManagement(String pom) {
        var extra = new ArrayList<String>();
        var seen = new HashSet<String>();
        var depMgmt = DEP_MGMT_BLOCK.matcher(pom);
        if (!depMgmt.find()) {
            return extra;
        }
        var body = depMgmt.group(1);
        var matcher = DEP_BLOCK.matcher(body);
        while (matcher.find()) {
            var block = matcher.group(0);
            var coords = DEP_COORDS.matcher(block);
            if (!coords.find()) {
                continue;
            }
            var groupId = coords.group(1).trim();
            var artifactId = coords.group(2).trim();
            var key = groupId + ":" + artifactId;

            if (isDropwizardDependency(groupId, artifactId)) {
                continue;
            }
            if (isHelidonDependency(groupId)) {
                continue;
            }
            if (BLOCKED_ARTIFACTS.contains(key)) {
                continue;
            }
            if (seen.add(key)) {
                extra.add(block.trim());
            }
        }
        return extra;
    }

    private boolean isDropwizardDependency(String groupId, String artifactId) {
        var ga = groupId + ":" + artifactId;
        for (var pattern : DROPWIZARD_DEP_PATTERNS) {
            if (pattern.matcher(groupId).find() || pattern.matcher(ga).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean isHelidonDependency(String groupId) {
        return groupId.startsWith("io.helidon");
    }

    private String injectProperties(String template, Map<String, String> props) {
        if (props.isEmpty()) {
            return template;
        }
        var propBlock = Pattern.compile("(?s)<properties>\\s*(.*?)\\s*</properties>").matcher(template);
        if (!propBlock.find()) {
            return template;
        }
        var existing = propBlock.group(1);
        var sb = new StringBuilder(existing);
        for (var entry : props.entrySet()) {
            if (existing.contains("<" + entry.getKey() + ">")) {
                continue;
            }
            sb.append("        <").append(entry.getKey()).append(">")
              .append(entry.getValue())
              .append("</").append(entry.getKey()).append(">\n");
        }
        var replacement = "<properties>\n" + sb + "    </properties>";
        return propBlock.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private String injectExtraDependencies(String template, List<String> extraDeps) {
        if (extraDeps.isEmpty()) {
            return template;
        }
        var deps = Pattern.compile("(?s)<dependencies>\\s*(.*?)\\s*</dependencies>").matcher(template);
        if (!deps.find()) {
            return template;
        }
        var existing = deps.group(1);
        var sb = new StringBuilder(existing);
        for (var dep : extraDeps) {
            sb.append("\n        ").append(dep.replace("\n", "\n        ")).append("\n");
        }
        var replacement = "<dependencies>\n" + sb + "    </dependencies>";
        return deps.replaceFirst(Matcher.quoteReplacement(replacement));
    }

    private String injectDependencyManagement(String template, List<String> extraDepMgmt) {
        if (extraDepMgmt.isEmpty()) {
            return template;
        }
        var block = new StringBuilder();
        block.append("    <dependencyManagement>\n")
             .append("        <dependencies>\n");
        for (var dep : extraDepMgmt) {
            block.append("            ").append(dep.replace("\n", "\n            ")).append("\n");
        }
        block.append("        </dependencies>\n")
             .append("    </dependencyManagement>\n");

        var propsBlock = Pattern.compile("(?s)</properties>\\s*").matcher(template);
        if (propsBlock.find()) {
            return propsBlock.replaceFirst(Matcher.quoteReplacement("</properties>\n\n" + block));
        }
        var depsBlock = Pattern.compile("(?s)<dependencies>\\s*").matcher(template);
        if (depsBlock.find()) {
            return depsBlock.replaceFirst(Matcher.quoteReplacement(block + "<dependencies>\n"));
        }
        return template + "\n" + block;
    }

    private List<String> collectBundleTransitiveDeps(String originalPom, List<String> alreadyCollected) {
        var result = new ArrayList<String>();
        var existingKeys = new HashSet<String>();

        for (var depBlock : alreadyCollected) {
            var coords = DEP_COORDS.matcher(depBlock);
            if (coords.find()) {
                existingKeys.add(coords.group(1).trim() + ":" + coords.group(2).trim());
            }
        }

        for (var entry : BUNDLE_TO_DEPS.entrySet()) {
            var bundleArtifactId = entry.getKey();
            if (!originalPom.contains(bundleArtifactId)) {
                continue;
            }
            for (var coords : entry.getValue()) {
                var groupId = coords[0];
                var artifactId = coords[1];
                var version = coords[2];
                var key = groupId + ":" + artifactId;
                if (existingKeys.add(key)) {
                    result.add("<dependency>\n"
                            + "    <groupId>" + groupId + "</groupId>\n"
                            + "    <artifactId>" + artifactId + "</artifactId>\n"
                            + "    <version>" + version + "</version>\n"
                            + "</dependency>");
                    System.out.println("  [pom] Added transitive dep from removed bundle '"
                            + bundleArtifactId + "': " + key);
                }
            }
        }
        return result;
    }

    private boolean isImportScopedBom(String depBlock) {
        if (depBlock == null) {
            return false;
        }
        return depBlock.contains("<scope>import</scope>")
                || depBlock.contains("<type>pom</type>");
    }
}
