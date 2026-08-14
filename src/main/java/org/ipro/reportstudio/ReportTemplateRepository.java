package org.ipro.reportstudio;

import org.ipro.reportstudio.dom.ReportTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Репозиторий шаблонов отчётов (Фаза 1). Полноценный сервис со слоем
 * доступа/RLS появится с конструктором (Фаза 5); здесь — минимальный CRUD
 * для каталога и тестов round-trip.
 */
public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, Long> {

    Optional<ReportTemplate> findByName(String name);

    boolean existsByName(String name);
}