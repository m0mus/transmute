package io.transmute.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single entry in a migration module's dependency catalog.
 *
 * <p>Catalog files are YAML lists of these entries, loaded by
 * {@link MarkdownMigrationLoader#loadCatalog()}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DependencyCatalogEntry(
        @JsonProperty("groupId")    String groupId,
        @JsonProperty("artifactId") String artifactId,
        @JsonProperty("status")     DependencyStatus status,
        @JsonProperty("replacedBy") String replacedBy,
        @JsonProperty("notes")      String notes
) {}
