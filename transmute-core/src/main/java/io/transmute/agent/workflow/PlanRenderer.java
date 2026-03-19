package io.transmute.agent.workflow;

import io.transmute.agent.TransmuteConfig;
import io.transmute.catalog.DependencyCatalogEntry;
import io.transmute.catalog.DependencyStatus;
import io.transmute.catalog.MarkdownMigrationLoader;
import io.transmute.catalog.MigrationPlan;
import io.transmute.inventory.DependencyInfo;
import io.transmute.inventory.JavaFileInfo;
import io.transmute.inventory.ProjectInventory;
import io.transmute.migration.AiMigration;
import io.transmute.migration.Migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Console plan display, save {@code migration-plan.txt} / {@code migration-untouched.txt},
 * and {@link #buildCatalogHints()}.
 */
class PlanRenderer {

    private final TransmuteConfig config;
    private final MigrationPlan plan;
    private final ProjectInventory inventory;
    private final List<Migration> migrations;
    private final MarkdownMigrationLoader.Catalog catalog;

    PlanRenderer(TransmuteConfig config, MigrationPlan plan, ProjectInventory inventory,
                 List<Migration> migrations, MarkdownMigrationLoader.Catalog catalog) {
        this.config = config;
        this.plan = plan;
        this.inventory = inventory;
        this.migrations = migrations;
        this.catalog = catalog;
    }

    void printAndSave() {
        var view = PlanView.build(plan, inventory);
        printPlanView(view);
        savePlanToFile(view);
    }

    String buildCatalogHints() {
        var relevant = inventory.getDependencies().stream()
                .filter(d -> {
                    var e = getCatalogEntry(d);
                    return e != null && (e.status() == DependencyStatus.UNSUPPORTED
                                     || e.status() == DependencyStatus.PARTIAL);
                })
                .toList();
        if (relevant.isEmpty()) return "";
        var sb = new StringBuilder("## Known Dependency Status\n");
        for (var dep : relevant) {
            var e = getCatalogEntry(dep);
            sb.append("- **").append(dep.groupId()).append(":").append(dep.artifactId())
              .append("** (").append(e.status().name().toLowerCase()).append(")");
            if (e.notes() != null && !e.notes().isBlank())
                sb.append(": ").append(e.notes());
            sb.append("\n");
        }
        return sb.toString();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private DependencyCatalogEntry getCatalogEntry(DependencyInfo dep) {
        var entry = catalog.entries().get(dep.groupId() + ":" + dep.artifactId());
        if (entry == null) entry = catalog.entries().get(dep.groupId() + ":*");
        return entry;
    }

    private DependencyStatus getCatalogStatus(DependencyInfo dep) {
        var entry = getCatalogEntry(dep);
        return entry != null ? entry.status() : DependencyStatus.PASSTHROUGH;
    }

    private void printPlanView(PlanView v) {
        int covered    = v.coveredFiles().size();
        int notTargeted = (int) inventory.getJavaFiles().stream()
                .filter(f -> !v.coveredFiles().contains(f.sourceFile()))
                .filter(f -> !f.sourceFile().replace('\\', '/').contains("/test/"))
                .count();

        // ── Overview ──────────────────────────────────────────────────────────
        System.out.println();
        Out.sectionHeader("Project Overview");
        System.out.printf("  Source files:  %3d   │  Test files:     %3d%n", v.totalSrc(), v.totalTest());
        System.out.printf("  Covered:       %3d   │  Not targeted:   %3d%n", covered, notTargeted);
        System.out.printf("  Migrations:    %3d   │  Project-scoped: %3d%n",
                plan.entries().size(), v.projectScoped().size());

        // ── Dependencies ──────────────────────────────────────────────────────
        var deps = inventory.getDependencies();
        if (!deps.isEmpty()) {
            long replaced    = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.REPLACED).count();
            long partial     = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.PARTIAL).count();
            long unsupported = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.UNSUPPORTED).count();
            long unknown     = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.UNKNOWN).count();
            var subtitle = new java.util.StringJoiner(" · ");
            if (replaced    > 0) subtitle.add(replaced    + " replaced");
            if (partial     > 0) subtitle.add(partial     + " partial");
            if (unsupported > 0) subtitle.add(unsupported + " unsupported");
            if (unknown     > 0) subtitle.add(unknown     + " unknown");
            System.out.println();
            Out.sectionHeader("Dependencies", subtitle.toString());
            for (var dep : deps) {
                var coord = dep.groupId() + ":" + dep.artifactId()
                        + (dep.version() != null && !dep.version().isBlank() ? ":" + dep.version() : "");
                var status = getCatalogStatus(dep);
                var entry = getCatalogEntry(dep);
                var noteStr = (entry != null && entry.notes() != null && !entry.notes().isBlank())
                        ? Out.DIM + " - " + entry.notes() + Out.RESET : "";
                if (status == DependencyStatus.REPLACED) {
                    System.out.println("  " + Out.GREEN + "[+]" + Out.RESET + "  " + coord + noteStr);
                } else if (status == DependencyStatus.PARTIAL) {
                    System.out.println("  " + Out.YELLOW + "[~]" + Out.RESET + "  " + coord + noteStr);
                } else if (status == DependencyStatus.UNSUPPORTED) {
                    System.out.println("  " + Out.RED + "[!]" + Out.RESET + "  " + coord + noteStr);
                } else if (status == DependencyStatus.PASSTHROUGH) {
                    System.out.println("  " + Out.DIM + "[=]  " + coord + Out.RESET);
                } else {
                    System.out.println("  " + Out.DIM + "[?]  " + coord + Out.RESET);
                }
            }
            System.out.println("  " + Out.DIM
                    + "[+] replaced  [~] partial  [!] unsupported  [=] passthrough  [?] unknown"
                    + Out.RESET);
        }

        // ── Project-scoped ────────────────────────────────────────────────────
        if (!v.projectScoped().isEmpty()) {
            System.out.println();
            Out.sectionHeader("Project-scoped", "run once, not file-specific");
            for (var name : v.projectScoped()) {
                System.out.println("  " + Out.CYAN + "•" + Out.RESET + "  " + name);
            }
        }

        // ── File plan table ───────────────────────────────────────────────────
        if (!v.coveredFiles().isEmpty()) {
            System.out.println();
            Out.sectionHeader("File Migration Plan",
                    covered + " files · " + v.fileRecipes().size() + " with recipe · "
                    + v.fileFeatures().size() + " with features");
            int wFile    = 40;
            int wRecipe  = 28;
            System.out.println("  "
                    + Out.DIM + padRight("File", wFile) + "  "
                    + padRight("Recipe", wRecipe) + "  "
                    + "Features" + Out.RESET);
            System.out.println("  "
                    + Out.DIM + "─".repeat(wFile) + "  "
                    + "─".repeat(wRecipe) + "  "
                    + "─".repeat(44) + Out.RESET);
            for (var file : v.coveredFiles()) {
                var recipes  = v.fileRecipes().getOrDefault(file, List.of());
                var features = v.fileFeatures().getOrDefault(file, List.of());
                var fileInfo = inventory.fileByPath(file);
                var keyImports = fileInfo != null
                        ? fileInfo.imports().stream()
                                .filter(i -> !i.startsWith("java.") && !i.startsWith("javax.")
                                        && !i.startsWith("jakarta.") && !i.startsWith("org.junit")
                                        && !i.startsWith("org.mockito"))
                                .limit(2).toList()
                        : List.<String>of();

                var featStr = features.isEmpty() ? Out.DIM + "—" + Out.RESET
                        : features.stream().collect(java.util.stream.Collectors.joining(", "));

                // Build the framework-hints column (key framework imports, dimmed)
                var importHint = keyImports.isEmpty() ? ""
                        : Out.DIM + "  [" + keyImports.stream()
                                .map(i -> i.substring(i.lastIndexOf('.') + 1))
                                .collect(java.util.stream.Collectors.joining(", ")) + "]" + Out.RESET;

                String recipePadded = recipes.isEmpty()
                        ? Out.DIM + padRight("—", wRecipe) + Out.RESET
                        : padRight(truncate(String.join(", ", recipes), wRecipe), wRecipe);
                System.out.println("  "
                        + padRight(truncate(shortPath(file), wFile), wFile) + "  "
                        + recipePadded + "  "
                        + featStr + importHint);
            }
        }

        // ── Not targeted ──────────────────────────────────────────────────────
        var untouched = inventory.getJavaFiles().stream()
                .filter(f -> !v.coveredFiles().contains(f.sourceFile()))
                .filter(f -> !f.sourceFile().replace('\\', '/').contains("/test/"))
                .map(f -> shortPath(f.sourceFile()))
                .toList();
        if (!untouched.isEmpty()) {
            System.out.println();
            Out.sectionHeader("Not targeted", untouched.size() + " files  (see migration-untouched.txt)");
            int shown = Math.min(8, untouched.size());
            for (int i = 0; i < shown; i++) {
                System.out.println("  " + Out.DIM + "·  " + untouched.get(i) + Out.RESET);
            }
            if (untouched.size() > shown) {
                System.out.println("  " + Out.DIM + "·  … " + (untouched.size() - shown) + " more" + Out.RESET);
            }
        }
    }

    private void savePlanToFile(PlanView v) {
        try {
            var sb = new StringBuilder();
            int covered     = v.coveredFiles().size();
            int notTargeted = (int) inventory.getJavaFiles().stream()
                    .filter(f -> !v.coveredFiles().contains(f.sourceFile()))
                    .filter(f -> !f.sourceFile().replace('\\', '/').contains("/test/"))
                    .count();

            txtSection(sb, "Migration Plan");
            sb.append("Generated : ").append(java.time.Instant.now()).append("\n");
            sb.append("Project   : ").append(config.projectDir()).append("\n\n");

            txtSection(sb, "Project Overview");
            sb.append(String.format("  Source files  : %d%n", v.totalSrc()));
            sb.append(String.format("  Test files    : %d%n", v.totalTest()));
            sb.append(String.format("  Covered       : %d%n", covered));
            sb.append(String.format("  Not targeted  : %d%n", notTargeted));
            sb.append(String.format("  Migrations    : %d%n", plan.entries().size()));
            sb.append(String.format("  Project-scoped: %d%n", v.projectScoped().size()));
            sb.append("\n");

            var deps = inventory.getDependencies();
            if (!deps.isEmpty()) {
                long replaced    = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.REPLACED).count();
                long partial     = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.PARTIAL).count();
                long unsupported = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.UNSUPPORTED).count();
                long unknown     = deps.stream().filter(d -> getCatalogStatus(d) == DependencyStatus.UNKNOWN).count();
                var counts = new java.util.StringJoiner(" · ");
                if (replaced    > 0) counts.add(replaced    + " replaced");
                if (partial     > 0) counts.add(partial     + " partial");
                if (unsupported > 0) counts.add(unsupported + " unsupported");
                if (unknown     > 0) counts.add(unknown     + " unknown");
                txtSection(sb, "Dependencies  (" + counts + ")");
                for (var dep : deps) {
                    var status = getCatalogStatus(dep);
                    var symbol = switch (status) {
                        case REPLACED    -> "[+]";
                        case PARTIAL     -> "[~]";
                        case UNSUPPORTED -> "[!]";
                        case PASSTHROUGH -> "[=]";
                        default          -> "[?]";
                    };
                    var coord = dep.groupId() + ":" + dep.artifactId()
                            + (dep.version() != null && !dep.version().isBlank() ? ":" + dep.version() : "");
                    var entry = getCatalogEntry(dep);
                    var note = (entry != null && entry.notes() != null && !entry.notes().isBlank())
                            ? "  - " + entry.notes() : "";
                    sb.append("  ").append(symbol).append("  ").append(coord).append(note).append("\n");
                }
                sb.append("\n");
            }

            if (!v.projectScoped().isEmpty()) {
                txtSection(sb, "Project-scoped Migrations");
                for (var name : v.projectScoped()) {
                    sb.append("  - ").append(name).append("\n");
                }
                sb.append("\n");
            }

            if (!v.coveredFiles().isEmpty()) {
                txtSection(sb, "File Migration Plan");
                int wFile = v.coveredFiles().stream()
                        .mapToInt(f -> f.length()).max().orElse(40) + 2;
                int wRecipe = v.fileRecipes().values().stream()
                        .mapToInt(r -> String.join(", ", r).length()).max().orElse(20) + 2;
                var hdr = padRight("File", wFile) + padRight("Recipe", wRecipe) + "Features";
                sb.append("  ").append(hdr).append("\n");
                sb.append("  ").append("─".repeat(hdr.length())).append("\n");
                for (var file : v.coveredFiles()) {
                    var recipes  = v.fileRecipes().getOrDefault(file, List.of());
                    var features = v.fileFeatures().getOrDefault(file, List.of());
                    sb.append("  ")
                            .append(padRight(file, wFile))
                            .append(padRight(recipes.isEmpty() ? "—" : String.join(", ", recipes), wRecipe))
                            .append(features.isEmpty() ? "—" : String.join(", ", features))
                            .append("\n");
                }
                sb.append("\n");
            }

            var untouched = inventory.getJavaFiles().stream()
                    .filter(f -> !v.coveredFiles().contains(f.sourceFile()))
                    .filter(f -> !f.sourceFile().replace('\\', '/').contains("/test/"))
                    .map(JavaFileInfo::sourceFile)
                    .toList();
            if (!untouched.isEmpty()) {
                txtSection(sb, "Not Targeted  (" + untouched.size() + " files)");
                for (var f : untouched) {
                    sb.append("  ").append(f).append("\n");
                }
                sb.append("  (see migration-untouched.txt for trigger miss details)\n");
                sb.append("\n");
            }

            if (!plan.entries().isEmpty()) {
                txtSection(sb, "Migration Coverage");
                int wName = plan.entries().stream()
                        .mapToInt(e -> e.migration().name().length()).max().orElse(20) + 2;
                for (var entry : plan.entries()) {
                    var am = (AiMigration) entry.migration();
                    var scope = am.skillScope() == io.transmute.migration.MigrationScope.PROJECT
                            ? "project" : entry.targetFiles().size() + " files";
                    sb.append(String.format("  %-" + wName + "s  %s%n", entry.migration().name(), scope));
                }
                sb.append("\n");
            }

            var planPath = Path.of(config.outputDir()).resolve("migration-plan.txt");
            Files.writeString(planPath, sb.toString());
            System.out.println();
            Out.info(Out.DIM + "Plan saved → " + Out.RESET + planPath.getFileName());
            saveUntouchedDiagnostics(untouched);
        } catch (Exception e) {
            Out.warn("Could not save plan file: " + e.getMessage());
        }
    }

    private void saveUntouchedDiagnostics(List<String> untouchedFiles) {
        if (untouchedFiles.isEmpty()) return;
        try {
            var diagnostics = new io.transmute.catalog.MigrationPlanner().diagnoseUntouched(untouchedFiles, migrations, inventory);
            var sb = new StringBuilder();
            sb.append("Files Not Targeted\n");
            sb.append("──────────────────\n");
            sb.append("Generated : ").append(java.time.Instant.now()).append("\n\n");
            sb.append(untouchedFiles.size()).append(" source file(s) not targeted by any migration.\n\n");

            for (var file : untouchedFiles) {
                sb.append(file).append("\n");
                var fileInfo = inventory.fileByPath(file);
                if (fileInfo != null) {
                    var keyImports = fileInfo.imports().stream()
                            .filter(i -> !i.startsWith("java.") && !i.startsWith("org.junit")
                                    && !i.startsWith("org.mockito"))
                            .limit(5).toList();
                    if (!keyImports.isEmpty())
                        sb.append("  imports    : ").append(String.join(", ", keyImports)).append("\n");
                    if (!fileInfo.annotationTypes().isEmpty())
                        sb.append("  annotations: ").append(String.join(", ", fileInfo.annotationTypes())).append("\n");
                    var superTypes = fileInfo.superTypes().stream()
                            .filter(st -> !st.equals("java.lang.Object")).limit(3).toList();
                    if (!superTypes.isEmpty())
                        sb.append("  superTypes : ").append(String.join(", ", superTypes)).append("\n");
                }
                var reasons = diagnostics.get(file);
                if (reasons != null && !reasons.isEmpty()) {
                    for (var reason : reasons) {
                        sb.append("  ").append(reason).append("\n");
                    }
                } else {
                    sb.append("  (no migrations with file-level triggers)\n");
                }
                sb.append("\n");
            }

            var path = Path.of(config.outputDir()).resolve("migration-untouched.txt");
            Files.writeString(path, sb.toString());
            Out.info(Out.DIM + "Untouched diagnostics → " + Out.RESET + path.getFileName());
        } catch (Exception e) {
            Out.warn("Could not write migration-untouched.txt: " + e.getMessage());
        }
    }

    /** Appends a plain-text section header (title + underline of title.length()+1 dashes + blank line). */
    static void txtSection(StringBuilder sb, String title) {
        sb.append(title).append("\n");
        sb.append("─".repeat(title.length() + 1)).append("\n");
        sb.append("\n");
    }

    static String shortPath(String path) {
        var p = Path.of(path.replace('\\', '/'));
        int n = p.getNameCount();
        if (n >= 2) return p.getName(n - 2) + "/" + p.getFileName();
        return p.getFileName() != null ? p.getFileName().toString() : path;
    }

    static String padRight(String s, int width) {
        if (s == null) s = "";
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }
}
