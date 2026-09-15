package org.ipro.jr;

import java.util.Optional;

import org.ipro.jr.dom.JrxmlTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Репозиторий метаданных шаблонов JR (.jrxml). Регистрируется централизованно
 * в {@code RlsAutoConfiguration#@EnableJpaRepositories} (basePackages).
 */
public interface JrxmlTemplateRepository extends JpaRepository<JrxmlTemplate, Long> {

    Optional<JrxmlTemplate> findByName(String name);

    boolean existsByName(String name);

    boolean existsByFileName(String fileName);
}
