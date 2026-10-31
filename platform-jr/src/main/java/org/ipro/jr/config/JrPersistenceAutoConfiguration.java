package org.ipro.jr.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Persistence-регистрация JR-шаблонов, принадлежащая модулю JR (M2.1).
 *
 * <p>Отдельный класс позволяет {@code @DataJpaTest} подключать только entity и repository,
 * не поднимая сервисы и execution-конфигурацию JR. Потребитель получает запись через
 * {@code META-INF/spring} этого артефакта, а не объявляет сканы у себя.</p>
 */
@AutoConfiguration
@EntityScan("org.ipro.jr.dom")
@EnableJpaRepositories("org.ipro.jr")
public class JrPersistenceAutoConfiguration {
}
