package org.ipro.vaadin.search.config;

import org.ipro.form.config.FormAutoConfiguration;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.search.GlobalSearchCatalog;
import org.ipro.search.GlobalSearchService;
import org.ipro.search.config.GlobalSearchAutoConfiguration;
import org.ipro.vaadin.search.GlobalSearchHeader;
import org.ipro.vaadin.search.GlobalSearchNavigationAdapter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;

/**
 * UI-половина глобального поиска: поле в шапке и переход к карточке записи.
 *
 * <p>Почему отдельная автоконфигурация. Прежнее устройство — пример того, как граница
 * исчезает незаметно: ядро поиска (запрос, каталог, провайдеры, сервис) и UI (Vaadin-компонент
 * шапки, навигация через {@link FormCoordinator}) лежали в одном пакете {@code org.ipro.search}
 * и в одной конфигурации. Ядро поиска при этом не содержит ничего UI-шного — Vaadin в него
 * попадал ровно двумя классами, и из-за них будущий {@code platform-core} нельзя было собрать
 * без UI-библиотеки.</p>
 *
 * <p>Теперь ядро поиска не знает ни о Vaadin, ни о формах, а знает эта конфигурация. Она
 * поднимает свои бины только когда подняты ядро поиска и формовый слой; самостоятельный
 * потребитель поиска без UI получает работающий сервис, но не получает шапки — и это ожидаемо,
 * а не потеря.</p>
 */
@AutoConfiguration
@AutoConfigureAfter({GlobalSearchAutoConfiguration.class, FormAutoConfiguration.class})
public class GlobalSearchVaadinAutoConfiguration {

    @Bean
    @ConditionalOnBean({GlobalSearchCatalog.class, FormCoordinator.class})
    @ConditionalOnMissingBean(GlobalSearchNavigationAdapter.class)
    public GlobalSearchNavigationAdapter globalSearchNavigationAdapter(
            GlobalSearchCatalog catalog, FormCoordinator formCoordinator) {
        return new GlobalSearchNavigationAdapter(catalog, formCoordinator);
    }

    /**
     * Prototype: шапка вставляется в layout и держит своё состояние поиска, поэтому экземпляр
     * на каждое использование, а не общий бин.
     */
    @Bean
    @Scope("prototype")
    @ConditionalOnBean({GlobalSearchService.class, GlobalSearchNavigationAdapter.class})
    @ConditionalOnMissingBean(GlobalSearchHeader.class)
    public GlobalSearchHeader globalSearchHeader(
            GlobalSearchService searchService,
            GlobalSearchNavigationAdapter navigationAdapter) {
        return new GlobalSearchHeader(searchService, navigationAdapter);
    }
}
