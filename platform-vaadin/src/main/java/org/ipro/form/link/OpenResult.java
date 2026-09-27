package org.ipro.form.link;

import java.util.Optional;

/**
 * Результат открытия адреса формы (E2.2, ADR-0009 §6): одна матрица вместо «что-нибудь покажем».
 *
 * <p><b>Зачем отдельный тип, а не {@code boolean}/{@code Optional}.</b> Исходов у адреса шесть,
 * и они различаются не оттенком, а тем, что обязан показать host: «нет такого адреса» (404),
 * «есть, но вам нельзя» (403), «запись скрыта или удалена» (404 без различения), «форма не имеет
 * публичного адреса» (в адрес никогда не попадает) и «сломалась инфраструктура» (не 404 — иначе
 * дефект маскируется под отсутствующую запись). Булев результат схлопнул бы их в одно, а
 * {@code Optional} — в «получилось/не получилось», и различение 404/403 пришлось бы собирать
 * заново прикладным кодом, то есть второй формулой поверх уже принятой.</p>
 *
 * <p><b>Исход — не исключение.</b> Route-открытие не бросает «запись не найдена» наружу: host
 * получает значение и рисует страницу состояния, а не ловит случайный {@code RuntimeException}.
 * Исключения остаются там, где означают дефект вызова (например, отсутствие активного UI).</p>
 *
 * <p><b>Метка для аудита.</b> {@link #outcome()} — стабильная строка для route audit (ADR-0009 §8):
 * она не зависит от текста сообщения, не содержит id и различает hidden и missing так же мало,
 * как и весь остальной контракт.</p>
 */
public sealed interface OpenResult {

    /** Адрес открыт: вкладка (или существующая вкладка) показывает запрошенную форму. */
    record Opened(FormRoute route) implements OpenResult {

        @Override
        public String outcome() {
            return "opened";
        }

        @Override
        public String message() {
            return "";
        }
    }

    /**
     * Строки нет либо её скрыла row-level RLS — <b>неразличимо</b>.
     *
     * <p>Различение запрещено контрактом (ADR-0009 §6): иначе адрес становится инструментом
     * проверки существования чужих записей. Тот же исход отдаётся, если запись исчезла между
     * preflight и сборкой формы.</p>
     */
    record NotFound(FormRoute route) implements OpenResult {

        @Override
        public String outcome() {
            return "not-found";
        }

        @Override
        public String message() {
            return "Запись не найдена или недоступна";
        }
    }

    /**
     * Адрес известен, но доступ к сценарию чтения закрыт: class-level deny, либо тип не
     * допускает автономного {@code LIST}/{@code DETAIL}-чтения.
     */
    record Forbidden(FormRoute route, String message) implements OpenResult {

        @Override
        public String outcome() {
            return "forbidden";
        }
    }

    /** Адрес вне опубликованного набора: неизвестный ключ, вид формы или вариант. Такого адреса нет. */
    record InvalidRoute(String message) implements OpenResult {

        @Override
        public String outcome() {
            return "invalid-route";
        }

    }

    /**
     * Форма существует, но публичного адреса у неё нет (ADR-0009 §5): список с обязательным
     * контекстом, недоступный сценарий, непубликуемый тип. Прямой адрес такой формы не
     * открывается — он и не выдавался.
     */
    record NotLinkable(FormRoute route, NotLinkableReason reason) implements OpenResult {

        @Override
        public String outcome() {
            return "not-linkable";
        }

        @Override
        public String message() {
            return "У этой формы нет публичного адреса: " + reason;
        }
    }

    /** Инфраструктурный отказ (нет рабочей области, ошибка сервиса): не «не найдено». */
    record Unavailable(FormRoute route, String message) implements OpenResult {

        @Override
        public String outcome() {
            return "unavailable";
        }
    }

    /** Метка исхода для route audit: стабильная, без id и без текста сообщения. */
    String outcome();

    /** Текст для пользователя; пустой у {@link Opened}. */
    String message();

    /**
     * Маршрут, к которому относится исход; пусто только у {@link InvalidRoute}.
     *
     * <p>Имя отличается от компонентов записей ({@code route}) намеренно: у record'а акссесор
     * компонента обязан возвращать сам компонент, поэтому вариант «{@code route()} возвращает
     * {@code Optional}» в языке невыразим — а различие «маршрута нет вовсе» и «маршрут есть»
     * существует и его видно в типе.</p>
     */
    default Optional<FormRoute> resultRoute() {
        return this instanceof Opened opened ? Optional.of(opened.route())
            : this instanceof NotFound notFound ? Optional.of(notFound.route())
            : this instanceof Forbidden forbidden ? Optional.of(forbidden.route())
            : this instanceof NotLinkable notLinkable ? Optional.of(notLinkable.route())
            : this instanceof Unavailable unavailable ? Optional.of(unavailable.route())
            : Optional.empty();
    }

    /** Открылось ли (единственный положительный исход). */
    default boolean opened() {
        return this instanceof Opened;
    }

    static OpenResult opened(FormRoute route) {
        return new Opened(route);
    }

    static OpenResult notFound(FormRoute route) {
        return new NotFound(route);
    }

    static OpenResult forbidden(FormRoute route, String message) {
        return new Forbidden(route, message == null ? "" : message);
    }

    static OpenResult invalidRoute(String message) {
        return new InvalidRoute(message);
    }

    static OpenResult notLinkable(FormRoute route, NotLinkableReason reason) {
        return new NotLinkable(route, reason);
    }

    static OpenResult unavailable(FormRoute route, String message) {
        return new Unavailable(route, message == null ? "" : message);
    }
}
