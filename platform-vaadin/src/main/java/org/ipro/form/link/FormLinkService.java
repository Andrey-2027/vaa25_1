package org.ipro.form.link;

import java.util.Objects;
import java.util.Optional;

/**
 * Генерация публичной ссылки на форму (E2.1, ADR-0009 §5): {@code format} и правильность
 * ключа собираются в одном ответе — либо канонический адрес, либо причина.
 *
 * <p>Ссылка строится из типизированного запроса, а не конкатенацией строк в UI: только здесь
 * проверяются публикуемость типа, существование варианта и то, восстанавливается ли форма из
 * адреса. Вызывающий код не может получить «полуссылку»: адрес возвращается вместе с
 * разобранным маршрутом, а отказ — с причиной.</p>
 *
 * <p>Порядок проверок повторяет §6 ADR: публикация → грамматика запроса → вариант →
 * ограничения типа (сценарий чтения, обязательный контекст). Чтения данных здесь нет: сервис
 * ничего не знает о записи, он отвечает только на вопрос «адрес этой формы существует?».</p>
 */
public class FormLinkService {

    private final FormRouteCatalog catalog;
    private final FormRouteCodec codec;

    public FormLinkService(FormRouteCatalog catalog, FormRouteCodec codec) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
    }

    /** Ссылка на карточку существующей записи, default-вариант формы. */
    public FormLinkResult linkToRecord(Class<?> type, Long id) {
        return link(FormRouteKind.ITEM, type, id, null);
    }

    /** Ссылка на карточку существующей записи в именованном варианте формы. */
    public FormLinkResult linkToRecord(Class<?> type, Long id, String variant) {
        return link(FormRouteKind.ITEM, type, id, variant);
    }

    /** Ссылка на список, default-вариант формы. */
    public FormLinkResult linkToList(Class<?> type) {
        return link(FormRouteKind.LIST, type, null, null);
    }

    /** Ссылка на список в именованном варианте формы. */
    public FormLinkResult linkToList(Class<?> type, String variant) {
        return link(FormRouteKind.LIST, type, null, variant);
    }

    /** Ссылка на форму: {@code null} id — default-вариант списка, {@code null} variant — default-форма. */
    public FormLinkResult link(FormRouteKind kind, Class<?> type, Long id, String variant) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(type, "type must not be null");
        PublishedFormRoute published = catalog.find(type).orElse(null);
        if (published == null) {
            return FormLinkResult.notLinkable(NotLinkableReason.NOT_PUBLISHED);
        }
        if (kind == FormRouteKind.ITEM) {
            if (id == null) {
                return FormLinkResult.notLinkable(NotLinkableReason.MISSING_ID);
            }
            if (id <= 0) {
                return FormLinkResult.notLinkable(NotLinkableReason.INVALID_ID);
            }
        } else if (id != null) {
            return FormLinkResult.notLinkable(NotLinkableReason.UNEXPECTED_ID);
        }

        // «default» — не ключ реестра, а отсутствие варианта. Строку отклоняем, а не превращаем
        // молча в default-ветку: иначе у одного состояния было бы два выражения (параметр и его
        // отсутствие), и вызывающий код не знал бы, какой из них канонический.
        String requestedVariant = variant == null || variant.isBlank() ? null : variant;
        if (requestedVariant != null
                && (PublishedFormRoute.DEFAULT_VARIANT.equals(requestedVariant)
                    || !published.supports(kind, requestedVariant))) {
            return FormLinkResult.notLinkable(NotLinkableReason.UNKNOWN_VARIANT);
        }
        Optional<NotLinkableReason> blocked = published.notLinkable(kind, requestedVariant);
        if (blocked.isPresent()) {
            return FormLinkResult.notLinkable(blocked.get());
        }

        FormRoute route = new FormRoute(kind, published.entityKey(),
            kind == FormRouteKind.ITEM ? id : null, requestedVariant);
        return FormLinkResult.linkable(route, codec.format(route));
    }
}
