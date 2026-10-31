package org.ipro.design.generation;

import org.ipro.design.CheckedStructure;
import org.ipro.design.EntityDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeCatalog;

/** Compatible catalog-v2 entry: output bytes and fingerprint v1 remain unchanged. */
public final class CatalogJavaGenerator {
    public static final String GENERATOR_VERSION = "catalog-java-v1";
    public GenerationResult generate(CheckedStructure checked, EntityDraft currentDraft,
                                     ProjectDesignContext currentContext) {
        return new StandardRootJavaGenerator(RecipeCatalog.STANDARD_CATALOG_V2, GENERATOR_VERSION,
                GenerationManifest.LEGACY_FINGERPRINT).generate(checked, currentDraft, currentContext);
    }
}
