package org.ip.service;

import jakarta.validation.Validator;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.repository.AttributeTypeRepository;
import org.ip.repository.AttributeValueRepository;
import org.ip.repository.SklNomOpaRepository;
import org.ip.repository.SklNomOpaValueRepository;
import org.ipro.crud.BaseService;
import org.ipro.crud.EntityLookup;
import org.ipro.crud.NaturalKeyCreateSupport;
import org.ipro.crud.ValidationException;
import org.ipro.data.CanonicalEntityService;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.telemetry.api.Measured;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Единый словарь значений атрибутов: find-or-create для всех типов значений
 * ({@link AttributeValueType}), нормализация, дедуп, обработка гонки создания.
 *
 * <p>Правила по типам:
 * <ul>
 *   <li>STRING — trim, дедуп по {@code UPPER(code)} (первый вариант сохраняется);</li>
 *   <li>NUMBER — каноническая форма («1,5»/«1.50» → «1.5»: trim, запятая→точка,
 *       {@code BigDecimal.stripTrailingZeros().toPlainString()});</li>
 *   <li>ENUM — код + наименование; код пустой → из наименования; дедуп по коду;</li>
 *   <li>REF — строка создаётся при первой привязке: {@code refId} + снапшот displayName
 *       строки целевого словаря (дедуп по {@code (attrType, refId)}).</li>
 * </ul>
 *
 * <p>Гонка двух потоков, создающих одно и то же значение, разрешается общим механизмом
 * интернирования ({@link NaturalKeyCreateSupport}): уникальное ограничение ловит второй
 * INSERT, транзакция создания откатывается (REQUIRES_NEW — отдельно от вызывающей, чтобы
 * aborted-tx на Postgres не портил внешнюю транзакцию), затем повторный SELECT возвращает
 * строку победителя. Канонизация ключа (нормализация значения) остаётся здесь, в типе.
 *
 * <p>Значения бессмертны: {@link #delete(Long)} не предусмотрен; единственный способ
 * изменить строку — обработка переименования {@link #renameValue(Long, String, String)}.
 *
 * <p>C4.6 волна E: класс больше не наследует compatibility base. Стандартная поверхность
 * (list/detail/search, а также {@code save/create}) делегируется canonical handle: descriptor
 * типа выдаёт только {@code CREATE}, поэтому запрещённый generic-update теперь отклоняется
 * до SQL и до пользовательского кода, а не тихо выполнялся бы через repository. Нормализация
 * {@code codeUp}/{@code refId} вынесена в {@code AttributeValueLifecycle}, чтобы применяться
 * ко всем путям записи, а не только к тому, которым шёл этот сервис.</p>
 */
@Measured
@Service
public class AttributeValueService implements BaseService<AttributeValue, Long> {

    private final AttributeValueRepository attributeValueRepository;
    private final AttributeTypeRepository attributeTypeRepository;
    private final SklNomOpaRepository sklNomOpaRepository;
    private final SklNomOpaValueRepository sklNomOpaValueRepository;
    private final EntityLookup lookupService;
    private final ManagedEntityCatalog entityCatalog;
    /** Интернирование значения: гонка на создании разрешается общим механизмом платформы. */
    private final NaturalKeyCreateSupport createSupport;
    /** Переименование — обычная бизнес-операция, REQUIRES_NEW здесь не нужен. */
    private final TransactionTemplate renameTx;

    /** Стандартная поверхность (список, карточка, поиск, create) — canonical boundary. */
    private final CanonicalEntityService<AttributeValue> canonical;

    @Autowired
    public AttributeValueService(AttributeValueRepository repository,
                                 AttributeTypeRepository attributeTypeRepository,
                                 SklNomOpaRepository sklNomOpaRepository,
                                 SklNomOpaValueRepository sklNomOpaValueRepository,
                                 EntityLookup lookupService,
                                 ManagedEntityCatalog entityCatalog,
                                 NaturalKeyCreateSupport createSupport,
                                 PlatformTransactionManager transactionManager,
                                 EntityDataAccessResolver dataAccessResolver) {
        this(repository, attributeTypeRepository, sklNomOpaRepository, sklNomOpaValueRepository,
            lookupService, entityCatalog, createSupport, transactionManager,
            canonicalHandle(dataAccessResolver));
    }

    /**
     * Сборка с явным canonical handle — для тестов, где полный контекст не нужен.
     * Намеренно package-private, чтобы Spring autowiring видел ровно одного кандидата.
     */
    AttributeValueService(AttributeValueRepository repository,
                          AttributeTypeRepository attributeTypeRepository,
                          SklNomOpaRepository sklNomOpaRepository,
                          SklNomOpaValueRepository sklNomOpaValueRepository,
                          EntityLookup lookupService,
                          ManagedEntityCatalog entityCatalog,
                          NaturalKeyCreateSupport createSupport,
                          PlatformTransactionManager transactionManager,
                          CanonicalEntityService<AttributeValue> canonical) {
        this.canonical = Objects.requireNonNull(canonical, "canonical must not be null");
        this.attributeValueRepository = repository;
        this.attributeTypeRepository = java.util.Objects.requireNonNull(
            attributeTypeRepository, "attributeTypeRepository must not be null");
        this.sklNomOpaRepository = java.util.Objects.requireNonNull(
            sklNomOpaRepository, "sklNomOpaRepository must not be null");
        this.sklNomOpaValueRepository = java.util.Objects.requireNonNull(
            sklNomOpaValueRepository, "sklNomOpaValueRepository must not be null");
        this.lookupService = java.util.Objects.requireNonNull(
            lookupService, "lookupService must not be null");
        this.entityCatalog = java.util.Objects.requireNonNull(
            entityCatalog, "entityCatalog must not be null");
        this.createSupport = java.util.Objects.requireNonNull(
            createSupport, "createSupport must not be null");
        // переименование — обычная бизнес-операция: присоединяется к транзакции вызывающего
        // (если она есть) или открывает свою; REQUIRES_NEW здесь не нужен (см. renameValue)
        this.renameTx = new TransactionTemplate(transactionManager);
    }

    private static CanonicalEntityService<AttributeValue> canonicalHandle(
            EntityDataAccessResolver resolver) {
        Objects.requireNonNull(resolver, "dataAccessResolver must not be null");
        BaseService<AttributeValue, Long> handle = resolver
            .<AttributeValue, Long>findService(AttributeValue.class)
            .orElseThrow(() -> new IllegalStateException(
                "AttributeValue не имеет canonical data handle: "
                    + resolver.resolutionReason(AttributeValue.class)));
        // findService всегда строит именно canonical service: кастомный policy подставляет
        // свой EntityDataAccess внутрь того же handle, а не отдельный сервис.
        @SuppressWarnings("unchecked")
        CanonicalEntityService<AttributeValue> resolved = (CanonicalEntityService<AttributeValue>) handle;
        return resolved;
    }

    // === Основные операции ===

    /**
     * Найти или создать значение типа STRING/ENUM по тексту.
     * Для NUMBER/REF — ошибка (у них свои методы).
     */
    public AttributeValue getOrCreate(AttributeType type, String raw) {
        requireType(type, AttributeValueType.STRING, AttributeValueType.ENUM);
        String value = normalizeText(raw);
        return getOrCreateScalar(type, value, value);
    }

    /**
     * Найти или создать значение типа NUMBER в канонической форме.
     */
    public AttributeValue getOrCreate(AttributeType type, BigDecimal number) {
        requireType(type, AttributeValueType.NUMBER);
        String canonical = canonicalNumber(number);
        return getOrCreateScalar(type, canonical, canonical);
    }

    /**
     * Создать/найти значение «Внутреннего справочника» (ENUM) с явным кодом и наименованием.
     * Код пустой → из наименования. Дедуп — по коду без учёта регистра.
     */
    public AttributeValue createEnumValue(AttributeType type, String code, String name) {
        requireType(type, AttributeValueType.ENUM);
        String valueName = normalizeText(name);
        String valueCode = normalizeText(code);
        if (valueCode.isEmpty()) {
            valueCode = valueName;
        }
        if (valueName.isEmpty()) {
            throw new ValidationException("Значение «Внутреннего справочника» не может быть пустым.");
        }
        return getOrCreateScalar(type, valueCode, valueName);
    }

    /**
     * Найти или создать значение «Ссылки» (REF): {@code refId} + снапшот displayName
     * строки целевого словаря. Дедуп — по {@code (attrType, refId)}.
     *
     * <p>Строка словаря загружается через RLS-aware {@link EntityLookup}: чужая
     * (недоступная) строка неотличима от отсутствующей — единая доменная ошибка не
     * раскрывает существование записи из другой ветки.</p>
     */
    public AttributeValue getOrCreateRef(AttributeType type, Long refId) {
        requireType(type, AttributeValueType.REF);
        if (refId == null) {
            throw new ValidationException("Для значения «Ссылка» обязателен id строки словаря.");
        }
        Class<? extends HasDisplayName> targetClass = resolveTargetDictionary(type);
        HasDisplayName target = lookupService.findSelectedById(targetClass, refId)
            .orElseThrow(() -> new ValidationException(
                "Строка словаря " + targetClass.getSimpleName() + " с id=" + refId
                    + " не найдена или недоступна."));
        String display = target.getDisplayName();
        if (display == null || display.isBlank()) {
            throw new ValidationException(
                "Не удалось получить отображаемое имя строки словаря " + targetClass.getSimpleName()
                    + " с id=" + refId + ".");
        }
        return getOrCreateRefRow(type, display, refId);
    }

    /**
     * Разрешить {@link AttributeType#targetDictionary} в JPA-сущность. Значение
     * хранит имя класса как устойчивый контракт; проверка — через платформенный
     * {@link ManagedEntityCatalog}, чтобы строка из справочника не превратилась
     * в произвольный {@code Class.forName()} вызов в прикладном сервисе.
     */
    @Transactional(readOnly = true)
    public Class<? extends HasDisplayName> resolveTargetDictionary(AttributeType type) {
        requireType(type, AttributeValueType.REF);
        String className = normalizeText(type.getTargetDictionary());
        if (className.isEmpty()) {
            throw new ValidationException(
                "Для типа значения «Ссылка» не задан целевой словарь.");
        }
        return entityCatalog.resolve(className, HasDisplayName.class);
    }

    /**
     * Найти/создать скалярное значение внутри текущей транзакции агрегата.
     * В отличие от публичного {@link #getOrCreate(AttributeType, String)} этот путь
     * не открывает {@code REQUIRES_NEW}: если сохранение номенклатуры откатится,
     * созданная строка словаря также откатится.
     */
    @Transactional
    public AttributeValue getOrCreateInCurrentTransaction(AttributeType type, String raw) {
        requireType(type, AttributeValueType.STRING, AttributeValueType.ENUM);
        AttributeType managedType = lockAttributeType(type);
        String value = normalizeText(raw);
        return getOrCreateScalarInCurrentTransaction(managedType, value, value);
    }

    /** Найти/создать каноническое числовое значение в текущей транзакции агрегата. */
    @Transactional
    public AttributeValue getOrCreateInCurrentTransaction(AttributeType type, BigDecimal number) {
        requireType(type, AttributeValueType.NUMBER);
        AttributeType managedType = lockAttributeType(type);
        String canonical = canonicalNumber(number);
        return getOrCreateScalarInCurrentTransaction(managedType, canonical, canonical);
    }

    /**
     * Найти/создать ссылочное значение в текущей транзакции агрегата. Блокировка
     * типа сериализует создание одинаковых ссылочных строк в этом write-path.
     *
     * <p>Строка словаря — через RLS-aware {@link EntityLookup} (см. {@link #getOrCreateRef}).</p>
     */
    @Transactional
    public AttributeValue getOrCreateRefInCurrentTransaction(
            AttributeType type, Long refId) {
        requireType(type, AttributeValueType.REF);
        if (refId == null) {
            throw new ValidationException("Для значения «Ссылка» обязателен id строки словаря.");
        }
        AttributeType managedType = lockAttributeType(type);
        Class<? extends HasDisplayName> targetClass = resolveTargetDictionary(managedType);
        HasDisplayName target = lookupService.findSelectedById(targetClass, refId)
            .orElseThrow(() -> new ValidationException(
                "Строка словаря " + targetClass.getSimpleName() + " с id=" + refId
                    + " не найдена или недоступна."));
        String display = target.getDisplayName();
        if (display == null || display.isBlank()) {
            throw new ValidationException(
                "Не удалось получить отображаемое имя строки словаря " + targetClass.getSimpleName()
                    + " с id=" + refId + ".");
        }
        Optional<AttributeValue> existing =
            attributeValueRepository.findByAttrTypeAndRefId(managedType, refId);
        if (existing.isPresent()) {
            return existing.get();
        }
        AttributeValue created = attributeValueRepository.save(
            new AttributeValue(managedType, display, display, null, refId));
        attributeValueRepository.flush();
        return created;
    }

    private AttributeType lockAttributeType(AttributeType type) {
        if (type == null || type.getId() == null) {
            throw new ValidationException("Тип атрибута должен быть сохранён до указания значения.");
        }
        return attributeTypeRepository.findByIdForUpdate(type.getId())
            .orElseThrow(() -> new ValidationException(
                "Тип атрибута с id=" + type.getId() + " не найден."));
    }

    private AttributeValue getOrCreateScalarInCurrentTransaction(
            AttributeType type, String code, String name) {
        if (code.isEmpty()) {
            throw new ValidationException("Значение атрибута не может быть пустым.");
        }
        String codeUp = code.toUpperCase(Locale.ROOT);
        Optional<AttributeValue> existing =
            attributeValueRepository.findByAttrTypeAndCodeUp(type, codeUp);
        if (existing.isPresent()) {
            return existing.get();
        }
        AttributeValue created = attributeValueRepository.save(
            new AttributeValue(type, code, name, codeUp, null));
        attributeValueRepository.flush();
        return created;
    }

    /**
     * Только чтение: найти значение STRING/ENUM по тексту (без создания).
     */
    public Optional<AttributeValue> lookup(AttributeType type, String raw) {
        requireType(type, AttributeValueType.STRING, AttributeValueType.ENUM);
        String value = normalizeText(raw);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return attributeValueRepository.findByAttrTypeAndCodeUp(type, value.toUpperCase(Locale.ROOT));
    }

    /** Значения типа (для формы типа и справочников), упорядочены по коду. */
    public List<AttributeValue> findByAttrType(AttributeType type) {
        return attributeValueRepository.findByAttrTypeOrderByCode(type);
    }

    /**
     * Значения бессмертны (на них ссылаются наборы характеристик КСУ и привязки
     * номенклатуры): удаление не предусмотрено.
     */
    @Override
    public void delete(Long id) {
        throw new UnsupportedOperationException(
            "Удаление значений атрибутов не предусмотрено: значение бессмертно "
                + "(на него могут ссылаться наборы характеристик и привязки). "
                + "Деактивируйте тип атрибута (поле «Активен»).");
    }

    /**
     * Прямое изменение строки значения не предусмотрено — только обработка
     * переименования {@link #renameValue(Long, String, String)} (см. также {@link #delete}).
     */
    @Override
    public AttributeValue update(AttributeValue entity) {
        throw new UnsupportedOperationException(
            "Изменение значения атрибута напрямую не предусмотрено: код меняется обработкой "
                + "переименования, значение бессмертно (на него ссылаются наборы и привязки).");
    }

    // === Обработка переименования ===

    /**
     * Обработка переименования кода значения (id строки не меняется — привязки
     * {@code NomAttributeValue} и наборы КСУ ссылаются по ID и следуют автоматически,
     * каноны наборов не двигаются).
     *
     * <p>Правила:
     * <ul>
     *   <li>пессимистичная блокировка строки (PESSIMISTIC_WRITE) внутри одной транзакции:
     *       проверка цели и запись атомарны;</li>
     *   <li>новый код нормализуется по типу (STRING/ENUM — trim, NUMBER — каноническая форма);</li>
     *   <li>слот {@code (attrType, codeUp)} занят <b>другой</b> строкой → это слияние,
     *       а не переименование: запрещено (слияние меняет каноны наборов и может
     *       слить карточки);</li>
     *   <li>REF — запрещено: имя значения это снапшот строки словаря на момент привязки;</li>
     *   <li>STRING/NUMBER — {@code name} следует за кодом; ENUM — {@code name} меняется
     *       только если передан непустой {@code newName} (подпись независима от кода).</li>
     * </ul>
     *
     * <p>Известное допущение (зафиксировано при проектировании): освободившийся слот
     * старого кода может быть занят новым значением — «красный» после переименования
     * в «алый» можно завести заново. Перед появлением КСУ-наборов (где значение — разрез
     * карточки) риск закрывается «могилкой» (неактивная строка, занимающая слот).
     */
    public AttributeValue renameValue(Long valueId, String newCode, String newName) {
        return renameTx.execute(status -> {
            AttributeValue value = attributeValueRepository.findByIdForUpdate(valueId)
                .orElseThrow(() -> new ValidationException(
                    "Значение атрибута с id=" + valueId + " не найдено."));
            AttributeValueType type = value.getAttrType().getValueType();
            if (type == AttributeValueType.REF) {
                throw new ValidationException(
                    "Переименование значения «Ссылка» запрещено: его имя — снапшот строки "
                        + "словаря на момент привязки.");
            }
            String code = normalizeRenameCode(type, newCode);
            Optional<AttributeValue> target = attributeValueRepository.findByAttrTypeAndCodeUp(
                value.getAttrType(), code.toUpperCase(Locale.ROOT));
            if (target.isPresent() && !target.get().getId().equals(valueId)) {
                throw new ValidationException(
                    "Значение «" + code + "» уже существует у типа " + value.getAttrType().getCode()
                        + " — это слияние, а не переименование.");
            }
            value.setCode(code);
            value.setCodeUp(code.toUpperCase(Locale.ROOT));
            if (type == AttributeValueType.STRING || type == AttributeValueType.NUMBER) {
                value.setName(code);
            } else {
                String label = normalizeText(newName);
                if (!label.isEmpty()) {
                    value.setName(label);
                }
            }
            AttributeValue saved = attributeValueRepository.save(value);
            rebuildDisplayNames(saved);
            return saved;
        });
    }

    /**
     * Пересборка displayName затронутых наборов КСУ: displayName — поддерживаемый кэш
     * (решение 2026-09-08), переименование — исправление написания, наборы должны показывать
     * исправленное имя. Канон не меняется: он строится по id, id при переименовании стабилен.
     *
     * <p>Обычное доменное изменение через repository (без JPQL bulk update): кэш
     * меняется тем же путём, что и остальное состояние, optimistic-lock версия
     * набора корректно увеличивается вместе с ним.</p>
     */
    private void rebuildDisplayNames(AttributeValue renamed) {
        List<org.ip.model.SklNomOpa> sets = sklNomOpaValueRepository.findSetsContainingValue(renamed);
        for (org.ip.model.SklNomOpa set : sets) {
            List<org.ip.model.SklNomOpaValue> items =
                sklNomOpaValueRepository.findBySetOrderByAttrTypeId(set);
            items.sort(java.util.Comparator.comparing(
                item -> item.getAttrType().getId()));
            StringBuilder sb = new StringBuilder();
            for (org.ip.model.SklNomOpaValue item : items) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(item.getAttrType().getName()).append(": ")
                    .append(item.getValue().getName());
            }
            set.refreshDisplayName(sb.toString());
            sklNomOpaRepository.save(set);
        }
    }

    private static String normalizeRenameCode(AttributeValueType type, String raw) {
        if (type == AttributeValueType.NUMBER) {
            return canonicalNumber(parseNumber(raw));
        }
        String code = normalizeText(raw);
        if (code.isEmpty()) {
            throw new ValidationException("Новый код значения не может быть пустым.");
        }
        return code;
    }

    // === Нормализация ===

    /** Парсинг числа из текста ввода («1,5» → {@code BigDecimal("1.5")}). */
    public static BigDecimal parseNumber(String raw) {
        if (raw == null) {
            throw new ValidationException("Числовое значение не задано.");
        }
        String text = raw.trim().replace(',', '.');
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            throw new ValidationException("«" + raw.trim() + "» — не число.");
        }
    }

    /** Каноническая текстовая форма числа («1,5»/«1.50» → «1.5»). */
    public static String canonicalNumber(BigDecimal number) {
        if (number == null) {
            throw new ValidationException("Числовое значение не задано.");
        }
        return number.stripTrailingZeros().toPlainString();
    }

    // === Внутреннее ===

    private static String normalizeText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }

    private static void requireType(AttributeType type, AttributeValueType... allowed) {
        if (type == null) {
            throw new ValidationException("Тип атрибута не задан.");
        }
        for (AttributeValueType candidate : allowed) {
            if (type.getValueType() == candidate) {
                return;
            }
        }
        throw new ValidationException(
            "Операция не поддерживается для типа «" + type.getValueType().getLabel() + "» у типа "
                + type.getCode() + ".");
    }

    private AttributeValue getOrCreateScalar(AttributeType type, String code, String name) {
        if (code.isEmpty()) {
            throw new ValidationException("Значение атрибута не может быть пустым.");
        }
        String codeUp = code.toUpperCase(Locale.ROOT);
        Optional<AttributeValue> existing = attributeValueRepository.findByAttrTypeAndCodeUp(type, codeUp);
        if (existing.isPresent()) {
            return existing.get();
        }
        return createSupport.getOrCreate(AttributeValue.interningKeyOf(type, codeUp, null),
            () -> attributeValueRepository.findByAttrTypeAndCodeUp(type, codeUp),
            () -> attributeValueRepository.save(new AttributeValue(type, code, name, codeUp, null)));
    }

    private AttributeValue getOrCreateRefRow(AttributeType type, String displayName, Long refId) {
        Optional<AttributeValue> existing = attributeValueRepository.findByAttrTypeAndRefId(type, refId);
        if (existing.isPresent()) {
            return existing.get();
        }
        return createSupport.getOrCreate(AttributeValue.interningKeyOf(type, null, refId),
            () -> attributeValueRepository.findByAttrTypeAndRefId(type, refId),
            () -> attributeValueRepository.save(
                new AttributeValue(type, displayName, displayName, null, refId)));
    }

    // === Стандартная поверхность: делегируется canonical handle ===

    @Override
    public AttributeValue save(AttributeValue entity) {
        return canonical.save(entity);
    }

    @Override
    public AttributeValue create(AttributeValue entity) {
        return canonical.create(entity);
    }

    @Override
    public Optional<AttributeValue> findById(Long id) {
        return canonical.findById(id);
    }

    @Override
    public List<AttributeValue> findAll() {
        return canonical.findAll();
    }

    @Override
    public Page<AttributeValue> findAll(Pageable pageable) {
        return canonical.findAll(pageable);
    }

    @Override
    public Page<AttributeValue> findAll(Specification<AttributeValue> spec, Pageable pageable) {
        return canonical.findAll(spec, pageable);
    }

    @Override
    public Page<AttributeValue> findAll(Specification<AttributeValue> spec, Pageable pageable,
                                        Collection<String> fetchPaths) {
        return canonical.findAll(spec, pageable, fetchPaths);
    }

    @Override
    public Page<AttributeValue> findAllByScenario(FetchScenario scenario,
                                                  Specification<AttributeValue> spec,
                                                  Pageable pageable,
                                                  Collection<String> additionalFetchPaths) {
        return canonical.findAllByScenario(scenario, spec, pageable, additionalFetchPaths);
    }

    @Override
    public List<AttributeValue> search(String term) {
        return canonical.search(term);
    }

    @Override
    public Page<AttributeValue> search(String term, Pageable pageable) {
        return canonical.search(term, pageable);
    }

    @Override
    public Number sum(String fieldName, Specification<AttributeValue> spec) {
        return canonical.sum(fieldName, spec);
    }
}
