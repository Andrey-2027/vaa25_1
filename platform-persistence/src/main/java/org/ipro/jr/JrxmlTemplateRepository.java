package org.ipro.jr;

import java.util.Optional;

import org.ipro.jr.dom.JrxmlTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Репозиторий метаданных шаблонов JR (.jrxml).
 *
 * <p>Регистрируется не централизованно, а собственным модулем:
 * {@code PersistenceAutoConfiguration} объявляет {@code @EntityScan("org.ipro.jr.dom")} и
 * {@code @EnableJpaRepositories("org.ipro.jr")}. Раньше здесь был javadoc про централизованную
 * регистрацию через хаб {@code RlsAutoConfiguration} — он перестал быть правдой после D2, а
 * комментарий, описывающий не ту конфигурацию, хуже отсутствующего: он подсказывает неверный
 * способ чинить потерю бина.</p>
 */
public interface JrxmlTemplateRepository extends JpaRepository<JrxmlTemplate, Long> {

    Optional<JrxmlTemplate> findByName(String name);

    boolean existsByName(String name);

    boolean existsByFileName(String fileName);
}
