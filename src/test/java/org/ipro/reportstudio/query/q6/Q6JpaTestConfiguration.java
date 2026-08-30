package org.ipro.reportstudio.query.q6;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Тестовая primary-конфигурация для {@code @DataJpaTest}-срезов по q6-сущностям.
 *
 * {@code @SpringBootConfiguration} (а не {@code @TestConfiguration}): slice-тесты
 * {@code @DataJpaTest} пропускают {@code @TestConfiguration}-классы при поиске
 * primary-конфигурации и падают с «Unable to find a @SpringBootConfiguration».
 *
 * {@code @EnableAutoConfiguration} нужен не для импорта авто-конфигураций (в slice
 * они отключены {@code @OverrideAutoConfiguration(enabled = false)}), а ради его
 * меты {@code @AutoConfigurationPackage}: без неё сканер сущностей Hibernate падает
 * с «Unable to retrieve @EnableAutoConfiguration base packages». Пакет этого класса
 * (org.ipro.reportstudio.query.q6) содержит Q6Product/Q6Journal.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class Q6JpaTestConfiguration {
}
