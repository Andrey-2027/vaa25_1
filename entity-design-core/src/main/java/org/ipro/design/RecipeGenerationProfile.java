package org.ipro.design;

import java.util.Map;
import java.util.Optional;

/** Exact, portable mapping to implemented builders. Descriptions do not manufacture implementations. */
public record RecipeGenerationProfile(Builder builder, String generatorVersion, String fingerprintProfile) {
    public RecipeGenerationProfile {
        java.util.Objects.requireNonNull(builder);
        if (generatorVersion == null || generatorVersion.isBlank())
            throw new IllegalArgumentException("Generator version is required");
        if (fingerprintProfile == null || fingerprintProfile.isBlank())
            throw new IllegalArgumentException("Fingerprint profile is required");
    }
    public enum Builder { STANDARD_ROOT, INDEPENDENT_REGISTER }
    private static final Map<RecipeRef, RecipeGenerationProfile> IMPLEMENTED = Map.of(
        RecipeCatalog.STANDARD_CATALOG_V2, new RecipeGenerationProfile(Builder.STANDARD_ROOT,
            "catalog-java-v4", "plan-fingerprint-v2"),
        RecipeCatalog.STANDARD_DOCUMENT, new RecipeGenerationProfile(Builder.STANDARD_ROOT,
            "document-java-v4", "plan-fingerprint-v2"),
        RecipeCatalog.INDEPENDENT_REGISTER, new RecipeGenerationProfile(Builder.INDEPENDENT_REGISTER,
            "register-java-v1", "plan-fingerprint-v3"),
        RecipeCatalog.INDEPENDENT_REGISTER_RLS, new RecipeGenerationProfile(Builder.INDEPENDENT_REGISTER,
            "register-java-v2", "plan-fingerprint-v4"));

    public static Optional<RecipeGenerationProfile> find(RecipeRef recipe) {
        return Optional.ofNullable(IMPLEMENTED.get(recipe));
    }
}
