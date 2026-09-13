package org.ipro.data;

import org.ipro.crud.IdentifiableEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

/**
 * Публичная точка входа canonical data path (C4, ADR-0007 §1): выражает назначение
 * операции, а не persistence-механику.
 *
 * <p>Facade <b>не владеет policy</b>: RLS, FetchPlan, InstanceName, effective metadata,
 * нумерация, lifecycle и события остаются отдельными компонентами, которые вызываются
 * изнутри. Пользователь API не передаёт {@code EntityGraph}, Hibernate session, RLS
 * predicate и telemetry scope.</p>
 *
 * <p>Доступность типа решает не факт присутствия в JPA metamodel, а классифицированный
 * descriptor (ADR-0007 §2): owned row не получает автономного handle, internal store —
 * без явного разрешения владельца, тип вне каталога — отказ до RLS и SQL. Write-намерение
 * без соответствующей capability отклоняется до пользовательского кода.</p>
 *
 * <p>Имена intent-методов могут уточняться в последующих срезах C4; разделение
 * public/SPI/internal и семантика операций фиксируются здесь.</p>
 */
public interface EntityDataAccess {

    /** Descriptor типа — read-only view таксономии и capabilities. */
    EntityDescriptor descriptor(Class<?> type);

    /** Карточка формы: сценарий {@code DETAIL}. */
    <T> Optional<T> detail(Class<T> type, Object id);

    /** Список: сценарий {@code LIST}; {@code filter} может быть {@code null}. */
    <T> Page<T> list(Class<T> type, Specification<T> filter, Pageable pageable);

    /**
     * Значение выбора: сценарий {@code LOOKUP}. Surface поиска — прямые строковые пути
     * InstanceName; терм трактуется литерально (C4.4, ADR-0007 §7).
     */
    <T> List<T> lookup(Class<T> type, String term, int limit);

    /**
     * Серверный поиск: сценарий {@code LIST}, терм трактуется литерально, поля выводятся
     * из единой лестницы, порядок детерминирован. Blank term — не фильтр: выдача bounded
     * (page/limit), а не выгрузка таблицы (C4.4, ADR-0007 §7).
     */
    <T> Page<T> search(Class<T> type, String term, Pageable pageable);

    /** Создание новой сущности ({@code CREATE}). */
    <T extends IdentifiableEntity> T create(Class<T> type, T entity);

    /** Обновление существующей сущности ({@code UPDATE}). */
    <T extends IdentifiableEntity> T update(Class<T> type, T entity);

    /** Создание или обновление по наличию id — единый write intent для UI-формы. */
    <T extends IdentifiableEntity> T save(Class<T> type, T entity);

    /** Удаление по id ({@code DELETE}). */
    <T extends IdentifiableEntity> void delete(Class<T> type, Object id);
}
