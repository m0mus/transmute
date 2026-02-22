package io.transmute.skill.annotation;

import io.transmute.skill.MigrationSkill;

/**
 * Creates parameterized {@link MigrationSkill} instances from a {@link SkillConfig}.
 *
 * <p>Reference via {@link Skill#factory()}. When no factory is needed use the
 * sentinel {@link None}.
 */
public interface SkillFactory<T extends MigrationSkill> {

    T create(SkillConfig config);

    /**
     * Sentinel used when {@code @Skill} does not specify a factory.
     */
    class None implements SkillFactory<MigrationSkill> {
        @Override
        public MigrationSkill create(SkillConfig config) {
            throw new UnsupportedOperationException("SkillFactory.None must not be instantiated");
        }
    }
}
