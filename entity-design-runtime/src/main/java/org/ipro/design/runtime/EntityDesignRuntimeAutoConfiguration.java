package org.ipro.design.runtime;

import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

/** Optional backend bridge. Existing validated owners are required; absence is never an empty ready snapshot. */
@AutoConfiguration(afterName={"org.ipro.metadata.config.MetadataAutoConfiguration","org.ipro.data.config.DataAccessAutoConfiguration"})
@ConditionalOnBean({EntityDescriptorCatalog.class,MetadataResolver.class,SectionMetadataRegistry.class,
    NumberingMetadataRegistry.class,MetadataConsistencyStartupCheck.class})
public class EntityDesignRuntimeAutoConfiguration {
    @Bean @ConditionalOnMissingBean
    public EntityDesignRuntimeProjection entityDesignRuntimeProjection(EntityDescriptorCatalog descriptors,
            MetadataResolver metadata, SectionMetadataRegistry sections, NumberingMetadataRegistry numbering,
            MetadataConsistencyStartupCheck startup, ObjectProvider<RegisterFactsSource> registers) {
        return new EntityDesignRuntimeProjection(descriptors,metadata,sections,numbering,startup,registers.getIfAvailable());
    }
}
