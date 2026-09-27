package org.ipro.form.action;

import java.util.Objects;

/**
 * Описание действия (E1.1): идентичность, поверхность, место отображения и требования.
 *
 * <p>{@code entityType} и {@code variant} — часть ключа регистрации. {@code null} означает
 * «любой»: так платформенный default {@code crud.create} применим к любому типу, а предметный
 * {@code override} называет конкретный тип. Вариант здесь — <b>ключ</b>, а не фильтр
 * применимости: фильтр (легаси {@code ListCommand.appliesToVariant}) — предикат, и подменять им
 * ключ значило бы снова получить неоднозначность, которую реестр как раз устраняет.</p>
 *
 * <p>{@code title}/{@code iconName}/{@code order} — представление. {@code title} может быть
 * {@code null} для иконочного действия ({@code crud.refresh}): подпись не является
 * идентификатором, поэтому её отсутствие допустимо, а её изменение безопасно.</p>
 *
 * @param id           стабильный идентификатор
 * @param surface      поверхность исполнения
 * @param title        подпись ({@code null} — без подписи)
 * @param iconName     имя иконки Vaadin ({@code null} — без иконки)
 * @param entityType   тип сущности ({@code null} — любой)
 * @param variant      вариант формы ({@code null} — любой)
 * @param order        порядок отображения внутри поверхности
 * @param requirement  декларативные требования
 * @param visible      структурная применимость; {@code false} — явное подавление (suppress-override)
 */
public record ActionDefinition(ActionId id,
                               ActionSurface surface,
                               String title,
                               String iconName,
                               Class<?> entityType,
                               String variant,
                               int order,
                               ActionRequirement requirement,
                               boolean visible) {

    public ActionDefinition {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(surface, "surface must not be null");
        Objects.requireNonNull(requirement, "requirement must not be null");
        if (title != null && title.isBlank()) {
            throw new IllegalArgumentException("Подпись действия не может быть пустой строкой: «"
                + id + "»");
        }
    }

    /** Платформенный default: применим к любому типу и варианту. */
    public static ActionDefinition platformDefault(CrudAction action, ActionSurface surface,
                                                   String title, String iconName, int order) {
        return new ActionDefinition(action.id(), surface, title, iconName, null, null, order,
            action.requirement(), true);
    }

    /** Действие конкретного типа сущности. */
    public static ActionDefinition forEntity(ActionId id, ActionSurface surface, Class<?> entityType,
                                             String title, String iconName, int order,
                                             ActionRequirement requirement) {
        return new ActionDefinition(id, surface, title, iconName, entityType, null, order,
            requirement, true);
    }

    /**
     * Подавление (suppress-override) платформенного действия для конкретного типа (E1.4): действие
     * остаётся зарегистрированным, но этому типу не выдаётся вовсе.
     *
     * <p>Это <b>UI-policy типа</b>, а не разрешение операции: capability типа не меняется и
     * серверная граница не ослабляется (§2.3 плана E1). Так выражается ситуация «generic действие
     * у типа формально возможно, но правильный путь другой» — например, значение атрибута создаётся
     * интернирующим «Добавить значение», а не пустым generic созданием.</p>
     *
     * <p>Требование у подавленной записи — {@link ActionRequirement#none()}: оно не вычисляется,
     * потому что решение останавливается на невидимости раньше требований. Целевая регистрация при
     * сборке реестра обязана существовать — иначе старт падает с названной причиной, и опечатка в
     * действии не превращается в молчаливо новое действие.</p>
     *
     * @param action     платформенное действие, которое скрывается
     * @param surface    поверхность, на которой оно скрывается
     * @param entityType тип, которому действие не выдаётся
     */
    public static ActionDefinition suppress(CrudAction action, ActionSurface surface,
                                            Class<?> entityType) {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(surface, "surface must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null: подавление всегда про"
            + " конкретный тип, иначе оно скрыло бы действие всем");
        return new ActionDefinition(action.id(), surface, null, null, entityType, null, 0,
            ActionRequirement.none(), false);
    }

    /** Копия с другой видимостью — используется suppress-override. */
    public ActionDefinition withVisible(boolean visible) {
        return new ActionDefinition(id, surface, title, iconName, entityType, variant, order,
            requirement, visible);
    }

    /** Копия с другим порядком. */
    public ActionDefinition withOrder(int order) {
        return new ActionDefinition(id, surface, title, iconName, entityType, variant, order,
            requirement, visible);
    }

    /** Копия с другим вариантом (уточнение ключа). */
    public ActionDefinition withVariant(String variant) {
        return new ActionDefinition(id, surface, title, iconName, entityType, variant, order,
            requirement, visible);
    }
}
