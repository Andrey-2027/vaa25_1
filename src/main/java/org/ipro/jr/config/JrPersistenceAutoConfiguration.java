package org.ipro.jr.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Persistence-регистрация JR-шаблонов, принадлежащая приложению вместе с report-функцией.
 *
 * <p>Отдельный класс позволяет {@code @DataJpaTest} подключать только entity и repository,
 * не поднимая сервисы и execution-конфигурацию JR.</p>
 */
@AutoConfiguration
@EntityScan("org.ipro.jr.dom")
@EnableJpaRepositories("org.ipro.jr")
public class JrPersistenceAutoConfiguration {
}
