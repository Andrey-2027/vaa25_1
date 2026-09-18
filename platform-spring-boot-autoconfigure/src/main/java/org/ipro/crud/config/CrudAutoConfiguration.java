package org.ipro.crud.config;

import org.ipro.crud.LookupService;
import org.ipro.crud.NaturalKeyCreateSupport;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.ServiceLocator;
import org.ipro.data.CanonicalReadExecutor;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.config.MetadataAutoConfiguration;
import org.ipro.rls.RlsFilterActivator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Бины CRUD-слоя платформы.
 *
 * <p>Классы объявлены явными {@code @Bean}, а не {@code @Import} concrete-классов (прецедент —
 * {@link MetadataAutoConfiguration}). Разница семантическая, а не стилистическая: {@code @Import}
 * регистрирует <b>класс</b> под FQN-именем бина и не участвует в conditional-обработке, поэтому
 * пользовательский бин того же типа давал либо двух кандидатов одной инъекции (другое имя), либо
 * конфликт definition overriding (то же имя). Обещание «приложение может заменить любой бин»
 * держалось на словах. Здесь замена обеспечена механизмом контейнера:
 * {@code @ConditionalOnMissingBean} проверяет тип, а пользовательские бины определены до
 * авто-конфигураций.</p>
 *
 * <p>{@code @Bean} сохраняет аннотационную обработку экземпляра: {@code @PersistenceContext}
 * в {@link ReferenceCheckService} приходит из {@code PersistenceAnnotationBeanPostProcessor}
 * так же, как приходил импортированному классу.</p>
 *
 * <p><b>Fail-fast, а не backoff, там, где это контракт wiring.</b> {@link LookupService}
 * безусловно требует {@link CanonicalReadExecutor}: без него контекст обязан упасть с названной
 * причиной, а не подняться с полусломанным CRUD. Поэтому {@code @ConditionalOnBean} у этих
 * бинов нет — отсутствующий collaborator виден на старте.</p>
 *
 * <p>Порядок применения зафиксирован явно: {@link MetadataAutoConfiguration} объявляет
 * агрегатное сохранение, которому нужен {@link ServiceLocator}. До этого порядок держался на
 * алфавитной сортировке FQN — признаке, случайном для требования; {@code @AutoConfigureBefore}
 * делает его видимым и устойчивым к переименованию пакетов.</p>
 */
@AutoConfiguration
@AutoConfigureBefore(MetadataAutoConfiguration.class)
public class CrudAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ServiceLocator serviceLocator(ApplicationContext applicationContext,
                                         MetadataResolver metadataResolver,
                                         SectionMetadataRegistry sectionMetadataRegistry) {
        return new ServiceLocator(applicationContext, metadataResolver, sectionMetadataRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReferenceCheckService referenceCheckService(ReferenceIndex referenceIndex,
                                                       RlsFilterActivator rlsFilterActivator) {
        return new ReferenceCheckService(referenceIndex, rlsFilterActivator);
    }

    @Bean
    @ConditionalOnMissingBean
    public LookupService lookupService(CanonicalReadExecutor canonicalReadExecutor) {
        return new LookupService(canonicalReadExecutor);
    }

    @Bean
    @ConditionalOnMissingBean
    public NaturalKeyCreateSupport naturalKeyCreateSupport(
            PlatformTransactionManager transactionManager) {
        return new NaturalKeyCreateSupport(transactionManager);
    }
}
