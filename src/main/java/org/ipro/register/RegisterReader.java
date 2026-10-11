package org.ipro.register;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.ipro.data.EntityDataAccess;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Точечное чтение периодического регистра: «последняя запись на дату» (ADR-0013 §4, v1.0).
 *
 * <p>Читает через canonical {@link EntityDataAccess}: сохраняются C5, RLS, проверка экспозиции,
 * FetchPlan и телеметрия (ADR-0007). Выборка ограничена одной строкой: измерения равны,
 * {@code period <= asOf}, а сортировка убывает по полному winner order из descriptor.
 * Для самостоятельного регистра это period; для подчинённого — period, recordingOrder, lineNumber.</p>
 *
 * <p>Здесь нет среза по набору измерений, пакетного чтения и условий по ресурсам — это v1.1/v1.2.
 * Читатель требует <b>полный</b> набор измерений: частичный набор вернул бы произвольную одну запись
 * из многих групп.</p>
 *
 * <p>Прикладной бин получает descriptor из {@link RegisterRegistry}, проверенного по managed catalog.
 * Конструктор без registry сохраняется для самостоятельного использования и проверяет декларацию
 * через {@link RegisterDescriptor#describe(Class)}.</p>
 */
public final class RegisterReader<T> {

    private final Class<T> type;
    private final EntityDataAccess access;
    private final List<String> dimensions;
    private final String period;
    private final List<String> winnerOrder;

    public RegisterReader(Class<T> type, EntityDataAccess access) {
        this(type, access, RegisterDescriptor.describe(type));
    }

    /** Managed production path uses the same verified snapshot as startup/report consumers. */
    public RegisterReader(Class<T> type, EntityDataAccess access, RegisterRegistry registry) {
        this(type, access, Objects.requireNonNull(registry, "registry").require(type));
    }

    private RegisterReader(Class<T> type, EntityDataAccess access, RegisterDescriptor descriptor) {
        this.type = Objects.requireNonNull(type, "type");
        this.access = Objects.requireNonNull(access, "access");
        if (!descriptor.periodic()) {
            throw new IllegalArgumentException("getLast требует периодический регистр, а "
                + type.getSimpleName() + " объявлен без периода");
        }
        this.dimensions = descriptor.dimensionNames();
        this.period = descriptor.period().orElseThrow().name();
        this.winnerOrder = descriptor.winnerOrderFields();
    }

    /**
     * @param dimensionValues значения всех измерений по именам полей; ссылки — сохранённые сущности
     * @param asOf            дата, включительно
     * @return последняя запись с периодом не позже {@code asOf} или пусто, если такой записи нет
     */
    public Optional<T> getLast(Map<String, ?> dimensionValues, LocalDateTime asOf) {
        Objects.requireNonNull(dimensionValues, "dimensionValues");
        Objects.requireNonNull(asOf, "asOf");
        LocalDateTime boundary = RegisterPeriodicity.EXACT.normalize(asOf);
        requireExactDimensions(dimensionValues);
        Map<String, Object> values = Map.copyOf(dimensionValues);

        Specification<T> spec = (Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            for (String dimension : dimensions) {
                predicates.add(cb.equal(root.get(dimension), values.get(dimension)));
            }
            predicates.add(cb.lessThanOrEqualTo(root.<LocalDateTime>get(period), boundary));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Sort newestFirst = Sort.by(winnerOrder.stream().map(Sort.Order::desc).toList());
        return access.list(type, spec, PageRequest.of(0, 1, newestFirst)).stream().findFirst();
    }

    private void requireExactDimensions(Map<String, ?> values) {
        Set<String> expected = new LinkedHashSet<>(dimensions);
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(values.keySet());
        Set<String> unexpected = new TreeSet<>(values.keySet());
        unexpected.removeAll(expected);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            throw new IllegalArgumentException("Измерения регистра " + type.getSimpleName()
                + " заданы неверно: не хватает " + missing + ", лишние " + unexpected
                + " (нужен полный набор " + expected + ")");
        }
        values.forEach((name, value) -> {
            if (value == null) {
                throw new IllegalArgumentException("Значение измерения '" + name + "' не задано");
            }
        });
    }
}
