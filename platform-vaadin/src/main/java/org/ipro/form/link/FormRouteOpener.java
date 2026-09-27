package org.ipro.form.link;

import org.ipro.form.coordinator.FormNavigator;

import java.util.Objects;

/**
 * Типизированный route-вход (E2.2, ADR-0009 §6): адрес превращается в открытую форму либо в
 * названный исход, и ничего другого.
 *
 * <p><b>Почему это отдельный тип, а не метод координатора.</b> У открытия по адресу две части, и
 * они решаются разными источниками: «существует ли такой адрес и что он описывает» знает
 * {@link FormRouteCatalog} (публикация, вариант, запрет сценария), а «как открыть форму в этом UI»
 * знает {@link FormNavigator}. Если бы host складывал эти ответы сам, порядок проверок из §6
 * пришлось бы повторять в каждом host'е, а он — часть контракта: сначала каталог (обращений к
 * данным нет), потом чтение, потом вкладка. Здесь этот порядок записан один раз, а координатор
 * вызывается ровно на последнем шаге и ровно с найденным классом — не с тем, что host написал в
 * строке запроса.</p>
 *
 * <p><b>Класс берётся из каталога, а не из адреса.</b> Внешний ключ — это ключ публикации; никакого
 * {@code Class.forName} по строке адреса не происходит. Неизвестный ключ заканчивается
 * {@code InvalidRoute} до любого чтения и без создания вкладки.</p>
 *
 * <p><b>Режим открытия приложения не меняется.</b> Прямой URL всегда открывает форму в Workspace
 * (§3 ADR): мутабельная настройка координатора ({@code setItemFormOpenMode}) при этом не трогается —
 * иначе открытие ссылки изменило бы поведение обычных вызовов у того же пользователя.</p>
 */
public final class FormRouteOpener {

    private final FormRouteCatalog catalog;
    private final FormRouteCodec codec;
    private final FormNavigator navigator;

    /**
     * Навигатор, а не координатор: этот тип — обещание платформы (APP_API), и его подпись не может
     * называть INTERNAL-класс реализации. Зависимость от {@link FormNavigator} — тот же приём, что
     * у {@code FormContext}: приложение и инициализаторы видят контракт, а не реализацию.
     */
    public FormRouteOpener(FormRouteCatalog catalog, FormRouteCodec codec,
                           FormNavigator navigator) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.navigator = Objects.requireNonNull(navigator, "navigator must not be null");
    }

    /**
     * Открыть адрес как строку: разбор и открытие в одном вызове — то, что нужно host'у, у
     * которого на руках путь из окна браузера, а не разобранный маршрут.
     */
    public OpenResult openAddress(String address) {
        FormRouteParseResult parsed = codec.parse(address);
        if (parsed instanceof FormRouteParseResult.Parsed valid) {
            return open(valid.route());
        }
        return OpenResult.invalidRoute(parsed.rejection()
            .orElse("Адрес вне грамматики: разбор отклонён без причины, что само по себе дефект кодека"));
    }

    /** Результат разбора как исход открытия: отклонённый адрес — это {@code InvalidRoute}. */
    public OpenResult open(FormRouteParseResult parsed) {
        Objects.requireNonNull(parsed, "parsed must not be null");
        return parsed.parsedRoute().map(this::open)
            .orElseGet(() -> OpenResult.invalidRoute(parsed.rejection().orElse("Адрес отклонён")));
    }

    /**
     * Открыть разобранный маршрут: каталог → открытие.
     *
     * <p>Порядок проверок фиксирован §6 ADR и здесь является кодом, а не комментарием: пока
     * каталог не подтвердил публикацию и вариант, навигатор не вызывается вовсе — то есть
     * неизвестный адрес не приводит ни к обращению к данным, ни к появлению вкладки.</p>
     */
    public OpenResult open(FormRoute route) {
        Objects.requireNonNull(route, "route must not be null");

        PublishedFormRoute published = catalog.find(route.entityKey()).orElse(null);
        if (published == null) {
            return OpenResult.invalidRoute("Ключ '" + route.entityKey()
                + "' не публикуется: такого адреса нет");
        }

        // «default» — не ключ реестра, а отсутствие варианта (та же норма, что в генерации
        // ссылки): строка была бы вторым выражением того же состояния.
        String variant = route.variant();
        if (variant != null && (PublishedFormRoute.DEFAULT_VARIANT.equals(variant)
                || !published.supports(route.kind(), variant))) {
            return OpenResult.invalidRoute("Вариант '" + variant + "' не зарегистрирован для "
                + route.kind().segment() + " типа " + published.entityClass().getSimpleName());
        }

        NotLinkableReason blocker = published.notLinkable(route.kind(), variant).orElse(null);
        if (blocker == NotLinkableReason.SCENARIO_NOT_ALLOWED) {
            // Тип публикуемый, но автономного чтения этого вида у него нет: адрес известен,
            // доступ закрыт — это 403, а не 404 и не пустая страница.
            return OpenResult.forbidden(route, "Тип " + published.entityClass().getSimpleName()
                + " не допускает автономного " + route.kind().segment() + "-чтения");
        }
        if (blocker != null) {
            return OpenResult.notLinkable(route, blocker);
        }

        return route.kind() == FormRouteKind.ITEM
            ? navigator.openRoutedRecord(published.entityClass(), route)
            : navigator.openRoutedList(published.entityClass(), route);
    }
}
