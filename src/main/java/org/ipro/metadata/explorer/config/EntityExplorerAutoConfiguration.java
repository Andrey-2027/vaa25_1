package org.ipro.metadata.explorer.config;

import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.explorer.EntitySummaryAssembler;
import org.ipro.metadata.explorer.SubsystemSummaryAssembler;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.rls.RlsDimensionRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-Configuration поверхности чтения метаданных — Entity Explorer
 * (пакет {@code org.ipro.metadata.explorer}) и шов переопределений надписей
 * ({@code org.ipro.metadata.facet}).
 *
 * <p>Бины не попадают в component-scan приложения (базовый пакет {@code org.ip}) —
 * регистрируются здесь, как остальные подсистемы платформы (см.
 * {@code org.ipro.metadata.config.MetadataAutoConfiguration}).</p>
 *
 * <p>{@link FacetResolver} — default no-op (роль 3, store-слой переопределений пока не
 * существует). Store-бин позже подставит своё переопределение.</p>
 */
@AutoConfiguration
public class EntityExplorerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FacetResolver facetResolver() {
        return FacetResolver.none();
    }

    @Bean
    @ConditionalOnMissingBean
    public SubsystemSummaryAssembler subsystemSummaryAssembler(
            EntitySummaryAssembler entitySummaryAssembler,
            SubsystemRegistry subsystemRegistry,
            RlsDimensionRegistry rlsDimensionRegistry) {
        return new SubsystemSummaryAssembler(
            entitySummaryAssembler, subsystemRegistry, rlsDimensionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public EntitySummaryAssembler entitySummaryAssembler(
            @Value("${platform.subsystem-scan-package:org.ip}") String basePackage,
            MetadataResolver metadataResolver,
            FormRegistry formRegistry,
            ReferenceIndex referenceIndex,
            NumberingMetadataRegistry numberingMetadataRegistry,
            SubsystemRegistry subsystemRegistry,
            FacetResolver facetResolver) {
        return new EntitySummaryAssembler(
            basePackage, metadataResolver, formRegistry, referenceIndex,
            numberingMetadataRegistry, subsystemRegistry, facetResolver);
    }
}
