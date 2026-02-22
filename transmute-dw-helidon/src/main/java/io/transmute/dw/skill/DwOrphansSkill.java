package io.transmute.dw.skill;

import io.transmute.dw.rewrite.DwOrphansHandler;
import io.transmute.skill.FileChange;
import io.transmute.skill.MigrationSkill;
import io.transmute.skill.SkillContext;
import io.transmute.skill.SkillResult;
import io.transmute.skill.annotation.Skill;
import io.transmute.skill.annotation.SkillScope;
import io.transmute.skill.annotation.Trigger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies DW_POJO, DW_COMMENT, and DW_REMOVE transformations to all Java files
 * in the output directory.
 *
 * <p>Triggered when any file imports Dropwizard or Codahale classes.
 * Runs PROJECT-wide (not per-file) because DwOrphansHandler walks the directory itself.
 * Runs after JaxrsAnnotationsSkill (order=10) so JAX-RS resource classes are migrated first.
 */
@Skill(value = "DW Orphans", order = 10, scope = SkillScope.PROJECT,
       after = {JaxrsAnnotationsSkill.class})
@Trigger(imports = {"io.dropwizard.", "com.codahale.metrics."})
public class DwOrphansSkill implements MigrationSkill {

    @Override
    public SkillResult apply(SkillContext ctx) throws Exception {
        var workspace = ctx.workspace();
        var outputDir = Path.of(workspace.outputDir());

        // Snapshot all java file contents before transformation
        var beforeContents = snapshotJavaFiles(outputDir);

        var handler = new DwOrphansHandler();
        var result = handler.handle(outputDir);

        if (result.filesModified() == 0) {
            return SkillResult.noChange();
        }

        // Build FileChange list from modified paths
        var changes = new ArrayList<FileChange>();
        for (var relativePath : result.modifiedPaths()) {
            var file = outputDir.resolve(relativePath);
            String before = beforeContents.getOrDefault(file.toString(), "");
            String after;
            try {
                after = Files.readString(file);
            } catch (IOException e) {
                after = before;
            }
            changes.add(new FileChange(file.toString(), before, after));
        }

        return SkillResult.success(changes, List.of(),
                "DW orphan handler modified " + result.filesModified() + " file(s)");
    }

    private java.util.Map<String, String> snapshotJavaFiles(Path dir) {
        var snapshot = new java.util.HashMap<String, String>();
        try {
            Files.walk(dir)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target"))
                    .forEach(p -> {
                        try {
                            snapshot.put(p.toString(), Files.readString(p));
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
        return snapshot;
    }
}
