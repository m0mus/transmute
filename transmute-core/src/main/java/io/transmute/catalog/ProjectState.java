package io.transmute.catalog;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable, thread-safe state store shared across all skills in a migration run.
 */
public class ProjectState {

    private final ConcurrentHashMap<String, Object> state = new ConcurrentHashMap<>();

    public <T> void put(String key, T value) {
        state.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(String key, Class<T> type) {
        var value = state.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }

    public boolean containsKey(String key) {
        return state.containsKey(key);
    }
}
