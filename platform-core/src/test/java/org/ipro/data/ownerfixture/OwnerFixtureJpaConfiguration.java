package org.ipro.data.ownerfixture;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Изолированный persistence unit E3.R: только owner-fixtures.
 *
 * <p>Отдельный пакет, чтобы C4-тесты и их набор managed-типов не менялись от появления
 * owner-модели. Домен приложения здесь не участвует.</p>
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class OwnerFixtureJpaConfiguration {
}
