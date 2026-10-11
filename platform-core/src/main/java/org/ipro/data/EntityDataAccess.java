package org.ipro.data;

import org.ipro.identity.IdentifiableEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

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
    <T extends IdentifiableEntity<Long>> T create(Class<T> type, T entity);

    /** Обновление существующей сущности ({@code UPDATE}). */
    <T extends IdentifiableEntity<Long>> T update(Class<T> type, T entity);

    /** Создание или обновление по наличию id — единый write intent для UI-формы. */
    <T extends IdentifiableEntity<Long>> T save(Class<T> type, T entity);

    /**
     * Именованная операция владельца на строке (ADR-0019): право выражено ключом
     * {@code ACTION:<permissionId>}, а мутация применяется к загруженной строке.
     *
     * <p>Это не generic update: тип, объявивший только те write-намерения, которые публикует
     * сам (например, один {@code CREATE}), не получает здесь ни {@code UPDATE}-capability, ни
     * записи в {@code descriptor.capabilities().writes()}. Определение операции объявляет
     * владелец типа, а решение об операции принимается до загрузки строки и до SQL.</p>
     *
     * <p>Доступность типа решает классифицированный descriptor: тип без canonical write handle
     * (owned row, internal store без моста, намеренно закрытый тип) отклоняется до чтения строки,
     * до RLS и до пользовательского кода.</p>
     *
     * @param type         тип строки
     * @param permissionId стабильный идентификатор операции владельца (без префикса {@code ACTION:})
     * @param id           идентификатор строки
     * @param mutation     доменная мутация, применяемая владельцем к загруженной строке
     */
    <T extends IdentifiableEntity<Long>> T executeNamed(Class<T> type, String permissionId, Object id,
                                                  Consumer<T> mutation);

    /** Удаление по id ({@code DELETE}). */
    <T extends IdentifiableEntity<Long>> void delete(Class<T> type, Object id);
}
