package org.ipro.reportstudio.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Persistence-регистрация подсистемы отчётов (reportstudio).
 *
 * <p>D2 → D3 (шаг 8б): пакеты подсистемы раньше перечислял платформенный хаб
 * {@code RlsAutoConfiguration} — теперь подсистема объявляет их сама, по образцу
 * вынесенных модулей. Сущности лежат в {@code org.ipro.reportstudio.dom},
 * репозитории — в корне {@code org.ipro.reportstudio}. Отдельный класс (а не
 * {@code ReportStudioAutoConfiguration}), чтобы {@code @DataJpaTest}-срезы могли
 * подключать только persistence-регистрацию без всего графа бинов сервисов.</p>
 */
@AutoConfiguration
@EntityScan("org.ipro.reportstudio.dom")
@EnableJpaRepositories("org.ipro.reportstudio")
public class ReportStudioPersistenceAutoConfiguration {
}
