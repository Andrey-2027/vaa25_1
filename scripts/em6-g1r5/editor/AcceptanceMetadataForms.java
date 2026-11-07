package org.ip.config;

import org.ip.model.Workshop;
import org.ip.model.ReceivingDocument;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.form.builtin.ItemForm;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** User-selected acceptance variant; existing default/manual form factories remain intact. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="acceptance.em6.prepare",havingValue="true")
public class AcceptanceMetadataForms {
    @Bean ItemFormCustomization acceptanceWorkshopMetadata() {return variant(Workshop.class);}
    @Bean ItemFormCustomization acceptanceDocumentMetadata() {return variant(ReceivingDocument.class);}
    private ItemFormCustomization variant(Class<?> type) {
        return new ItemFormCustomization() {
            public Class<?> entityClass() {return type;}
            public void configure(ItemFormVariants variants) {
                variants.add("acceptance-metadata",ItemForm.class,ctx -> new ItemForm<>(ctx.metadataResolver().resolve(type),ctx.fieldFactory()));
            }
        };
    }
}
