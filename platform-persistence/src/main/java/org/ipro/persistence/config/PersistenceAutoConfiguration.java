package org.ipro.persistence.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * D2 (persistence slice): модуль сам объявляет свои persistence-пакеты.
 *
 * <p>Карта D1 (§3.5) назвала самым неприятным риском выноса то, что entity и репозитории
 * регистрируются <b>централизованно</b>: {@code @EntityScan} в приложении и
 * {@code @EnableJpaRepositories} в {@code RlsAutoConfiguration}. Пока код лежит в одном
 * артефакте, это просто список строк. После выноса та же строка означает, что артефакт
 * зависит от того, помнит ли о нём приложение — а забыть можно без единого признака в
 * компиляторе.</p>
 *
 * <p>Решение: пакеты, которые модуль везёт, объявляет сам модуль. Две декларации
 * {@code @EnableJpaRepositories} не перекрывают друг друга — в проекте это уже так
 * (приложение объявляет {@code org.ip} отдельно от платформенного хаба), а
 * {@code @EntityScan} накапливается в {@code EntityScanPackages}. Проверяется это не
 * рассуждением, а тестом, который требует наличия репозитория из <b>каждого</b>
 * зарегистрированного пакета.</p>
 */
@AutoConfiguration
@EntityScan("org.ipro.jr.dom")
@EnableJpaRepositories(basePackages = "org.ipro.jr")
public class PersistenceAutoConfiguration {
}
