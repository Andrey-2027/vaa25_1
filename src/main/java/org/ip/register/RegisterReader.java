package org.ip.register;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.ipro.data.EntityDataAccess;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
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
 * {@code period <= asOf}, период по убыванию. Текущий paged API также выполняет count-запрос.
 * Уникальный индекс {@code (измерения, период)} исключает равные периоды в одном наборе.</p>
 *
 * <p>Здесь нет среза по набору измерений, пакетного чтения и условий по ресурсам — это v1.1/v1.2.
 * Читатель требует <b>полный</b> набор измерений: частичный набор вернул бы произвольную одну запись
 * из многих групп.</p>
 *
 * <p>Конструктор проверяет декларацию через {@link RegisterMetadataValidator}: регистр с неверной
 * декларацией не даст создать читателя, а значит, не поднимет бин, который его использует.</p>
 */
public final class RegisterReader<T> {

    private final Class<T> type;
    private final EntityDataAccess access;
    private final List<String> dimensions;
    private final String period;

    public RegisterReader(Class<T> type, EntityDataAccess access) {
        this.type = Objects.requireNonNull(type, "type");
        this.access = Objects.requireNonNull(access, "access");
        RegisterMetadata meta = type.getAnnotation(RegisterMetadata.class);
        if (meta == null) {
            throw new IllegalArgumentException(type.getName() + " не объявляет @RegisterMetadata");
        }
        List<RegisterMetadataValidator.Violation> violations = RegisterMetadataValidator.validate(type);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Декларация регистра " + type.getSimpleName()
                + " нарушена: " + violations.stream()
                    .map(violation -> violation.code() + " — " + violation.message())
                    .toList());
        }
        if (meta.period().isBlank()) {
            throw new IllegalArgumentException("getLast требует периодический регистр, а "
                + type.getSimpleName() + " объявлен без периода");
        }
        this.dimensions = List.of(meta.dimensions());
        this.period = meta.period();
    }

    /**
     * @param dimensionValues значения всех измерений по именам полей; ссылки — сохранённые сущности
     * @param asOf            дата, включительно
     * @return последняя запись с периодом не позже {@code asOf} или пусто, если такой записи нет
     */
    public Optional<T> getLast(Map<String, ?> dimensionValues, LocalDate asOf) {
        Objects.requireNonNull(dimensionValues, "dimensionValues");
        Objects.requireNonNull(asOf, "asOf");
        requireExactDimensions(dimensionValues);
        Map<String, Object> values = Map.copyOf(dimensionValues);

        Specification<T> spec = (Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            for (String dimension : dimensions) {
                predicates.add(cb.equal(root.get(dimension), values.get(dimension)));
            }
            predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get(period), asOf));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Sort newestFirst = Sort.by(Sort.Direction.DESC, period);
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
