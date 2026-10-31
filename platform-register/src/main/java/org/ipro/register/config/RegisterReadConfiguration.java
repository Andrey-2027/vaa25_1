package org.ipro.register.config;

import org.ipro.register.RegisterClassificationContributor;
import jakarta.persistence.EntityManagerFactory;
import org.ipro.data.EntityDataAccess;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.register.RecorderTypeRegistry;
import org.ipro.register.RegisterReaderFactory;
import org.ipro.register.RegisterRegistry;
import org.ipro.register.RegisterStartupValidator;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * D-R S1 — read-конфигурация модуля: реестр объявлений, каталог типов-регистраторов, стартовая
 * проверка деклараций и фабрика читателей.
 *
 * <p>Почему конфигурация срезовая, а не общая. До среза модуль объявлял один монолитный
 * {@code RegisterAutoConfiguration}, который импортировал и команды ключа (S2), и протокол набора
 * владельца (S3); вынос чтения в модуль начинался бы с переноса всего этого — то есть с среза,
 * который читает и пишет одновременно. Проверка раскладки назвала это главным блокером S1
 * (12 из 14 ссылок за границу среза). Поэтому срез владеет только чтением: команд и регистратора
 * здесь нет, они остаются приложению до своих срезов.</p>
 *
 * <p>Что здесь <b>не</b> объявлено и почему:</p>
 * <ul>
 *   <li>{@code @EntityScan("org.ipro.register.persistence")} — сущность порядка записи
 *       ({@code RecorderOrderClock}) до S3 принадлежит приложению вместе со своим persistence,
 *       поэтому сканирование остаётся прикладным до переезда сущности;</li>
 *   <li>засев часов порядка — по той же причине (см. {@link RegisterStartupValidator});</li>
 *   <li>команды ключа и набор владельца — S2 и S3.</li>
 * </ul>
 *
 * <p>Бины — {@code @ConditionalOnMissingBean}: потребитель вправе заменить читателя или реестр
 * своим (так делает, например, тестовая конфигурация), и это не должно требовать выключения
 * авто-конфигурации целиком.</p>
 */
@AutoConfiguration
public class RegisterReadConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RecorderTypeRegistry recorderTypeRegistry(ManagedEntityCatalog catalog,
            EntityManagerFactory entityManagerFactory, MetadataResolver metadataResolver) {
        return new RecorderTypeRegistry(catalog, entityManagerFactory, metadataResolver);
    }

    /**
     * Каталог типов-регистраторов валидируется на старте: дубль кода или не-DOCUMENT тип
     * должны падать до первого набора владельца, а не при сохранении документа.
     */
    @Bean
    public SmartInitializingSingleton recorderTypeStartupValidator(RecorderTypeRegistry registry) {
        return registry::initialize;
    }

    @Bean
    @ConditionalOnMissingBean
    public RegisterRegistry registerRegistry(ManagedEntityCatalog catalog, RecorderTypeRegistry recorderTypes) {
        return new RegisterRegistry(catalog, recorderTypes);
    }

    @Bean
    public RegisterClassificationContributor registerClassificationContributor(RegisterRegistry registry) {
        return new RegisterClassificationContributor(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public RegisterStartupValidator registerStartupValidator(RegisterRegistry registry) {
        return new RegisterStartupValidator(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public RegisterReaderFactory registerReaderFactory(RegisterRegistry registry, EntityDataAccess access,
            EntityManagerFactory entityManagerFactory) {
        return new RegisterReaderFactory(registry, access, entityManagerFactory);
    }
}
