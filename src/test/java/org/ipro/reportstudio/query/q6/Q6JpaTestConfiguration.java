package org.ipro.reportstudio.query.q6;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Прикладная primary-конфигурация для {@code @DataJpaTest}-срезов, которые ещё
 * проверяют сценарии приложения (UDR-адаптер и RLS-исполнение).
 *
 * <p>{@code @SpringBootConfiguration}, а не {@code @TestConfiguration}: slice-тесты
 * {@code @DataJpaTest} пропускают {@code @TestConfiguration}-классы при поиске
 * primary-конфигурации. {@code @EnableAutoConfiguration} нужен ради меты
 * {@code @AutoConfigurationPackage}: без неё Hibernate не находит base package.</p>
 *
 * <p>M1.4: сами Q6-сущности переехали в тестовый артефакт {@code platform-query}
 * ({@code org.ipro.query.q6}); этот класс больше не сканирует их пакет неявно,
 * поэтому тесты задают {@code @EntityScan("org.ipro.query.q6")} явно.</p>
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class Q6JpaTestConfiguration {
}
