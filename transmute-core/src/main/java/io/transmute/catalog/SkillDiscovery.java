package io.transmute.catalog;

import io.github.classgraph.ClassGraph;
import io.transmute.skill.MigrationSkill;
import io.transmute.skill.annotation.Skill;
import io.transmute.skill.annotation.SkillFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers {@link MigrationSkill} implementations and {@link SourceTypeRegistry} instances
 * on the classpath using ClassGraph.
 *
 * <p>Only scans packages declared in the caller-supplied list. Classes must be annotated
 * with {@link Skill} (for skills) or implement {@link SourceTypeRegistry} (for registries).
 */
public class SkillDiscovery {

    /**
     * Result container returned by {@link #discover(List)}.
     */
    public record DiscoveryResult(
            List<MigrationSkill> skills,
            List<SourceTypeRegistry> registries
    ) {}

    /**
     * Scans the given packages and instantiates all discovered skills and registries.
     *
     * @param skillsPackages packages to scan (e.g., {@code "com.example.migration.skills"})
     */
    public DiscoveryResult discover(List<String> skillsPackages) {
        if (skillsPackages == null || skillsPackages.isEmpty()) {
            return new DiscoveryResult(List.of(), List.of());
        }

        var skills = new ArrayList<MigrationSkill>();
        var registries = new ArrayList<SourceTypeRegistry>();

        try (var result = new ClassGraph()
                .acceptPackages(skillsPackages.toArray(String[]::new))
                .enableClassInfo()
                .enableAnnotationInfo()
                .scan()) {

            // Discover skills
            for (var classInfo : result.getClassesWithAnnotation(Skill.class)) {
                if (classInfo.isAbstract() || classInfo.isInterface()) {
                    continue;
                }
                try {
                    var cls = classInfo.loadClass(MigrationSkill.class);
                    var skillAnn = cls.getAnnotation(Skill.class);
                    var factoryCls = skillAnn.factory();

                    MigrationSkill instance;
                    if (factoryCls == SkillFactory.None.class) {
                        instance = cls.getDeclaredConstructor().newInstance();
                    } else {
                        var factory = factoryCls.getDeclaredConstructor().newInstance();
                        instance = factory.create(new io.transmute.skill.annotation.SkillConfig(java.util.Map.of()));
                    }
                    skills.add(instance);
                } catch (Exception e) {
                    System.err.println("[SkillDiscovery] Failed to instantiate "
                            + classInfo.getName() + ": " + e.getMessage());
                }
            }

            // Discover source type registries
            for (var classInfo : result.getClassesImplementing(SourceTypeRegistry.class)) {
                if (classInfo.isAbstract() || classInfo.isInterface()) {
                    continue;
                }
                try {
                    var cls = classInfo.loadClass(SourceTypeRegistry.class);
                    registries.add(cls.getDeclaredConstructor().newInstance());
                } catch (Exception e) {
                    System.err.println("[SkillDiscovery] Failed to instantiate registry "
                            + classInfo.getName() + ": " + e.getMessage());
                }
            }
        }

        return new DiscoveryResult(skills, registries);
    }
}
