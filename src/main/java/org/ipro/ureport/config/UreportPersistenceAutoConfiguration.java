package org.ipro.ureport.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Persistence-регистрация подсистемы UReport.
 *
 * <p>D2 → D3 (шаг 8б): пакеты подсистемы раньше перечислял платформенный хаб
 * {@code RlsAutoConfiguration} — теперь подсистема объявляет их сама, по образцу
 * вынесенных модулей. Сущность лежит в {@code org.ipro.ureport.dom}, репозиторий —
 * в корне {@code org.ipro.ureport}. Отдельный класс (а не
 * {@code UreportAutoConfiguration}), чтобы {@code @DataJpaTest}-срезы могли
 * подключать только persistence-регистрацию без всего графа бинов сервисов.</p>
 */
@AutoConfiguration
@EntityScan("org.ipro.ureport.dom")
@EnableJpaRepositories("org.ipro.ureport")
public class UreportPersistenceAutoConfiguration {
}
