package org.ipro.report.config;

import org.ipro.report.catalog.ReportCatalogContributor;
import org.ipro.report.catalog.ReportCatalogReadGate;
import org.ipro.report.catalog.ReportCatalogService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-Configuration общего каталога отчётов.
 *
 * <p>Пакеты {@code org.ipro.report.*} не попадают в component-scan приложения
 * (базовый пакет {@code org.ip}) — бины регистрируются здесь, по образцу
 * остальных отчётных конфигураций.</p>
 *
 * <p>Конфигурация не зависит ни от одного движка: вклад приходит адаптерами
 * {@link ReportCatalogContributor}, а каждый движок объявляет свой адаптер в
 * собственной конфигурации. Поэтому снятие движка не требует правок каталога,
 * и каталог не создаётся конфигурацией конкретного движка.</p>
 *
 * <p>Без вклада движков каталог пуст, но работоспособен: {@link ObjectProvider}
 * допускает ноль адаптеров — ранняя загрузка платформы без отчётов не должна
 * падать на отсутствующем бине.</p>
 */
@AutoConfiguration
public class ReportCatalogAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ReportCatalogReadGate reportCatalogReadGate(RlsReadGate readGate,
                                                       RlsCurrentUser currentUser) {
        return new ReportCatalogReadGate(readGate, currentUser);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportCatalogService reportCatalogService(
            ObjectProvider<ReportCatalogContributor> contributors) {
        return new ReportCatalogService(contributors.orderedStream().toList());
    }
}
