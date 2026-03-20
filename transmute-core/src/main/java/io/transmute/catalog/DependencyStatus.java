package io.transmute.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Migration support status for a dependency in the dependency catalog.
 */
public enum DependencyStatus {
    @JsonProperty("replaced")    REPLACED,
    @JsonProperty("partial")     PARTIAL,
    @JsonProperty("unsupported") UNSUPPORTED,
    @JsonProperty("passthrough") PASSTHROUGH,
    UNKNOWN   // internal default; not deserialized from YAML
}
