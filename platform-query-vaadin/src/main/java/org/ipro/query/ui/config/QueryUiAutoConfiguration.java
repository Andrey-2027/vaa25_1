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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.ipro.data.presentation.EntityPresentationService;
import org.ipro.query.QueryProjectionCompiler;
import org.ipro.query.ReportProjectionReads;
import org.ipro.query.ui.grid.QueryGridEnvironment;
import org.ipro.query.ui.grid.QueryProjectionSource;
import org.ipro.query.ui.grid.QueryReferenceLabels;
import org.ipro.query.ui.grid.QueryRunHistory;
import org.ipro.query.ui.grid.QueryTaskRunner;
import org.ipro.query.ui.selection.EntitySelection;

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

    /** FQ-3: время прошлого выполнения для подсказки «Не выполнялся»; только в памяти процесса. */
    @Bean
    @ConditionalOnMissingBean
    public QueryRunHistory queryRunHistory() {
        return new QueryRunHistory();
    }

    /**
     * FQ-3: окружение ядра просмотра. Фоновое выполнение даёт адаптер приложения: он переносит
     * пользователя в рабочий поток. Пока адаптера нет, выполнение идёт в потоке запроса.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({QueryProjectionCompiler.class, ReportProjectionReads.class,
            EntityPresentationService.class, RlsCurrentUser.class})
    public QueryGridEnvironment queryGridEnvironment(QueryProjectionCompiler compiler,
                                                     ReportProjectionReads reads,
                                                     EntityPresentationService presentations,
                                                     ObjectProvider<EntitySelection> selection,
                                                     ObjectProvider<QueryTaskRunner> tasks,
                                                     QueryRunHistory history,
                                                     RlsCurrentUser currentUser) {
        return new QueryGridEnvironment(QueryProjectionSource.over(compiler, reads),
                QueryReferenceLabels.over(presentations), selection.getIfAvailable(),
                tasks.getIfAvailable(QueryTaskRunner::direct), history, currentUser::requireAuthenticatedUsername);
    }
}
