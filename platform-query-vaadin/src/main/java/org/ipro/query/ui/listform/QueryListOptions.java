package org.ipro.query.ui.listform;

import java.util.function.Supplier;

/**
 * Как открыт список: вариант стандартного вида, встроенный ли он, откуда берутся виды и ссылка.
 *
 * @param variant  вариант вида, как у ListForm ({@code ReceivingDocument.workshop-incoming}); один и
 *                 тот же список в разных формах получает разные варианты, и виды одной формы не
 *                 попадают в другую; {@code null} — общие виды ключа списка
 * @param embedded встроенный вид (UX-10): без заголовка и панели параметров, в toolbar только
 *                 «Обновить» и «Ещё»
 * @param views    хранилище видов; {@code null} — «Видов» нет
 * @param link     адрес списка для «Скопировать ссылку»; {@code null} — пункта нет
 */
public record QueryListOptions(String variant, boolean embedded, QueryListViews views, Supplier<String> link) {
    public static final QueryListOptions DEFAULT = new QueryListOptions(null, false, null, null);

    public QueryListOptions {
        if (variant != null && !variant.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("Вариант вида: строчные латинские буквы, цифры и дефис: " + variant);
        }
    }

    public QueryListOptions withVariant(String value) {
        return new QueryListOptions(value, embedded, views, link);
    }

    public QueryListOptions asEmbedded() {
        return new QueryListOptions(variant, true, views, link);
    }

    public QueryListOptions withViews(QueryListViews value) {
        return new QueryListOptions(variant, embedded, value, link);
    }

    public QueryListOptions withLink(Supplier<String> value) {
        return new QueryListOptions(variant, embedded, views, value);
    }

    /** Ключ формы для «Видов»: {@code querylist.<ключ списка>[.<вариант>]}. */
    public String formKey(String listKey) {
        return "querylist." + listKey + (variant == null ? "" : "." + variant);
    }
}
