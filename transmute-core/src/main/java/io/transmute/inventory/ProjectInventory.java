package io.transmute.inventory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The full structural inventory of a scanned project.
 * Accumulated by {@link JavaProjectVisitor} and serializable to JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectInventory {

    private int schemaVersion = 1;
    private String generatedAt = Instant.now().toString();
    private String project;
    private String rootDir;

    private List<JavaFileInfo> javaFiles = new ArrayList<>();
    private List<DependencyInfo> dependencies = new ArrayList<>();
    private List<DependencyInfo> transitiveDependencies = new ArrayList<>();
    private List<ModuleInfo> modules = new ArrayList<>();
    private Set<String> signals = new HashSet<>();
    private List<String> warnings = new ArrayList<>();
    private List<String> errors = new ArrayList<>();

    public ProjectInventory() {}

    // ── Mutators ──────────────────────────────────────────────────────────────

    public void addSignal(String signal) {
        signals.add(signal);
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    public void addError(String error) {
        errors.add(error);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public JavaFileInfo fileByPath(String path) {
        return javaFiles.stream()
                .filter(f -> f.sourceFile().equals(path))
                .findFirst()
                .orElse(null);
    }

    public List<JavaFileInfo> filesByAnnotation(String annotationType) {
        return javaFiles.stream()
                .filter(f -> f.annotationTypes().contains(annotationType))
                .toList();
    }

    public List<JavaFileInfo> filesByImport(String prefix) {
        return javaFiles.stream()
                .filter(f -> f.imports().stream().anyMatch(i -> i.startsWith(prefix)))
                .toList();
    }

    public List<JavaFileInfo> filesBySuperType(String superType) {
        return javaFiles.stream()
                .filter(f -> f.superTypes().contains(superType))
                .toList();
    }

    // ── Getters/Setters ───────────────────────────────────────────────────────

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

    public String getProject() { return project; }
    public void setProject(String project) { this.project = project; }

    public String getRootDir() { return rootDir; }
    public void setRootDir(String rootDir) { this.rootDir = rootDir; }

    public List<JavaFileInfo> getJavaFiles() { return javaFiles; }
    public void setJavaFiles(List<JavaFileInfo> javaFiles) { this.javaFiles = javaFiles; }

    public List<DependencyInfo> getDependencies() { return dependencies; }
    public void setDependencies(List<DependencyInfo> dependencies) { this.dependencies = dependencies; }

    public List<DependencyInfo> getTransitiveDependencies() { return transitiveDependencies; }
    public void setTransitiveDependencies(List<DependencyInfo> transitiveDependencies) { this.transitiveDependencies = transitiveDependencies; }

    public List<ModuleInfo> getModules() { return modules; }
    public void setModules(List<ModuleInfo> modules) { this.modules = modules; }

    public Set<String> getSignals() { return signals; }
    public void setSignals(Set<String> signals) { this.signals = signals; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }

    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }
}
