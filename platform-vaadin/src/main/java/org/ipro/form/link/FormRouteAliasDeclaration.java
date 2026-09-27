package org.ipro.form.link;

import java.util.List;
import java.util.Objects;

/**
 * Прикладная декларация внешнего ключа (E2.1, ADR-0009 §4): явный alias типа, его прежние
 * ключи и причина исключения.
 *
 * <p>Ключ выводится из имени класса, и это правило работает по умолчанию: декларация нужна
 * тогда, когда выведенный ключ не годится (переименование класса без смены адреса) либо когда
 * у типа уже есть опубликованные прежние адреса. Причина обязательна: исключение из правила
 * без причины неотличимо от забытого кода.</p>
 *
 * <p>Объявляется как бин приложения; платформа читает декларации при построении
 * {@link FormRouteCatalog}. Коллизии, ключи вне грамматики и декларации для непубликуемого
 * типа обнаруживаются там же и останавливают старт.</p>
 */
public record FormRouteAliasDeclaration(Class<?> type, String entityKey, List<String> legacyKeys,
                                        String reason) {

    public FormRouteAliasDeclaration {
        Objects.requireNonNull(type, "type must not be null");
        legacyKeys = legacyKeys == null ? List.of() : List.copyOf(legacyKeys);
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("декларация ключа требует причину: " + type.getName());
        }
    }

    /** Декларация только прежних ключей: канонический остаётся выведенным из класса. */
    public static FormRouteAliasDeclaration legacyKeys(Class<?> type, List<String> legacyKeys,
                                                       String reason) {
        return new FormRouteAliasDeclaration(type, null, legacyKeys, reason);
    }
}
