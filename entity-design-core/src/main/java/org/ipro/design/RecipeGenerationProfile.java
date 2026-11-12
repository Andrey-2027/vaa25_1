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
    private static final Map<RecipeRef, RecipeGenerationProfile> IMPLEMENTED = Map.ofEntries(
        Map.entry(RecipeCatalog.CUSTOM_PLAIN_V2, new RecipeGenerationProfile(Builder.STANDARD_ROOT,
            "plain-java-v1", "plan-fingerprint-v2")),
        Map.entry(RecipeCatalog.STANDARD_CATALOG_V4, new RecipeGenerationProfile(Builder.STANDARD_ROOT, "catalog-java-v6", "plan-fingerprint-v5")),
        Map.entry(RecipeCatalog.STANDARD_DOCUMENT_V3, new RecipeGenerationProfile(Builder.STANDARD_ROOT, "document-java-v6", "plan-fingerprint-v5")),
        Map.entry(RecipeCatalog.INDEPENDENT_REGISTER_V5, new RecipeGenerationProfile(Builder.INDEPENDENT_REGISTER, "register-java-v5", "plan-fingerprint-v5")),
        Map.entry(RecipeCatalog.INDEPENDENT_REGISTER_RLS_V6, new RecipeGenerationProfile(Builder.INDEPENDENT_REGISTER, "register-java-v6", "plan-fingerprint-v5")),
        Map.entry(RecipeCatalog.STANDARD_CATALOG_V3, new RecipeGenerationProfile(Builder.STANDARD_ROOT,
            "catalog-java-v5", "plan-fingerprint-v5")),
        Map.entry(RecipeCatalog.STANDARD_DOCUMENT_V2, new RecipeGenerationProfile(Builder.STANDARD_ROOT,
            "document-java-v5", "plan-fingerprint-v5")),
        Map.entry(RecipeCatalog.INDEPENDENT_REGISTER_V3, new RecipeGenerationProfile(Builder.INDEPENDENT_REGISTER,
            "register-java-v3", "plan-fingerprint-v5")),
        Map.entry(RecipeCatalog.INDEPENDENT_REGISTER_RLS_V4, new RecipeGenerationProfile(Builder.INDEPENDENT_REGISTER,
            "register-java-v4", "plan-fingerprint-v5")),
        Map.entry(RecipeCatalog.STANDARD_CATALOG_V2, new RecipeGenerationProfile(Builder.STANDARD_ROOT,
            "catalog-java-v4", "plan-fingerprint-v2")),
        Map.entry(RecipeCatalog.STANDARD_DOCUMENT, new RecipeGenerationProfile(Builder.STANDARD_ROOT,
            "document-java-v4", "plan-fingerprint-v2")),
        Map.entry(RecipeCatalog.INDEPENDENT_REGISTER, new RecipeGenerationProfile(Builder.INDEPENDENT_REGISTER,
            "register-java-v1", "plan-fingerprint-v3")),
        Map.entry(RecipeCatalog.INDEPENDENT_REGISTER_RLS, new RecipeGenerationProfile(Builder.INDEPENDENT_REGISTER,
            "register-java-v2", "plan-fingerprint-v4")));

    public static Optional<RecipeGenerationProfile> find(RecipeRef recipe) {
        return Optional.ofNullable(IMPLEMENTED.get(recipe));
    }
}
