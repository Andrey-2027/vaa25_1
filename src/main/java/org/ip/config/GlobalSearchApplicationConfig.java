package org.ip.config;

import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.ReceivingDocument;
import org.ipro.search.GlobalSearchConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Единственная прикладная декларация участия сущностей в глобальном поиске.
 *
 * <p>Порядок деклараций является порядком групп результатов. Поля поиска относятся
 * к глобальному поиску, а не к {@code @Lookup.searchFields} автокомплита ссылок.</p>
 */
@Configuration(proxyBeanMethods = false)
public class GlobalSearchApplicationConfig {

    @Bean
    public GlobalSearchConfig globalSearchConfig() {
        GlobalSearchConfig config = new GlobalSearchConfig();
        config.add(Nomenclature.class, "code", "name");
        config.add(PrdSpec.class, "codeSpec", "draft")
            .displayFields("codeSpec", "draft");
        config.add(ReceivingDocument.class, "number")
            .displayFields("number", "date");
        return config;
    }
}
