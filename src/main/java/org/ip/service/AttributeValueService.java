package org.ip.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Validator;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.repository.AttributeValueRepository;
import org.ipro.crud.AbstractBaseService;
import org.ipro.crud.ValidationException;
import org.ipro.metadata.HasDisplayName;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Locale;
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
 * <p>Гонка двух потоков, создающих одно и то же значение: уникальное ограничение ловит
 * второй INSERT, транзакция создания откатывается (REQUIRES_NEW — отдельно от вызывающей,
 * чтобы aborted-tx на Postgres не портил внешнюю транзакцию), затем повторный SELECT
 * возвращает строку победителя.
 *
 * <p>Значения бессмертны: {@link #delete(Object)} не предусмотрен; единственный способ
 * изменить строку — обработка переименования {@link #renameValue(Long, String, String)}.
 */
@Service
public class AttributeValueService extends AbstractBaseService<AttributeValue, Long> {

    private static final int MAX_ATTEMPTS = 3;

    private final AttributeValueRepository attributeValueRepository;
    private final TransactionTemplate createTx;
    private final TransactionTemplate renameTx;

    /**
     * Репозиторий строк наборов КСУ — для пересборки displayName затронутых наборов
     * при переименовании. Injected сеттером: SklNomOpaValueRepository не зависит от
     * AttributeValueService, цикла нет, но конструкторный цикл между сервисами был бы.
     */
    private org.ip.repository.SklNomOpaValueRepository sklNomOpaValueRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSklNomOpaValueRepository(org.ip.repository.SklNomOpaValueRepository repository) {
        this.sklNomOpaValueRepository = repository;
    }

    @PersistenceContext
    private EntityManager entityManager;

    public AttributeValueService(AttributeValueRepository repository,
                                 Validator validator,
                                 PlatformTransactionManager transactionManager) {
        super(repository, validator);
        this.attributeValueRepository = repository;
        this.createTx = new TransactionTemplate(transactionManager);
        this.createTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // переименование — обычная бизнес-операция: присоединяется к транзакции вызывающего
        // (если она есть) или открывает свою; REQUIRES_NEW здесь не нужен (см. renameValue)
        this.renameTx = new TransactionTemplate(transactionManager);
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
     */
    public AttributeValue getOrCreateRef(AttributeType type, Class<?> targetClass, Long refId) {
        requireType(type, AttributeValueType.REF);
        if (refId == null) {
            throw new ValidationException("Для значения «Ссылка» обязателен id строки словаря.");
        }
        Object target = entityManager.find(targetClass, refId);
        if (target == null) {
            throw new ValidationException(
                "Строка словаря " + targetClass.getSimpleName() + " с id=" + refId + " не найдена.");
        }
        String display = target instanceof HasDisplayName hdn
            ? hdn.getDisplayName()
            : String.valueOf(target);
        if (display == null || display.isBlank()) {
            throw new ValidationException(
                "Не удалось получить отображаемое имя строки словаря " + targetClass.getSimpleName()
                    + " с id=" + refId + ".");
        }
        return getOrCreateRefRow(type, display, refId);
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
            AttributeValue value = entityManager.find(
                AttributeValue.class, valueId, LockModeType.PESSIMISTIC_WRITE);
            if (value == null) {
                throw new ValidationException("Значение атрибута с id=" + valueId + " не найдено.");
            }
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
     */
    private void rebuildDisplayNames(AttributeValue renamed) {
        if (sklNomOpaValueRepository == null) {
            return; // наборы КСУ ещё не заведены — пересобирать нечего
        }
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
            setDisplayName(set, sb.toString());
        }
    }

    /** Запись кэша displayName через EntityManager (шапка без сеттеров). */
    private void setDisplayName(org.ip.model.SklNomOpa set, String displayName) {
        entityManager.createQuery(
                "UPDATE SklNomOpa s SET s.displayName = :name WHERE s.id = :id")
            .setParameter("name", displayName)
            .setParameter("id", set.getId())
            .executeUpdate();
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
        return createWithRetry(() ->
            attributeValueRepository.findByAttrTypeAndCodeUp(type, codeUp),
            () -> attributeValueRepository.save(new AttributeValue(type, code, name, codeUp, null)));
    }

    private AttributeValue getOrCreateRefRow(AttributeType type, String displayName, Long refId) {
        Optional<AttributeValue> existing = attributeValueRepository.findByAttrTypeAndRefId(type, refId);
        if (existing.isPresent()) {
            return existing.get();
        }
        return createWithRetry(() ->
            attributeValueRepository.findByAttrTypeAndRefId(type, refId),
            () -> attributeValueRepository.save(
                new AttributeValue(type, displayName, displayName, null, refId)));
    }

    /**
     * SELECT → INSERT в отдельной REQUIRES_NEW-транзакции; при уникальном конфликте
     * транзакция откатывается и попытка повторяется с повторным SELECT (строка победителя).
     */
    private AttributeValue createWithRetry(
            java.util.function.Supplier<Optional<AttributeValue>> finder,
            java.util.function.Supplier<AttributeValue> inserter) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return createTx.execute(status -> {
                    Optional<AttributeValue> existing = finder.get();
                    if (existing.isPresent()) {
                        return existing.get();
                    }
                    return inserter.get();
                });
            } catch (RuntimeException e) {
                if (!isUniqueViolation(e) || attempt == MAX_ATTEMPTS - 1) {
                    throw e;
                }
                // гонка: другой поток уже создал строку — повторяем SELECT в новой транзакции
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

    // === Кросс-полевые правила при сохранении через формы (generic справочник) ===

    @Override
    protected void validateBusinessRules(AttributeValue entity) {
        if (entity.getRefId() != null) {
            if (entity.getAttrType() == null
                    || entity.getAttrType().getValueType() != AttributeValueType.REF) {
                throw new ValidationException(
                    "Ссылочное значение (refId) допустимо только для типа «Ссылка».");
            }
            entity.setCodeUp(null);
        } else {
            if (entity.getAttrType() != null
                    && entity.getAttrType().getValueType() == AttributeValueType.REF) {
                throw new ValidationException(
                    "Для типа «Ссылка» значение обязано ссылаться на строку словаря (refId).");
            }
            // пересчитываем служебный дедуп-ключ (поле не участвует в формах)
            if (entity.getCode() != null) {
                entity.setCodeUp(entity.getCode().toUpperCase(Locale.ROOT));
            }
        }
    }

    @Override
    public List<AttributeValue> search(String term) {
        if (term == null || term.isEmpty()) {
            return findAll();
        }
        return attributeValueRepository.searchByTerm(term, PageRequest.of(0, 100));
    }

    @Override
    public Page<AttributeValue> findAll(Specification<AttributeValue> spec, Pageable pageable) {
        return findAllWithFetchGraph(spec, pageable);
    }
}