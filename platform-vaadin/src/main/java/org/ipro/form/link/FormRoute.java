package org.ipro.form.link;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Типизированный адрес формы (E2.1, ADR-0009 §3/§7): одно представление для разбора,
 * генерации ссылки и identity вкладки.
 *
 * <p>Инварианты выражения, а не соглашение:</p>
 * <ul>
 * <li>{@link #entityKey} — внешний ключ публикации (ASCII lower-kebab-case), а не имя класса:
 *     переименование Java-типа не меняет опубликованный адрес;</li>
 * <li>{@link #id} — обязателен и положителен для {@link FormRouteKind#ITEM}, отсутствует для
 *     {@link FormRouteKind#LIST}. {@code null} у карточки не означает «новая запись»:
 *     создание по ссылке вне контракта E2, поэтому такой адрес не существует;</li>
 * <li>{@link #variant} — ровно ключ {@code FormRegistry} либо {@code null} для default-варианта.
 *     Default в каноническом адресе отсутствует, поэтому {@code "default"} — не то же самое,
 *     что {@code null}: строка {@code "default"} не является ключом реестра и будет отклонена
 *     каталогом как неизвестный вариант.</li>
 * </ul>
 *
 * <p>Класс не знает каталога: он описывает синтаксически корректный адрес. Существует ли
 * ключ, зарегистрирован ли вариант и линкабельна ли форма — решают
 * {@link FormRouteCatalog} и {@link FormLinkService}, а не этот тип.</p>
 */
public record FormRoute(FormRouteKind kind, String entityKey, Long id, String variant) {

    /** Грамматика внешнего ключа и ключа варианта: ASCII lower-kebab-case. */
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

    public FormRoute {
        Objects.requireNonNull(kind, "kind must not be null");
        entityKey = requireKey(entityKey, "entityKey");
        if (kind == FormRouteKind.ITEM) {
            if (id == null || id <= 0) {
                throw new IllegalArgumentException(
                    "ITEM-адрес требует положительный id: " + id);
            }
        } else if (id != null) {
            throw new IllegalArgumentException("LIST-адрес не принимает id: " + id);
        }
        if (variant != null) {
            variant = requireKey(variant, "variant");
        }
    }

    /** Адрес карточки существующей записи. */
    public static FormRoute record(String entityKey, long id) {
        return new FormRoute(FormRouteKind.ITEM, entityKey, id, null);
    }

    /** Адрес списка. */
    public static FormRoute list(String entityKey) {
        return new FormRoute(FormRouteKind.LIST, entityKey, null, null);
    }

    /** Адрес default-варианта (в каноническом виде вариант не присутствует). */
    public boolean defaultVariant() {
        return variant == null;
    }

    private static String requireKey(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (!isKey(value)) {
            throw new IllegalArgumentException(field + " вне грамматики (ASCII lower-kebab-case): '"
                + value + "'");
        }
        return value;
    }

    /**
     * Грамматика внешнего ключа и ключа варианта. Пакетная видимость: её использует кодек
     * при разборе адреса, а приложению она не нужна — синтаксис адреса закрыт
     * {@link FormRouteCodec}.
     */
    static boolean isKey(String value) {
        return value != null && KEY.matcher(value).matches();
    }
}
