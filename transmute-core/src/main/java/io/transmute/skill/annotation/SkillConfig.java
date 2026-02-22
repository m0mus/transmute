package io.transmute.skill.annotation;

import java.util.Map;
import java.util.Optional;

/**
 * Map-backed configuration passed to a {@link SkillFactory} when constructing
 * parameterized skills.
 */
public record SkillConfig(Map<String, String> params) {

    public SkillConfig {
        params = Map.copyOf(params);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(params.get(key));
    }

    public String require(String key) {
        var value = params.get(key);
        if (value == null) {
            throw new IllegalStateException("Required SkillConfig parameter missing: " + key);
        }
        return value;
    }
}
