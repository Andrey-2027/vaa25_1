package org.ipro.metadata;

import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;

/**
 * Структурный факт о JPA-атрибуте сущности: поле известно платформе, но не
 * обязательно опубликовано в пользовательских каналах.
 *
 * <p>Три различаемых состояния поля (ET2, D4):</p>
 * <ul>
 *   <li><b>structurally known</b> — атрибут персистентен (из JPA metamodel);
 *       именно это и представляет этот класс;</li>
 *   <li><b>UI declaration</b> — поле имеет {@code @FieldMetadata};
 *       {@link #hasUiDeclaration()} возвращает {@code true}. Это стандартная
 *       публикация в формы/грид, но не публикация в query/REST: у этих каналов
 *       свои контракты;</li>
 *   <li><b>presentation defined</b> — label, order и остальное оформление задано
 *       явно или выведено из JPA/Java; доступно через {@link #fieldMetadata()} если
 *       аннотация присутствует.</li>
 * </ul>
 *
 * <p>Этот класс не заменяет {@link FieldMetadataInfo}: тот полностью описывает
 * опубликованное поле с resolved-типом, required, lookup и диагностиками.
 * {@code StructuralAttribute} — лёгкий structural-only факт для Explorer.
 * Startup-валидатор проверяет объявленный metadata-контракт и не считает отсутствие
 * публикации у структурного атрибута ошибкой.</p>
 *
 * <p>Экземпляры создаются {@link StructuralAttributeScanner}; не создавать напрямую
 * в потребителях.</p>
 *
 * @param name           имя Java-поля (совпадает с JPA attribute name)
 * @param declaringType  класс, объявивший атрибут (может быть базовым)
 * @param javaType       Java-тип поля
 * @param persistentKind категория JPA-атрибута: BASIC, ASSOCIATION_TO_ONE, ASSOCIATION_TO_MANY
 * @param accessMode     фактический JPA access mode объявившего класса
 * @param technicalPurpose техническое назначение атрибута (id/version/audit/бизнес)
 * @param nullable       допускает ли JPA null
 * @param required       требует ли значение Bean Validation или JPA
 * @param requiredOrigin происхождение {@link #required}
 * @param fieldMetadata  {@code @FieldMetadata}-аннотация, если задана; иначе {@code null}
 * @param persistentMember фактический JPA field/getter; {@code null} для legacy fixtures
 */
public record StructuralAttribute(
        String name,
        Class<?> declaringType,
        Class<?> javaType,
        PersistentKind persistentKind,
        AccessMode accessMode,
        TechnicalPurpose technicalPurpose,
        boolean nullable,
        boolean required,
        FactOrigin requiredOrigin,
        FieldMetadata fieldMetadata,
        java.lang.reflect.AnnotatedElement persistentMember
) {

    /** Compatibility constructor for structural fixtures without a metamodel member. */
    public StructuralAttribute(String name, Class<?> declaringType, Class<?> javaType,
            PersistentKind persistentKind, AccessMode accessMode, TechnicalPurpose technicalPurpose,
            boolean nullable, boolean required, FactOrigin requiredOrigin, FieldMetadata fieldMetadata) {
        this(name, declaringType, javaType, persistentKind, accessMode, technicalPurpose,
            nullable, required, requiredOrigin, fieldMetadata, null);
    }

    public StructuralAttribute {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A structural attribute name must not be blank");
        }
        if (declaringType == null || javaType == null || persistentKind == null || accessMode == null
            || technicalPurpose == null || requiredOrigin == null) {
            throw new IllegalArgumentException("Structural attribute facts must be complete");
        }
    }

    /**
     * Категория JPA-атрибута с точки зрения структурного описания.
     * Точного соответствия {@link jakarta.persistence.metamodel.Attribute.PersistentAttributeType}
     * не требуется: потребителям достаточно знать, является ли поле скаляром, ссылкой или коллекцией.
     */
    public enum PersistentKind {
        /** Скалярное поле: строка, число, дата, Enum, Embedded. */
        BASIC,
        /** Одиночная ссылка на другую сущность ({@code @ManyToOne}, {@code @OneToOne}). */
        ASSOCIATION_TO_ONE,
        /** Коллекция ссылок ({@code @OneToMany}, {@code @ManyToMany}) или element collection. */
        ASSOCIATION_TO_MANY
    }

    /** Фактический JPA access mode объявившего класса. */
    public enum AccessMode {
        FIELD,
        PROPERTY
    }

    /** Техническое назначение атрибута, не являющееся бизнес-реквизитом. */
    public enum TechnicalPurpose {
        BUSINESS,
        IDENTIFIER,
        VERSION,
        AUDIT
    }

    /**
     * Атрибут имеет стандартную UI-декларацию {@code @FieldMetadata}.
     *
     * <p>Это не означает публикацию во всех каналах: query-каталог и REST имеют
     * собственные контракты и не выводятся из структурного атрибута.</p>
     */
    public boolean hasUiDeclaration() {
        return fieldMetadata != null;
    }

    /**
     * Платформенный тип по умолчанию: тот же вывод из Java/JPA, который применяет
     * {@link FieldMetadataInfo} для поля без явного {@code @FieldMetadata.type}.
     */
    public FieldType platformType() {
        return FieldMetadataInfo.inferPlatformType(javaType, persistentKind != PersistentKind.BASIC);
    }

    /** Происхождение {@link #platformType()}: ассоциация приходит из JPA, остальное — из Java. */
    public FactOrigin platformTypeOrigin() {
        return persistentKind == PersistentKind.BASIC ? FactOrigin.JAVA_TYPE : FactOrigin.JPA_MAPPING;
    }
}
