package org.ipro.telemetry.repository;

import org.ipro.telemetry.model.OperationLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Read-доступ к operation_log для UI «Диагностика» (ленивый грид журнала
 * со Specification-фильтрами и пагинацией). Регистрируется собственным
 * {@code @EnableJpaRepositories} в {@code TelemetryAutoConfiguration} модуля —
 * ни приложение, ни платформенный хаб {@code RlsAutoConfiguration} этот пакет
 * больше не перечисляют.
 * <p>
 * Сущность {@link OperationLogEntity} уже входит в {@code @EntityScan}
 * приложения. Сам репозиторий прав не проверяет — проверка ROLE_ADMIN
 * выполняется на уровне сервиса (JournalSearchService), т.к. payload
 * содержит потенциально чувствительные данные.
 */
public interface OperationLogRepository
        extends JpaRepository<OperationLogEntity, Long>,
                JpaSpecificationExecutor<OperationLogEntity> {
}
