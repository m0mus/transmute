package io.transmute.dw.skill;

import io.transmute.dw.rewrite.JaxrsAnnotationsMigrator;
import io.transmute.skill.FileChange;
import io.transmute.skill.MigrationSkill;
import io.transmute.skill.SkillContext;
import io.transmute.skill.SkillResult;
import io.transmute.skill.annotation.Postchecks;
import io.transmute.skill.annotation.Skill;
import io.transmute.skill.annotation.SkillScope;
import io.transmute.skill.annotation.Trigger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the full JAX-RS to Helidon annotation migration using OpenRewrite recipes.
 *
 * <p>Applies:
 * <ul>
 *   <li>{@link io.transmute.dw.rewrite.JaxrsToHelidonRecipe} -- adds Endpoint/Singleton class annotations, Entity param annotations</li>
 *   <li>{@code ChangeType} recipes -- maps all JAX-RS method/param annotations to Http.*</li>
 *   <li>{@link io.transmute.dw.rewrite.DropwizardParamRecipe} -- converts DW param wrapper types to Java types</li>
 *   <li>Response type migration, RemoveUnusedImports</li>
 *   <li>Text-based post-processing: Produces/Consumes TODOs, View migration, @Context, media type TODOs</li>
 * </ul>
 *
 * <p>Runs PROJECT-wide (order=5), after PomMigrationSkill.
 */
@Skill(value = "JAX-RS Annotations", order = 5, scope = SkillScope.PROJECT,
       after = {PomMigrationSkill.class})
@Trigger(imports = {"javax.ws.rs.", "jakarta.ws.rs."})
@Postchecks(forbidImports = {"javax.ws.rs.", "jakarta.ws.rs."})
public class JaxrsAnnotationsSkill implements MigrationSkill {

    @Override
    public SkillResult apply(SkillContext ctx) throws Exception {
        var workspace = ctx.workspace();
        var outputDir = Path.of(workspace.outputDir());

        var beforeContents = snapshotJavaFiles(outputDir);

        var migrator = new JaxrsAnnotationsMigrator();
        var result = migrator.apply(outputDir);

        if (result.changedFiles() == 0) {
            return SkillResult.noChange();
        }

        var changes = new ArrayList<FileChange>();
        for (var relativePath : result.changedPaths()) {
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
                "JAX-RS migration: " + result.changedFiles() + "/" + result.totalFiles() + " files changed");
    }

    private Map<String, String> snapshotJavaFiles(Path dir) {
        var snapshot = new HashMap<String, String>();
        try {
            Files.walk(dir)
                    .filter(p -> p.toString().endsWith(".java"))
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
