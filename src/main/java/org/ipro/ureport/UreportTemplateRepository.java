package org.ipro.ureport;

import java.util.Optional;

import org.ipro.ureport.dom.UreportTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Репозиторий метаданных шаблонов UReport3. Регистрируется централизованно
 * в {@code RlsAutoConfiguration#@EnableJpaRepositories} (basePackages).
 */
public interface UreportTemplateRepository extends JpaRepository<UreportTemplate, Long> {

    Optional<UreportTemplate> findByName(String name);

    boolean existsByName(String name);

    boolean existsByFileName(String fileName);
}
