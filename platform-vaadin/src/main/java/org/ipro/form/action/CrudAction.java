package org.ipro.form.action;

import org.ipro.form.action.ActionRequirement.Level;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Платформенные действия по умолчанию (E1.1) — таблица §2.2 плана E1, выраженная данными.
 *
 * <p>Здесь же живут их стабильные {@code id} ({@code crud.*}). Заголовки правятся свободно —
 * {@link ActionId} от них не зависит.</p>
 *
 * <p>Состав тулбара списка — {@link #listToolbarDefaults()}, состав подвала карточки —
 * {@link #itemFooterDefaults()}. Действия карточки появляются в E1.5: у них решение зависит от
 * состояния объекта ({@link ActionContext.RowState}), а причина просмотра — типизированная
 * ({@code TYPE_READ_ONLY} / {@code ACCESS_DENIED}, §2.5 плана).</p>
 */
public enum CrudAction {

    /** Создание: нужен {@code CREATE} типа и заполненный обязательный контекст. */
    CREATE(ActionRequirement.of(Level.REQUIRED, Level.ANY, Level.ANY, Level.ANY, false, true)),

    /**
     * Копирование: нужен {@code CREATE} результата, {@code DETAIL} источника и строка.
     * Обязательный контекст копию намеренно не гейтит — это текущее предметное поведение,
     * а правило destination context (§4.1.5 плана) принимается отдельным решением.
     */
    COPY(ActionRequirement.of(Level.REQUIRED, Level.ANY, Level.ANY, Level.REQUIRED, true, false)),

    /** Изменение: нужен {@code DETAIL} и {@code UPDATE}. */
    EDIT(ActionRequirement.of(Level.ANY, Level.REQUIRED, Level.ANY, Level.REQUIRED, true, false)),

    /**
     * Просмотр: {@code DETAIL} есть, а изменение недоступно. {@code UNAVAILABLE} делает действие
     * неприменимым там, где изменение доступно, — поэтому {@code open} не подменяет {@code edit},
     * а дополняет его: у типа без generic {@code UPDATE} и у строки, изменение которой отказано
     * правами (E1.2-pilot: без этого у такой строки не оставалось пути чтения).
     */
    OPEN(ActionRequirement.of(Level.ANY, Level.UNAVAILABLE, Level.ANY, Level.REQUIRED, true, false)),

    /** Удаление: нужен {@code DELETE} и строка. */
    DELETE(ActionRequirement.of(Level.ANY, Level.ANY, Level.REQUIRED, Level.ANY, true, false)),

    /** Обновление списка: не требует ни строки, ни прав записи. */
    REFRESH(ActionRequirement.none()),

    /**
     * Копирование публичной ссылки (E2.1): ни права, ни строка, ни обязательный контекст не
     * нужны — нужен <b>адрес</b>, поэтому требование у этого действия одно и названное
     * ({@link ActionRequirement#linkable()}).
     *
     * <p>Действие одно на две поверхности, и это не экономия: у списка и у карточки различается
     * только то, на что ведёт адрес ({@code /lists/...} и {@code /records/...}), а решение о его
     * наличии считает один и тот же код. Копирование адреса не является изменением данных, поэтому
     * список, открытый только для просмотра, ссылку сохраняет: именно там она и нужна.</p>
     */
    COPY_LINK(ActionRequirement.linkable()),

    /**
     * Сохранение карточки: у новой записи требуется {@code CREATE}, у существующей — {@code UPDATE}.
     *
     * <p>Это единственное действие, у которого операция выбирается состоянием объекта
     * ({@link ActionContext.RowState}), и то же решение отвечает на два вопроса UI: можно ли
     * править открытую карточку и есть ли в подвале «Сохранить». Обязательный контекст не
     * требуется: открытие новой записи уже прошло через {@code crud.create}, а собственное
     * заполнение полей проверяет форма ({@code ItemForm.validate}).</p>
     */
    SAVE(ActionRequirement.byRowState(Level.REQUIRED, Level.REQUIRED, Level.ANY, Level.ANY,
        false, false));

    /**
     * Платформенный состав тулбара списка (E1.3): id, требования, порядок и подписи по умолчанию.
     *
     * <p>Тип и вариант здесь {@code null} — это generic default, применимый к любому типу; предметные
     * отличия выражаются {@code override} в том же ключе (§2.4 плана), а не правкой этой таблицы.</p>
     *
     * <p>Подпись — представление, а не идентичность: {@code id} от неё не зависит. Иконка намеренно
     * не задана: до generic renderer'а её держит UI, и выдумывать сейчас схему имён значило бы
     * зафиксировать контракт без потребителя.</p>
     */
    public static List<ActionDefinition> listToolbarDefaults() {
        return List.of(
            ActionDefinition.platformDefault(CREATE, ActionSurface.LIST_TOOLBAR, "Создать", null, 0),
            ActionDefinition.platformDefault(COPY, ActionSurface.LIST_TOOLBAR, "Копировать", null, 1),
            ActionDefinition.platformDefault(EDIT, ActionSurface.LIST_TOOLBAR, "Изменить", null, 2),
            ActionDefinition.platformDefault(OPEN, ActionSurface.LIST_TOOLBAR, "Просмотр", null, 3),
            ActionDefinition.platformDefault(DELETE, ActionSurface.LIST_TOOLBAR, "Удалить", null, 4),
            ActionDefinition.platformDefault(REFRESH, ActionSurface.LIST_TOOLBAR, "Обновить", null, 5),
            // Ссылка на список — после обновления и всегда предпоследним требованием состава:
            // она адресует сам список, а не выбранную строку, поэтому ей не нужно ни выделения,
            // ни открытой карточки.
            ActionDefinition.platformDefault(COPY_LINK, ActionSurface.LIST_TOOLBAR,
                "Скопировать ссылку", null, 6));
    }

    /**
     * Платформенный состав подвала карточки (E1.5): «Сохранить» — единственное действие, которое
     * карточка обязана уметь объявить. «Закрыть» (в карточке — «Отмена») здесь сознательно нет:
     * у него нет условия, кроме открытой формы, а потребителя такого решения (подвал, который
     * собирается из решений) в E1.5 нет — он появится вместе с меню и предметными командами E1.6.
     */
    public static List<ActionDefinition> itemFooterDefaults() {
        return List.of(
            ActionDefinition.platformDefault(SAVE, ActionSurface.ITEM_FOOTER, "Сохранить", null, 0),
            ActionDefinition.platformDefault(COPY_LINK, ActionSurface.ITEM_FOOTER,
                "Скопировать ссылку", null, 1));
    }

    /** Полный платформенный состав: список и карточка. */
    public static List<ActionDefinition> platformDefaults() {
        return Stream.concat(listToolbarDefaults().stream(), itemFooterDefaults().stream())
            .toList();
    }

    private final ActionRequirement requirement;

    CrudAction(ActionRequirement requirement) {
        this.requirement = requirement;
    }

    /** Стабильный id действия; не выводится из заголовка. */
    public ActionId id() {
        return ActionId.of("crud." + name().toLowerCase(Locale.ROOT));
    }

    /** Декларативные требования действия. */
    public ActionRequirement requirement() {
        return requirement;
    }
}
