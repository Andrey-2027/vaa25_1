package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.Nomenclature;
import org.ip.model.SklNomOpa;
import org.ip.model.SklNomOpaValue;
import org.ip.repository.SklNomOpaRepository;
import org.ip.repository.SklNomOpaValueRepository;
import org.ipro.crud.AbstractBaseService;
import org.ipro.crud.ValidationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Наборы значений атрибутов КСУ — единственная операция find-or-create (аналог
 * {@code spSklCreateFindGoodsCardAttr} Ис-Про, режимы LOOKUP/CREATE) + ленивый пустой набор.
 *
 * <p>Канонизация: отсев пустых → сортировка по {@code attrType.id} → канон
 * {@code "typeId:valueId;..."} (детерминирован, поэтому уникальный ключ — сама строка,
 * без хеша). Порядок входной карты не важен.
 *
 * <p>Гонка двух потоков на одну комбинацию: INSERT ловит unique-конфликт
 * {@code (nomenclature, canonical)}, транзакция создания откатывается (REQUIRES_NEW —
 * отдельно от вызывающей, чтобы aborted-tx на Postgres не портил внешнюю транзакцию),
 * повторный SELECT возвращает шапку победителя.
 *
 * <p>Набор immutable: {@code update}/{@code delete} не предусмотрены. Исправление написания
 * значения — {@link AttributeValueService#renameValue} (id значения стабилен → канон всех
 * наборов стабилен); {@code displayName} затронутых наборов пересобирается той же транзакцией.
 */
@Service
public class SklNomOpaService extends AbstractBaseService<SklNomOpa, Long> {

    /** Потолок строк на набор — константа с понятной ошибкой; поднять — без миграции схемы. */
    public static final int MAX_ITEMS = 16;

    private static final int MAX_ATTEMPTS = 3;

    private final SklNomOpaRepository sklNomOpaRepository;
    private final SklNomOpaValueRepository valueRepository;
    private final TransactionTemplate createTx;

    public SklNomOpaService(SklNomOpaRepository repository,
                            SklNomOpaValueRepository valueRepository,
                            Validator validator,
                            PlatformTransactionManager transactionManager) {
        super(repository, validator);
        this.sklNomOpaRepository = repository;
        this.valueRepository = valueRepository;
        this.createTx = new TransactionTemplate(transactionManager);
        this.createTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // === Режимы find-or-create ===

    /** Только найти (для проверок): null, если комбинации ещё нет. Ничего не создаёт. */
    public SklNomOpa lookup(Nomenclature nomenclature, Map<AttributeType, AttributeValue> attrs) {
        String canonical = canonicalize(attrs);
        return sklNomOpaRepository.findByNomenclatureAndCanonical(nomenclature, canonical).orElse(null);
    }

    /**
     * Найти или создать набор по комбинации значений (при проведении документов).
     * Значения обязаны принадлежать своим типам; потолок — {@link #MAX_ITEMS} строк.
     */
    public SklNomOpa findOrCreate(Nomenclature nomenclature, Map<AttributeType, AttributeValue> attrs) {
        if (nomenclature == null || nomenclature.getId() == null) {
            throw new ValidationException("Набор атрибутов КСУ сохраняется только для сохранённой позиции.");
        }
        TreeMap<AttributeType, AttributeValue> sorted = normalize(attrs);
        if (sorted.size() > MAX_ITEMS) {
            throw new ValidationException(
                "Слишком много атрибутов в наборе (максимум " + MAX_ITEMS + "): "
                    + sorted.size() + " у позиции " + nomenclature.getDisplayName() + ".");
        }
        String canonical = canonicalOf(sorted);
        String displayName = displayNameOf(sorted);

        Optional<SklNomOpa> existing = sklNomOpaRepository.findByNomenclatureAndCanonical(nomenclature, canonical);
        if (existing.isPresent()) {
            return existing.get();
        }
        return createWithRetry(nomenclature, canonical, displayName, sorted);
    }

    /** Ленивый пустой набор: одна шапка без строк с пустым каноном на номенклатуру. */
    public SklNomOpa getOrCreateEmpty(Nomenclature nomenclature) {
        return findOrCreate(nomenclature, Map.of());
    }

    /** Строки набора (тип → значение), отсортированы по id типа — порядок канона. */
    public List<SklNomOpaValue> getItems(SklNomOpa set) {
        return valueRepository.findBySetOrderByAttrTypeId(set);
    }

    /** Все наборы, содержащие значение (обратный поиск «все карточки, где цвет = красный»). */
    public List<SklNomOpa> findSetsContainingValue(AttributeValue value) {
        return valueRepository.findSetsContainingValue(value);
    }

    // === Канонизация ===

    /** Каноническая строка {@code "typeId:valueId;..."} по нормализованной карте. */
    public static String canonicalOf(TreeMap<AttributeType, AttributeValue> sorted) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<AttributeType, AttributeValue> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(e.getKey().getId()).append(':').append(e.getValue().getId());
        }
        return sb.toString();
    }

    /** Отображаемая строка {@code "Тип: Значение, ..."} в порядке канона (аналог FormOpaAtrStr). */
    public static String displayNameOf(TreeMap<AttributeType, AttributeValue> sorted) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<AttributeType, AttributeValue> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey().getName()).append(": ").append(e.getValue().getName());
        }
        return sb.toString();
    }

    /**
     * Нормализация входа: отсев пустых (аналог {@code NULL → 0}), проверка принадлежности
     * значения типу, сортировка по {@code attrType.id}.
     */
    private TreeMap<AttributeType, AttributeValue> normalize(Map<AttributeType, AttributeValue> attrs) {
        TreeMap<AttributeType, AttributeValue> sorted = new TreeMap<>(Comparator.comparing(AttributeType::getId));
        if (attrs == null) {
            return sorted;
        }
        for (Map.Entry<AttributeType, AttributeValue> entry : attrs.entrySet()) {
            AttributeType type = entry.getKey();
            AttributeValue value = entry.getValue();
            if (type == null || type.getId() == null || value == null || value.getId() == null) {
                continue; // отсев пустых
            }
            if (value.getAttrType() == null || !value.getAttrType().getId().equals(type.getId())) {
                throw new ValidationException(
                    "Значение «" + value.getDisplayName() + "» не принадлежит типу «"
                        + type.getDisplayName() + "»: исправьте связку (тип, значение).");
            }
            sorted.put(type, value);
        }
        return sorted;
    }

    /** Канонизация произвольной карты (lookup: отсев пустых + сортировка + строка). */
    private String canonicalize(Map<AttributeType, AttributeValue> attrs) {
        return canonicalOf(normalize(attrs));
    }

    // === Создание с обработкой гонки ===

    /**
     * INSERT шапки + строк в отдельной REQUIRES_NEW-транзакции; при уникальном конфликте
     * транзакция откатывается и попытка повторяется с повторным SELECT (шапка победителя).
     */
    private SklNomOpa createWithRetry(Nomenclature nomenclature, String canonical,
                                      String displayName, TreeMap<AttributeType, AttributeValue> sorted) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return createTx.execute(status -> {
                    Optional<SklNomOpa> again =
                        sklNomOpaRepository.findByNomenclatureAndCanonical(nomenclature, canonical);
                    if (again.isPresent()) {
                        return again.get();
                    }
                    SklNomOpa header = sklNomOpaRepository.save(new SklNomOpa(nomenclature, canonical, displayName));
                    List<SklNomOpaValue> items = new ArrayList<>(sorted.size());
                    for (Map.Entry<AttributeType, AttributeValue> e : sorted.entrySet()) {
                        items.add(new SklNomOpaValue(header, e.getKey(), e.getValue()));
                    }
                    valueRepository.saveAll(items);
                    return header;
                });
            } catch (RuntimeException e) {
                if (!isUniqueViolation(e) || attempt == MAX_ATTEMPTS - 1) {
                    throw e;
                }
                // гонка: другой поток уже создал набор — повторяем SELECT в новой транзакции
            }
        }
        throw new IllegalStateException("Недостижимо");
    }

    /**
     * Классификация уникального нарушения по цепочке причин (Hibernate может пробросить
     * как JPA-обёртку, так и своё исключение напрямую, Spring — как DataIntegrityViolation).
     */
    private static boolean isUniqueViolation(RuntimeException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException
                    || cause instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
        }
        return e instanceof DataIntegrityViolationException;
    }

    // === Immutable ===

    /** Набор создаётся только через {@link #findOrCreate} — канонизация не обходится. */
    @Override
    public SklNomOpa save(SklNomOpa entity) {
        throw new UnsupportedOperationException(
            "Набор атрибутов КСУ создаётся только обработкой findOrCreate (канонизация комбинации).");
    }

    /** См. {@link #save(SklNomOpa)}. */
    @Override
    public SklNomOpa create(SklNomOpa entity) {
        throw new UnsupportedOperationException(
            "Набор атрибутов КСУ создаётся только обработкой findOrCreate (канонизация комбинации).");
    }

    /** Набор неизменяем: правки нет (см. также {@link #delete}). */
    @Override
    public SklNomOpa update(SklNomOpa entity) {
        throw new UnsupportedOperationException(
            "Набор атрибутов КСУ неизменяем: новая комбинация значений создаёт новый набор "
                + "(findOrCreate), исправление написания — переименованием значения словаря.");
    }

    /** Набор бессмертен: удаление не предусмотрено (на него ссылаются карточки и партии). */
    @Override
    public void delete(Long id) {
        throw new UnsupportedOperationException(
            "Удаление набора атрибутов КСУ не предусмотрено: на набор ссылаются карточки и партии. "
                + "Новая комбинация значений создаёт новый набор.");
    }

    // === Инфраструктура ===

    @Override
    public List<SklNomOpa> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        return sklNomOpaRepository.searchByTerm(term, PageRequest.of(0, 100)).getContent();
    }

    @Override
    public Page<SklNomOpa> findAll(Specification<SklNomOpa> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }
}
