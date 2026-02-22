package io.transmute.dw.skill;

import io.transmute.dw.pom.PomTemplateMerger;
import io.transmute.skill.FileChange;
import io.transmute.skill.MigrationSkill;
import io.transmute.skill.SkillContext;
import io.transmute.skill.SkillResult;
import io.transmute.skill.annotation.Skill;
import io.transmute.skill.annotation.SkillScope;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Replaces the project pom.xml with a Helidon 4 SE declarative template,
 * preserving groupId/artifactId/version/name and non-Dropwizard dependencies.
 *
 * <p>Runs once per project (PROJECT scope) and should be first (order=1) so all
 * subsequent skills compile against Helidon rather than Dropwizard artifacts.
 */
@Skill(value = "POM Migration", order = 1, scope = SkillScope.PROJECT)
public class PomMigrationSkill implements MigrationSkill {

    private static final String TEMPLATE_RESOURCE = "/templates/helidon-declarative-pom.xml";

    @Override
    public SkillResult apply(SkillContext ctx) throws Exception {
        var workspace = ctx.workspace();
        var outputDir = Path.of(workspace.outputDir());
        var outputPom = outputDir.resolve("pom.xml");

        if (!Files.exists(outputPom)) {
            return SkillResult.failure("pom.xml not found in output directory: " + outputPom);
        }

        String template = loadTemplate();
        if (template == null) {
            return SkillResult.failure("Helidon POM template not found on classpath: " + TEMPLATE_RESOURCE);
        }

        String before = Files.readString(outputPom);
        var merger = new PomTemplateMerger();
        String after = merger.merge(outputPom, template);

        if (before.equals(after)) {
            return SkillResult.noChange();
        }

        var change = new FileChange(outputPom.toString(), before, after);
        if (!workspace.isDryRun()) {
            Files.writeString(outputPom, after);
        }

        return SkillResult.success(List.of(change), List.of(), "POM migrated to Helidon 4 SE declarative");
    }

    private String loadTemplate() {
        try (InputStream is = PomMigrationSkill.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (is == null) {
                return null;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
