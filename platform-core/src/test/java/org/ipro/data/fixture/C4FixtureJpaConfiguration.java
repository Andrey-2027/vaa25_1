package org.ipro.data.fixture;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Primary-конфигурация изолированного persistence unit C4.3-fixture.
 *
 * <p>{@code @SpringBootConfiguration} (а не {@code @TestConfiguration}) — иначе
 * {@code @DataJpaTest} не находит primary-конфигурацию. Persistence unit сканирует только
 * test-only fixture entities этого пакета, поэтому в нём нет доменных сущностей приложения
 * и доказательство canonical path не опирается на application infrastructure.</p>
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
public class C4FixtureJpaConfiguration {
}
