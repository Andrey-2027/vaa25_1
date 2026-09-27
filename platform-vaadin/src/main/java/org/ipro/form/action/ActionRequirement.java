package org.ipro.form.action;

/**
 * Декларативные требования действия (E1.1).
 *
 * <p>Требования заданы <b>данными</b>, а не ветвлением по {@link ActionId}: приложение должно
 * уметь объявить предметное действие, не добавляя новый {@code case} в решающую функцию.
 * Каждое требование — четырёхзначное ({@link Level}): операция обязана быть, обязана
 * отсутствовать у типа, обязана быть недоступна (тип <i>или</i> права) либо не участвует
 * в решении.</p>
 *
 * <p><b>Три способа сказать «нужна операция» и два — «не нужна».</b> {@link Level#REQUIRED}
 * требует и capability типа, и права. {@link Level#FORBIDDEN} — «операции нет у типа»
 * (структурное условие, только capability). {@link Level#UNAVAILABLE} — «операция недоступна»:
 * типа она может не быть <i>либо</i> права её не разрешать на выбранной строке. Именно
 * {@link Level#UNAVAILABLE} выражает {@code crud.open}: просмотр предлагается там, где изменение
 * недоступно <b>по любой причине</b>, иначе строка, изменение которой отказано правами, не имела
 * бы пути чтения вовсе (E1.2-pilot: `FORBIDDEN` такого случая не покрывал).</p>
 *
 * <p>«Отсутствие операции у типа» (capability) и «отказ в правах» (permission) намеренно
 * разделены по уровням: отсутствие структурной операции делает действие неприменимым
 * (<b>скрыто</b>), а отказ в правах — недоступным (<b>неактивно с причиной</b>).
 * {@code UNAVAILABLE} смотрит на оба входа сразу, потому что говорит о <i>применимости</i>
 * действия, а не о том, может ли пользователь его выполнить.</p>
 *
 * <p><b>Операция по состоянию объекта (E1.5).</b> У {@code crud.save} одна запись обслуживает
 * и создание, и изменение, поэтому требование к операции у него выбирается состоянием объекта
 * ({@link ActionContext.RowState}): для новой записи действует {@code create}, для существующей —
 * {@code update}. Это не свободный флаг: он есть ровно у действий, которые объявлены на карточку,
 * и без него пришлось бы либо требовать обе операции сразу (тогда тип с одним {@code CREATE} не
 * смог бы сохранить новую запись), либо различать режим ветвлением по {@code id} внутри политики
 * — то, что E1 устраняет.</p>
 *
 * @param create                   требование к {@code CREATE} типа
 * @param update                   требование к {@code UPDATE} типа
 * @param delete                   требование к {@code DELETE} типа
 * @param detail                   требование к чтению {@code DETAIL} типа
 * @param selectionRequired        нужна выбранная строка
 * @param requiredContextRequired  нужен заполненный обязательный контекст
 * @param rowStateSelectsOperation выбирать действующую операцию по состоянию объекта
 * @param linkableRequired         нужен публичный адрес формы (E2.1): действие применимо только
 *                                 там, где ссылку действительно можно выдать
 */
public record ActionRequirement(Level create, Level update, Level delete, Level detail,
                                boolean selectionRequired, boolean requiredContextRequired,
                                boolean rowStateSelectsOperation, boolean linkableRequired) {

    /**
     * Уровень требования: обязано быть, обязано отсутствовать у типа, обязано быть недоступно,
     * не участвует.
     */
    public enum Level {

        /** Операция обязана быть: нужны и capability типа, и право пользователя. */
        REQUIRED,

        /**
         * Операции <b>не должно быть у типа</b> (только capability): иначе действие
         * неприменимо. Права здесь не участвуют — для «недоступно по любой причине» есть
         * {@link #UNAVAILABLE}.
         */
        FORBIDDEN,

        /**
         * Операция должна быть <b>недоступна</b>: её либо не поддерживает тип, либо не разрешают
         * права на выбранной строке. Используется действиями, которые дополняют другое действие
         * там, где оно неприменимо, — так {@code crud.open} даёт путь чтения строке с отказом
         * в правах на изменение.
         *
         * <p>Без выделенной строки строчные права не проверены (см. {@link ActionPermission}),
         * поэтому операция считается доступной, а действие — неприменимым: состав кнопок не
         * зависит от того, выбрана ли строка.</p>
         */
        UNAVAILABLE,

        /** Операция не участвует в решении. */
        ANY
    }

    public ActionRequirement {
        create = require(create, "create");
        update = require(update, "update");
        delete = require(delete, "delete");
        detail = require(detail, "detail");
    }

    private static Level require(Level level, String name) {
        if (level == null) {
            throw new IllegalArgumentException("Уровень требования «" + name + "» не задан");
        }
        return level;
    }

    /** Действие без требований: всегда применимо и доступно. */
    public static ActionRequirement none() {
        return new ActionRequirement(Level.ANY, Level.ANY, Level.ANY, Level.ANY, false, false,
            false, false);
    }

    public static ActionRequirement of(Level create, Level update, Level delete, Level detail,
                                       boolean selectionRequired, boolean requiredContextRequired) {
        return new ActionRequirement(create, update, delete, detail,
            selectionRequired, requiredContextRequired, false, false);
    }

    /**
     * Действие, которое существует ровно тогда, когда форму можно адресовать (E2.1): ни прав, ни
     * строки, ни контекста не требуется — требуется ссылка.
     *
     * <p>Требование выражено данными, как и остальные: решающая функция не знает, что за действие
     * «скопировать ссылку», и прикладное действие с тем же требованием ведёт себя одинаково.</p>
     *
     * <p>Недоступный адрес делает действие <b>неприменимым</b>, а не «видимым-но-серым»: серая
     * кнопка «Скопировать ссылку» при отсутствующем контракте адреса обещала бы действие, которого
     * платформа не умеет ни при каком состоянии экрана. Это отличается от «нужно выделить строку»,
     * где действие применимо и ждёт пользователя.</p>
     */
    public static ActionRequirement linkable() {
        return new ActionRequirement(Level.ANY, Level.ANY, Level.ANY, Level.ANY, false, false,
            false, true);
    }

    /**
     * Требования действия, у которого операция выбирается состоянием объекта (карточка,
     * {@code crud.save}): {@code whenRowIsNew} действует до появления объекта,
     * {@code whenRowIsExisting} — после. Вторая операция в решении не участвует.
     *
     * @param whenRowIsNew      требование к {@code CREATE} для новой записи
     * @param whenRowIsExisting требование к {@code UPDATE} для существующей записи
     */
    public static ActionRequirement byRowState(Level whenRowIsNew, Level whenRowIsExisting,
                                               Level delete, Level detail,
                                               boolean selectionRequired,
                                               boolean requiredContextRequired) {
        return new ActionRequirement(whenRowIsNew, whenRowIsExisting, delete, detail,
            selectionRequired, requiredContextRequired, true, false);
    }

    /** Требуется только выбранная строка (действия чтения списка). */
    public static ActionRequirement selectionOnly() {
        return new ActionRequirement(Level.ANY, Level.ANY, Level.ANY, Level.ANY, true, false,
            false, false);
    }

    public boolean requiresSelection() {
        return selectionRequired;
    }

    public boolean requiresRequiredContext() {
        return requiredContextRequired;
    }

    /** Выбирать действующую операцию по {@link ActionContext.RowState} (см. {@link #byRowState}). */
    public boolean rowStateSelectsOperation() {
        return rowStateSelectsOperation;
    }

    /** Требуется публичный адрес формы (E2.1, см. {@link #linkable()}). */
    public boolean requiresLinkable() {
        return linkableRequired;
    }
}
