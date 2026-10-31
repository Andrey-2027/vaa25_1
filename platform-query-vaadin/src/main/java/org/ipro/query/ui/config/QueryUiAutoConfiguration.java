package org.ipro.query.ui.config;

import org.ipro.metadata.ManagedEntityTypes;
import org.ipro.metadata.MetadataResolver;
import org.ipro.query.ReportQueryGuard;
import org.ipro.query.config.QueryAutoConfiguration;
import org.ipro.query.ui.editor.QueryEditorAnalysisService;
import org.ipro.query.ui.editor.QueryMetadataCatalogService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * M1.2: бины query-UI принадлежат артефакту, а не приложению.
 *
 * <p>Раньше оба бина объявлял {@code ReportStudioAutoConfiguration} — то есть query UI
 * был подключён только вместе с отчётным слоем. Теперь редактор можно собрать в
 * потребителе, у которого отчётов нет вовсе: конфигурация подхватывается модульным
 * {@code AutoConfiguration.imports}, а не сканированием пакетов приложения.</p>
 */
@AutoConfiguration(after = QueryAutoConfiguration.class)
public class QueryUiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QueryEditorAnalysisService queryEditorAnalysisService(ReportQueryGuard guard) {
        return new QueryEditorAnalysisService(guard);
    }

    @Bean
    @ConditionalOnMissingBean
    public QueryMetadataCatalogService queryMetadataCatalogService(
            ManagedEntityTypes managedEntityTypes,
            MetadataResolver metadataResolver,
            RlsReadGate rlsReadGate,
            RlsCurrentUser currentUser) {
        return new QueryMetadataCatalogService(managedEntityTypes, metadataResolver, rlsReadGate, currentUser);
    }
}
