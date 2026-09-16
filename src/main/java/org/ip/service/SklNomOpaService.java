package org.ip.service;

import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.Nomenclature;
import org.ip.model.SklNomOpa;
import org.ip.model.SklNomOpaValue;
import org.ip.repository.SklNomOpaRepository;
import org.ip.repository.SklNomOpaValueRepository;
import org.ipro.crud.BaseService;
import org.ipro.crud.NaturalKeyCreateSupport;
import org.ipro.crud.ValidationException;
import org.ipro.data.CanonicalEntityService;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.data.SearchRead;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.telemetry.api.Measured;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p>Гонка двух потоков на одну комбинацию разрешается общим механизмом интернирования
 * ({@link org.ipro.crud.NaturalKeyCreateSupport}): INSERT ловит unique-конфликт
 * {@code (nomenclature, canonical)}, транзакция создания откатывается (REQUIRES_NEW —
 * отдельно от вызывающей, чтобы aborted-tx на Postgres не портил внешнюю транзакцию),
 * повторный SELECT возвращает шапку победителя. Канонизация ключа остаётся здесь, в типе:
 * общим является поведение интернирования, а не состав ключа.
 *
 * <p>Набор immutable: {@code update}/{@code delete} не предусмотрены. Исправление написания
 * значения — {@link AttributeValueService#renameValue} (id значения стабилен → канон всех
 * наборов стабилен); {@code displayName} затронутых наборов пересобирается той же транзакцией.
 *
 * <p>C4.6 волна E: класс больше не наследует compatibility base. Стандартная поверхность
 * (list/detail/search) делегируется canonical handle — те же FetchPlan, RLS и capability
 * границы, что у типов без собственного сервиса. Запись остаётся здесь только в виде
 * интернирования: descriptor типа намеренно не выдаёт generic write-capabilities, поэтому
 * {@code save/create/update/delete} отказывают явно, а не тихо обходят канонизацию.
 */
@Measured
@Service
public class SklNomOpaService implements BaseService<SklNomOpa, Long> {

    /** Потолок строк на набор — константа с понятной ошибкой; поднять — без миграции схемы. */
    public static final int MAX_ITEMS = 16;

    private final SklNomOpaRepository sklNomOpaRepository;
    private final SklNomOpaValueRepository valueRepository;

    /** Интернирование набора: гонка на создании разрешается общим механизмом платформы. */
    private final NaturalKeyCreateSupport createSupport;

    /** Чтение списка/карточки/поиска — canonical boundary. */
    private final CanonicalEntityService<SklNomOpa> canonical;

    @Autowired
    public SklNomOpaService(SklNomOpaRepository repository,
                            SklNomOpaValueRepository valueRepository,
                            NaturalKeyCreateSupport createSupport,
                            EntityDataAccessResolver dataAccessResolver) {
        this.sklNomOpaRepository = Objects.requireNonNull(repository, "repository must not be null");
        this.valueRepository = Objects.requireNonNull(valueRepository, "valueRepository must not be null");
        this.createSupport = Objects.requireNonNull(createSupport, "createSupport must not be null");
        Objects.requireNonNull(dataAccessResolver, "dataAccessResolver must not be null");
        BaseService<SklNomOpa, Long> handle = dataAccessResolver
            .<SklNomOpa, Long>findService(SklNomOpa.class)
            .orElseThrow(() -> new IllegalStateException(
                "SklNomOpa не имеет canonical data handle: "
                    + dataAccessResolver.resolutionReason(SklNomOpa.class)));
        @SuppressWarnings("unchecked")
        CanonicalEntityService<SklNomOpa> resolved = (CanonicalEntityService<SklNomOpa>) handle;
        this.canonical = resolved;
    }

    /** Сборка с явным canonical handle — для тестов, где полный контекст не нужен. */
    SklNomOpaService(SklNomOpaRepository repository,
                     SklNomOpaValueRepository valueRepository,
                     NaturalKeyCreateSupport createSupport,
                     CanonicalEntityService<SklNomOpa> canonical) {
        this.sklNomOpaRepository = Objects.requireNonNull(repository, "repository must not be null");
        this.valueRepository = Objects.requireNonNull(valueRepository, "valueRepository must not be null");
        this.createSupport = Objects.requireNonNull(createSupport, "createSupport must not be null");
        this.canonical = Objects.requireNonNull(canonical, "canonical must not be null");
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
        return createSupport.getOrCreate(SklNomOpa.interningKeyOf(nomenclature, canonical),
            () -> sklNomOpaRepository.findByNomenclatureAndCanonical(nomenclature, canonical),
            () -> {
                // шапка создаётся заново на каждой попытке: после сорванного flush экземпляр
                // может уже нести сгенерированный id, и повторный save стал бы UPDATE
                SklNomOpa header = sklNomOpaRepository.save(
                    new SklNomOpa(nomenclature, canonical, displayName));
                List<SklNomOpaValue> items = new ArrayList<>(sorted.size());
                for (Map.Entry<AttributeType, AttributeValue> e : sorted.entrySet()) {
                    items.add(new SklNomOpaValue(header, e.getKey(), e.getValue()));
                }
                valueRepository.saveAll(items);
                return header;
            });
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

    // === Immutable: generic-запись намеренно запрещена capability типа ===

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

    // === Стандартная поверхность: делегируется canonical handle ===

    @Override
    public Optional<SklNomOpa> findById(Long id) {
        return canonical.findById(id);
    }

    @Override
    public List<SklNomOpa> findAll() {
        return canonical.findAll();
    }

    @Override
    public Page<SklNomOpa> findAll(Pageable pageable) {
        return canonical.findAll(pageable);
    }

    @Override
    public Page<SklNomOpa> findAll(Specification<SklNomOpa> spec, Pageable pageable) {
        return canonical.findAll(spec, pageable);
    }

    @Override
    public Page<SklNomOpa> findAll(Specification<SklNomOpa> spec, Pageable pageable,
                                   Collection<String> fetchPaths) {
        return canonical.findAll(spec, pageable, fetchPaths);
    }

    @Override
    public Page<SklNomOpa> findAllByScenario(FetchScenario scenario, Specification<SklNomOpa> spec,
                                             Pageable pageable,
                                             Collection<String> additionalFetchPaths) {
        return canonical.findAllByScenario(scenario, spec, pageable, additionalFetchPaths);
    }

    @Override
    public List<SklNomOpa> search(String term) {
        return search(term, SearchRead.defaultPage()).getContent();
    }

    @Override
    public Page<SklNomOpa> search(String term, Pageable pageable) {
        return canonical.search(term, pageable);
    }

    @Override
    public Number sum(String fieldName, Specification<SklNomOpa> spec) {
        return canonical.sum(fieldName, spec);
    }
}
