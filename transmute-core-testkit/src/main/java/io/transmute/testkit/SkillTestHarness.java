package io.transmute.testkit;

import dev.langchain4j.model.chat.ChatModel;
import io.transmute.catalog.MigrationLog;
import io.transmute.catalog.ProjectState;
import io.transmute.inventory.ProjectInventory;
import io.transmute.skill.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Test harness for {@link MigrationSkill} implementations.
 *
 * <p>Builds a {@link SkillContext} with an in-memory {@link ProjectInventory}, a
 * temporary-directory {@link Workspace}, and an optional mock {@link ChatModel},
 * executes the skill, and returns the {@link SkillResult} for assertions.
 *
 * <p>Example:
 * <pre>{@code
 *   @Test
 *   void mySkillProducesNoChange() throws Exception {
 *       var result = SkillTestHarness.forSkill(new MySkill())
 *           .withInventory(inventory)
 *           .withTargetFile("/tmp/src/Foo.java")
 *           .run();
 *       assertTrue(result.success());
 *   }
 * }</pre>
 */
public class SkillTestHarness {

    private final MigrationSkill skill;
    private ProjectInventory inventory = new ProjectInventory();
    private final List<String> targetFiles = new ArrayList<>();
    private final List<String> compileErrors = new ArrayList<>();
    private ChatModel model;
    private Path tempDir;

    private SkillTestHarness(MigrationSkill skill) {
        this.skill = skill;
    }

    public static SkillTestHarness forSkill(MigrationSkill skill) {
        return new SkillTestHarness(skill);
    }

    public SkillTestHarness withInventory(ProjectInventory inventory) {
        this.inventory = inventory;
        return this;
    }

    public SkillTestHarness withTargetFile(String file) {
        targetFiles.add(file);
        return this;
    }

    public SkillTestHarness withCompileError(String error) {
        compileErrors.add(error);
        return this;
    }

    public SkillTestHarness withModel(ChatModel model) {
        this.model = model;
        return this;
    }

    /**
     * Executes the skill and returns its result.
     *
     * @throws Exception propagated from {@link MigrationSkill#apply(SkillContext)}
     */
    public SkillResult run() throws Exception {
        if (tempDir == null) {
            tempDir = Files.createTempDirectory("transmute-test-");
        }

        var workspace = new Workspace(
                tempDir.resolve("source").toString(),
                tempDir.resolve("output").toString(),
                false
        );
        Files.createDirectories(Path.of(workspace.sourceDir()));
        Files.createDirectories(Path.of(workspace.outputDir()));

        var projectState = new ProjectState();
        var migrationLog = new MigrationLog();

        var ctx = new SkillContext(
                inventory,
                targetFiles,
                projectState,
                compileErrors,
                model,
                workspace,
                migrationLog
        );

        return skill.apply(ctx);
    }

    /**
     * Deletes the temporary directory used during the test (call in {@code @AfterEach}).
     */
    public void cleanup() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }
}
