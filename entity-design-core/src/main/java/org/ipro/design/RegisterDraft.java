package org.ipro.design;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Register-specific part of a draft: ordered role references by field UUID plus the period mode.
 * Roles reference fields rather than duplicating them, so renaming a field keeps its role and
 * removing a field leaves a detectable unknown reference instead of silently re-assigning it.
 */
public record RegisterDraft(String period, String writeProfile, List<UUID> dimensions, List<UUID> resources) {
    public static final String NONE = "NONE", DAY = "DAY";
    public static final String NAMED_AND_CANONICAL_CRUD = "NAMED_AND_CANONICAL_CRUD";
    public static final int MAX_DIMENSION_STRING_LENGTH = 200, MAX_RESOURCE_STRING_LENGTH = 4000, MAX_RESOURCE_PRECISION = 19;
    public static final List<RecipeCatalog.FieldType> DIMENSION_TYPES = List.of(RecipeCatalog.FieldType.STRING,
        RecipeCatalog.FieldType.INTEGER, RecipeCatalog.FieldType.LONG, RecipeCatalog.FieldType.REFERENCE);
    public static final List<RecipeCatalog.FieldType> RESOURCE_TYPES = List.of(RecipeCatalog.FieldType.STRING,
        RecipeCatalog.FieldType.INTEGER, RecipeCatalog.FieldType.LONG, RecipeCatalog.FieldType.DECIMAL, RecipeCatalog.FieldType.BOOLEAN);
    public RegisterDraft {
        Objects.requireNonNull(period); Objects.requireNonNull(writeProfile);
        dimensions = List.copyOf(dimensions); resources = List.copyOf(resources);
        dimensions.forEach(Objects::requireNonNull); resources.forEach(Objects::requireNonNull);
    }
    public static RegisterDraft none() { return new RegisterDraft(NONE, NAMED_AND_CANONICAL_CRUD, List.of(), List.of()); }
    public static RegisterDraft day() { return new RegisterDraft(DAY, NAMED_AND_CANONICAL_CRUD, List.of(), List.of()); }
    public boolean periodic() { return DAY.equals(period); }
    public RegisterDraft withPeriod(String value) { return new RegisterDraft(value, writeProfile, dimensions, resources); }
    public RegisterDraft withDimensions(List<UUID> value) { return new RegisterDraft(period, writeProfile, value, resources); }
    public RegisterDraft withResources(List<UUID> value) { return new RegisterDraft(period, writeProfile, dimensions, value); }
}
