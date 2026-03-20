package io.transmute.inventory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans a Java project directory and produces a {@link ProjectInventory}.
 *
 * <p>This is an inventory-layer concern: it drives {@link JavaProjectVisitor}
 * over the source tree using the OpenRewrite Java parser and returns the
 * accumulated result.
 */
public class JavaProjectScanner {

    /**
     * Scans the given source directory and returns its {@link ProjectInventory}.
     *
     * @param sourceDir absolute or relative path to the project root
     * @return populated inventory; never {@code null}
     */
    public ProjectInventory scan(String sourceDir) {
        var visitor = new JavaProjectVisitor();
        var inv = visitor.getInitialValue();
        inv.setRootDir(sourceDir);
        inv.setProject(Path.of(sourceDir).getFileName().toString());
        try {
            var parser = org.openrewrite.java.JavaParser.fromJavaVersion()
                    .logCompilationWarningsAndErrors(false)
                    .build();
            var sourceRoot = Path.of(sourceDir);
            try (var walk = Files.walk(sourceRoot)) {
                var javaFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
                if (!javaFiles.isEmpty()) {
                    var ctx = new org.openrewrite.InMemoryExecutionContext(e -> inv.addError(e.getMessage()));
                    var sources = parser.parse(javaFiles, sourceRoot, ctx).toList();
                    for (var source : sources) {
                        visitor.visit(source, inv);
                    }
                }
            }
        } catch (Exception e) {
            inv.addWarning("Inventory scan error: " + e.getMessage());
        }

        parseMavenDependencies(Path.of(sourceDir), inv);
        return inv;
    }

    private void parseMavenDependencies(Path projectRoot, ProjectInventory inv) {
        var pomFile = projectRoot.resolve("pom.xml");
        if (!Files.exists(pomFile)) return;
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            var doc = dbf.newDocumentBuilder().parse(pomFile.toFile());
            doc.getDocumentElement().normalize();

            // Collect property values for version resolution
            var properties = new java.util.HashMap<String, String>();
            NodeList propNodes = doc.getElementsByTagName("properties");
            if (propNodes.getLength() > 0) {
                var props = propNodes.item(0).getChildNodes();
                for (int i = 0; i < props.getLength(); i++) {
                    if (props.item(i) instanceof Element el) {
                        properties.put("${" + el.getTagName() + "}", el.getTextContent().trim());
                    }
                }
            }

            var deps = new ArrayList<DependencyInfo>();
            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                if (!(depNodes.item(i) instanceof Element dep)) continue;
                // Skip dependencies inside <dependencyManagement> by checking parent chain
                var parent = dep.getParentNode();
                boolean inManagement = false;
                while (parent != null) {
                    if (parent instanceof Element pe && pe.getTagName().equals("dependencyManagement")) {
                        inManagement = true;
                        break;
                    }
                    parent = parent.getParentNode();
                }
                if (inManagement) continue;

                var groupId    = text(dep, "groupId");
                var artifactId = text(dep, "artifactId");
                var version    = properties.getOrDefault(text(dep, "version"), text(dep, "version"));
                var scope      = text(dep, "scope");
                if (groupId != null && artifactId != null) {
                    deps.add(new DependencyInfo(groupId, artifactId, version, scope));
                }
            }
            inv.setDependencies(deps);
        } catch (Exception e) {
            inv.addWarning("Could not parse pom.xml for dependencies: " + e.getMessage());
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        var val = nl.item(0).getTextContent().trim();
        return val.isBlank() ? null : val;
    }
}
