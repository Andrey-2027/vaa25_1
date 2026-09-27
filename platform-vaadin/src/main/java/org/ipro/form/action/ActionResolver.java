package org.ipro.form.action;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Резолвер действий одной поверхности и одного типа (E1.3): связывает реестр (какое действие
 * объявлено для типа и варианта) с провайдером входов (что известно о типе и строке) и решающей
 * функцией.
 *
 * <p>Резолвер существует затем, чтобы у каждого host'а был <b>один</b> вызов на действие вместо
 * собственной комбинации «достать описание → собрать права → решить». Именно эта комбинация
 * раньше дублировалась в UI, и именно её устраняет E1.</p>
 *
 * <p>Экземпляр привязан к поверхности, типу и варианту: {@code ActionSurface.LIST_TOOLBAR} +
 * {@code Nomenclature} + {@code null} — это один список. Смена выделения не создаёт новый резолвер:
 * строка передаётся в {@link #decide} и {@link #context}, потому что решение зависит от строки, а
 * состав действий — нет.</p>
 *
 * <p>Незарегистрированное действие не «падает молча» и не считается разрешённым: решение приходит
 * скрытым с названной причиной. Так подавление (suppress-override) и отсутствие действия выглядят
 * для UI одинаково — действием, которого нет, — но по разным причинам.</p>
 */
public final class ActionResolver {

    private final ActionRegistry registry;
    private final ActionContextProvider provider;
    private final ActionHandlerRegistry handlerRegistry;
    private final ActionSurface surface;
    private final Class<?> entityType;
    private final String variant;

    public ActionResolver(ActionRegistry registry, ActionContextProvider provider,
                          ActionHandlerRegistry handlerRegistry, ActionSurface surface,
                          Class<?> entityType, String variant) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry,
            "handlerRegistry must not be null");
        this.surface = Objects.requireNonNull(surface, "surface must not be null");
        this.entityType = Objects.requireNonNull(entityType, "entityType must not be null");
        this.variant = variant;
    }

    /** Описание действия для этого типа и варианта, если оно зарегистрировано. */
    public Optional<ActionDefinition> definition(CrudAction action) {
        Objects.requireNonNull(action, "action must not be null");
        return registry.resolve(surface, entityType, variant, action.id());
    }

    /**
     * Все действия поверхности для этого типа и варианта, включая прикладные (E1.6a).
     *
     * <p>Host не выбирает, какие действия рисовать, — он рисует то, что объявлено, а доступность
     * спрашивает у решения. Именно поэтому прикладное действие не может «потеряться» из-за того,
     * что рендерер знает только перечень CRUD.</p>
     */
    public List<ActionDefinition> definitions() {
        return registry.resolve(surface, entityType, variant);
    }

    /** Исполнитель объявленного действия, если он есть у этого типа и варианта. */
    public Optional<ActionHandler> handler(ActionDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        return handlerRegistry.handlerOf(surface, entityType, variant, definition.id());
    }

    /**
     * Решение по конкретному объявлению. Нужно host'ам, рисущим объявленные действия: у них на
     * руках описание, а не перечисление CRUD, и решать надо именно его.
     */
    public ActionDecision decide(ActionDefinition definition, Object row,
                                 boolean requiredContextComplete) {
        Objects.requireNonNull(definition, "definition must not be null");
        return ActionPolicy.decide(definition, context(row, requiredContextComplete));
    }

    /**
     * Контекст решения для текущего выделения. {@code row == null} — контекст списка: строковые
     * права не вычисляются (§2.6 плана).
     */
    public ActionContext context(Object row, boolean requiredContextComplete) {
        return row == null
            ? provider.listContext(entityType, variant, requiredContextComplete)
            : provider.rowContext(entityType, variant, row, requiredContextComplete);
    }

    /** Решение по действию. Входы собираются здесь, поэтому у host'а ровно один вызов на действие. */
    public ActionDecision decide(CrudAction action, Object row, boolean requiredContextComplete) {
        Objects.requireNonNull(action, "action must not be null");
        return definition(action)
            .map(definition -> decide(definition, row, requiredContextComplete))
            .orElseGet(() -> ActionDecision.hidden(ActionDecision.Reason.NOT_APPLICABLE,
                "Действие «" + action.id() + "» не объявлено для типа "
                    + entityType.getSimpleName()));
    }
}
