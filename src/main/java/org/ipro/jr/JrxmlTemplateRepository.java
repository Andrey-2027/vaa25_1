package org.ipro.jr;

import java.util.Optional;

import org.ipro.jr.dom.JrxmlTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Репозиторий метаданных шаблонов JR (.jrxml).
 *
 * <p>Регистрируется app-owned {@code JrPersistenceAutoConfiguration}; это отделяет
 * persistence-срез JR от сервисной конфигурации движка и от платформенного persistence API.</p>
 */
public interface JrxmlTemplateRepository extends JpaRepository<JrxmlTemplate, Long> {

    Optional<JrxmlTemplate> findByName(String name);

    boolean existsByName(String name);

    boolean existsByFileName(String fileName);
}
