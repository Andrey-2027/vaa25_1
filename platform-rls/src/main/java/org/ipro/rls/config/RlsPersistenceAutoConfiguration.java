package org.ipro.rls.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Persistence-регистрация подсистемы RLS.
 *
 * <p>Отдельный класс (а не {@code RlsAutoConfiguration}), чтобы
 * {@code @DataJpaTest}-срезы могли подключать только persistence-регистрацию
 * (сущность {@code AccessGrant} + {@code AccessGrantRepository}) без всего графа
 * бинов принуждения — по образцу {@code ReportStudioPersistenceAutoConfiguration}.
 * Прецедент проблемы — срезы settings (см. {@code PersistenceTypeRegistrationTest}):
 * срез обязан использовать регистрацию модуля, а не имитировать её.</p>
 */
@AutoConfiguration
@EntityScan("org.ipro.rls")
@EnableJpaRepositories("org.ipro.rls")
public class RlsPersistenceAutoConfiguration {
}
