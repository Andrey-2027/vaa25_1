package org.ipro.form.builder;

import org.ipro.form.action.ActionId;
import org.ipro.form.action.CrudAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Неизменяемое описание размещения команд списка.
 *
 * <p>Слой описывает только то, что в нём явно задано. {@link Composition#INHERIT} и
 * {@link PanelMode#INHERIT} означают «не переопределять нижний слой»; незаполненное свойство
 * отдельной команды также наследуется. Поэтому слои платформы, семейства, конфигурации
 * сущности, варианта и экземпляра можно накладывать друг на друга, не теряя чужие правки.</p>
 *
 * <p>Описание не создаёт UI, не хранит компоненты и не содержит второй формулы прав. Оно
 * применяется к уже существующим командам и их решениям.</p>
 */
public final class ListCommandLayout {

    /** Режим состава команд. */
    public enum Composition {
        /** Не менять состав нижнего слоя. */
        INHERIT,
        /** Автоматический состав: известные команды плюс будущие подходящие. */
        AUTO,
        /** Явный перечень; пустой перечень означает «ни одной команды». */
        EXPLICIT
    }

    /** Режим вывода рабочих команд на панель. */
    public enum PanelMode {
        /** Не менять режим нижнего слоя. */
        INHERIT,
        /** Платформенные умолчания для текущего состава. */
        DEFAULT,
        /** Все рабочие включённые команды на панели. */
        ALL,
        /** Явный перечень панели; остальные включённые команды остаются в «Ещё». */
        EXPLICIT
    }

    /** Представление команды на панели. */
    public enum CommandView {
        INHERIT,
        ICON_ONLY,
        ICON_AND_TEXT
    }

    /** Место команды. */
    public enum Placement {
        INHERIT,
        PANEL,
        MORE
    }

    /** Намерение проверки ссылки на команду. Это не требование прав. */
    public enum RefKind {
        /** Ключ должен быть известен статически; иначе ошибка конфигурации. */
        REQUIRED,
        /** Команда может отсутствовать, например необязательный модуль печати. */
        OPTIONAL,
        /** Локальная команда; разрешается на границе beforeClientResponse. */
        LOCAL
    }

    /**
     * Ссылка на команду с явным намерением проверки.
     */
    public record Ref(RefKind kind, ActionId id) {

        public Ref {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
        }

        public static Ref required(ActionId id) {
            return new Ref(RefKind.REQUIRED, id);
        }

        public static Ref optional(ActionId id) {
            return new Ref(RefKind.OPTIONAL, id);
        }

        public static Ref local(ActionId id) {
            return new Ref(RefKind.LOCAL, id);
        }
    }

    /**
     * Переопределение одной команды. {@code null} в свойстве означает наследование.
     */
    public record CommandOverride(CommandView view, Placement placement, Integer order,
                                  Boolean included, String title) {

        public CommandOverride(CommandView view, Placement placement, Integer order, Boolean included) {
            this(view, placement, order, included, null);
        }

        public CommandOverride {
            view = view == null ? CommandView.INHERIT : view;
            placement = placement == null ? Placement.INHERIT : placement;
            if (title != null && title.isBlank()) throw new IllegalArgumentException("Blank command title");
        }

        public CommandOverride withView(CommandView value) {
            return new CommandOverride(value, placement, order, included, title);
        }

        public CommandOverride withPlacement(Placement value) {
            return new CommandOverride(view, value, order, included, title);
        }

        public CommandOverride withOrder(Integer value) {
            return new CommandOverride(view, placement, value, included, title);
        }

        public CommandOverride withIncluded(Boolean value) {
            return new CommandOverride(view, placement, order, value, title);
        }

        public CommandOverride withTitle(String value) {
            return new CommandOverride(view, placement, order, included, value);
        }
    }

    private static final CommandOverride EMPTY =
        new CommandOverride(CommandView.INHERIT, Placement.INHERIT, null, null);

    private static final ActionId REPORT_PRINT = ActionId.of("report.print");

    private static final ListCommandLayout PLATFORM_DEFAULTS = platformDefaultsBuilder().build();

    private final Composition composition;
    private final List<Ref> commands;
    private final PanelMode panelMode;
    private final List<Ref> panel;
    private final Map<ActionId, CommandOverride> overrides;
    private final Map<ActionId, Ref> overrideRefs;

    private ListCommandLayout(Composition composition, List<Ref> commands, PanelMode panelMode,
                              List<Ref> panel, Map<ActionId, CommandOverride> overrides, Map<ActionId, Ref> overrideRefs) {
        this.composition = composition;
        this.commands = List.copyOf(commands);
        this.panelMode = panelMode;
        this.panel = List.copyOf(panel);
        this.overrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides));
        this.overrideRefs = Map.copyOf(overrideRefs);
    }

    /** Пустой Builder; все свойства начинают в INHERIT. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Платформенные умолчания §2: основные команды на панели, остальные — в «Ещё».
     * Необязательный {@code report.print} задаётся ключом, а не типом другого модуля.
     */
    public static ListCommandLayout platformDefaults() {
        return PLATFORM_DEFAULTS;
    }

    /** Известен ли ключ как платформенная статическая команда. */
    public static boolean isPlatformKey(ActionId id) {
        if (id == null) {
            return false;
        }
        for (CrudAction action : CrudAction.values()) {
            if (action.id().equals(id)) {
                return true;
            }
        }
        for (ListUiCommand command : ListUiCommand.values()) {
            if (command.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public Composition composition() {
        return composition;
    }

    public List<Ref> commands() {
        return commands;
    }

    public PanelMode panelMode() {
        return panelMode;
    }

    public List<Ref> panel() {
        return panel;
    }

    public Map<ActionId, CommandOverride> overrides() {
        return overrides;
    }

    /** Переопределение команды либо пустое значение. */
    public CommandOverride override(ActionId id) {
        return overrides.getOrDefault(id, EMPTY);
    }

    /**
     * Наложить этот слой на нижний. Переопределяются только явно заданные свойства.
     * Явный режим панели сбрасывает нижние индивидуальные placement-правила;
     * placement текущего слоя применяется поверх выбранного режима.
     */
    public ListCommandLayout overlayOn(ListCommandLayout lower) {
        Objects.requireNonNull(lower, "lower");
        Composition effectiveComposition = composition == Composition.INHERIT
            ? lower.composition : composition;
        List<Ref> effectiveCommands = composition == Composition.INHERIT
            ? lower.commands : commands;
        PanelMode effectivePanelMode = panelMode == PanelMode.INHERIT
            ? lower.panelMode : panelMode;
        List<Ref> effectivePanel = panelMode == PanelMode.INHERIT
            ? lower.panel : panel;

        Map<ActionId, CommandOverride> merged = new LinkedHashMap<>(lower.overrides);
        if (panelMode != PanelMode.INHERIT) {
            merged.replaceAll((id, value) -> value.withPlacement(Placement.INHERIT));
        }
        Map<ActionId, Ref> refs = new LinkedHashMap<>(lower.overrideRefs);
        refs.putAll(overrideRefs);
        overrides.forEach((id, upper) ->
            merged.merge(id, upper, ListCommandLayout::mergeOverride));
        merged.entrySet().removeIf(entry -> entry.getValue().equals(EMPTY));
        refs.keySet().retainAll(merged.keySet());
        return new ListCommandLayout(effectiveComposition, effectiveCommands,
            effectivePanelMode, effectivePanel, merged, refs);
    }

    /**
     * Проверить структурные конфликты: дубли, панель вне явного состава, исключённая
     * команда в явной панели. Проверка не требует реестра действий и не создаёт UI.
     */
    public void validateStructure() {
        Set<ActionId> commandIds = new LinkedHashSet<>();
        for (Ref ref : commands) {
            if (!commandIds.add(ref.id())) {
                throw new IllegalArgumentException("Дубликат команды в составе: " + ref.id());
            }
        }
        Set<ActionId> panelIds = new LinkedHashSet<>();
        for (Ref ref : panel) {
            if (!panelIds.add(ref.id())) {
                throw new IllegalArgumentException("Дубликат команды на панели: " + ref.id());
            }
        }
        if (composition == Composition.EXPLICIT) {
            for (Ref ref : panel) {
                if (!commandIds.contains(ref.id())) {
                    throw new IllegalArgumentException(
                        "Команда панели отсутствует в явном составе: " + ref.id());
                }
            }
        }
        for (Ref ref : panel) {
            if (Boolean.FALSE.equals(override(ref.id()).included())) {
                throw new IllegalArgumentException(
                    "Исключённая команда не может быть в явной панели: " + ref.id());
            }
        }
    }

    /**
     * Проверить структуру и известность REQUIRED-ссылок. OPTIONAL и LOCAL допустимы без
     * известного действия: первые могут отсутствовать, вторые разрешаются позднее.
     */
    public void validate(Predicate<ActionId> knownAction, String context) {
        Objects.requireNonNull(knownAction, "knownAction");
        Objects.requireNonNull(context, "context");
        validateStructure();
        for (Ref ref : commands) {
            validateRef(ref, knownAction, context);
        }
        for (Ref ref : panel) {
            validateRef(ref, knownAction, context);
        }
        overrideRefs.values().forEach(ref -> validateRef(ref, knownAction, context));
    }

    /** Завершающая проверка локальных ссылок по фактически объявленным командам. */
    public void validateLocal(Predicate<ActionId> registered, String context) {
        List<Ref> refs = new ArrayList<>(commands);
        refs.addAll(panel);
        refs.addAll(overrideRefs.values());
        for (Ref ref : refs) {
            if (ref.kind() == RefKind.LOCAL && !registered.test(ref.id())) {
                throw new IllegalArgumentException(context + ": неизвестный LOCAL ключ команды '" + ref.id() + "'");
            }
        }
    }

    private static void validateRef(Ref ref, Predicate<ActionId> knownAction, String context) {
        if (ref.kind() == RefKind.REQUIRED && !knownAction.test(ref.id())) {
            throw new IllegalArgumentException(context + ": неизвестный REQUIRED ключ команды '"
                + ref.id() + "'");
        }
    }

    private static CommandOverride mergeOverride(CommandOverride lower, CommandOverride upper) {
        return new CommandOverride(
            upper.view() == CommandView.INHERIT ? lower.view() : upper.view(),
            upper.placement() == Placement.INHERIT ? lower.placement() : upper.placement(),
            upper.order() == null ? lower.order() : upper.order(),
            upper.included() == null ? lower.included() : upper.included(),
            upper.title() == null ? lower.title() : upper.title());
    }

    private static Builder platformDefaultsBuilder() {
        Builder builder = builder().allCommands().defaultPanel();
        builder.command(CrudAction.CREATE.id()).onPanel().iconAndText();
        builder.command(CrudAction.COPY.id()).onPanel().iconOnly();
        builder.command(CrudAction.EDIT.id()).onPanel().iconAndText();
        builder.command(CrudAction.OPEN.id()).inMore();
        builder.command(CrudAction.DELETE.id()).inMore();
        builder.command(CrudAction.REFRESH.id()).onPanel().iconOnly();
        builder.command(ListUiCommand.VIEWS.id()).onPanel().iconOnly();
        builder.command(Ref.optional(REPORT_PRINT)).onPanel().iconAndText();
        builder.command(ListUiCommand.QUICK_SEARCH.id()).inMore();
        builder.command(ListUiCommand.CONDITIONS.id()).inMore();
        builder.command(ListUiCommand.FORMATTING.id()).inMore();
        builder.command(ListUiCommand.GROUPING.id()).inMore();
        builder.command(CrudAction.COPY_LINK.id()).inMore();
        builder.command(ListUiCommand.DEVELOPMENT.id()).inMore();
        return builder;
    }

    /**
     * Builder одного слоя. Consumer-вход изменяет только свой слой, а не общий объект всех форм.
     */
    public static final class Builder {

        private Composition composition = Composition.INHERIT;
        private List<Ref> commands = List.of();
        private PanelMode panelMode = PanelMode.INHERIT;
        private List<Ref> panel = List.of();
        private final Map<ActionId, CommandOverride> overrides = new LinkedHashMap<>();
        private final Map<ActionId, Ref> overrideRefs = new LinkedHashMap<>();

        public Builder inheritCommands() {
            composition = Composition.INHERIT;
            commands = List.of();
            return this;
        }

        public Builder allCommands() {
            composition = Composition.AUTO;
            commands = List.of();
            return this;
        }

        public Builder commands(ActionId... ids) {
            composition = Composition.EXPLICIT;
            commands = requiredRefs(ids);
            return this;
        }

        public Builder commandRefs(List<Ref> refs) {
            composition = Composition.EXPLICIT;
            commands = copyRefs(refs);
            return this;
        }

        public Builder inheritPanel() {
            panelMode = PanelMode.INHERIT;
            panel = List.of();
            return this;
        }

        public Builder defaultPanel() {
            panelMode = PanelMode.DEFAULT;
            panel = List.of();
            return this;
        }

        public Builder allOnPanel() {
            panelMode = PanelMode.ALL;
            panel = List.of();
            return this;
        }

        public Builder panel(ActionId... ids) {
            panelMode = PanelMode.EXPLICIT;
            panel = requiredRefs(ids);
            return this;
        }

        public Builder panelRefs(List<Ref> refs) {
            panelMode = PanelMode.EXPLICIT;
            panel = copyRefs(refs);
            return this;
        }

        public CommandOverrideBuilder command(ActionId id) {
            Objects.requireNonNull(id, "id");
            return command(Ref.required(id));
        }

        public CommandOverrideBuilder command(Ref ref) {
            Objects.requireNonNull(ref, "ref");
            overrideRefs.put(ref.id(), ref);
            return new CommandOverrideBuilder(this, ref.id());
        }

        public Builder exclude(ActionId... ids) {
            for (ActionId id : Objects.requireNonNull(ids, "ids")) {
                command(Objects.requireNonNull(id, "id")).included(false);
            }
            return this;
        }

        public Builder include(ActionId... ids) {
            for (ActionId id : Objects.requireNonNull(ids, "ids")) {
                command(Objects.requireNonNull(id, "id")).included(true);
            }
            return this;
        }

        public ListCommandLayout build() {
            ListCommandLayout layout = new ListCommandLayout(composition, commands, panelMode,
                panel, overrides, overrideRefs);
            layout.validateStructure();
            return layout;
        }

        private CommandOverride current(ActionId id) {
            return overrides.getOrDefault(id, EMPTY);
        }

        private void put(ActionId id, CommandOverride value) {
            boolean empty = value.view() == CommandView.INHERIT
                && value.placement() == Placement.INHERIT
                && value.order() == null
                && value.included() == null && value.title() == null;
            if (empty) {
                overrides.remove(id);
            } else {
                overrides.put(id, value);
            }
        }

        private static List<Ref> requiredRefs(ActionId... ids) {
            Objects.requireNonNull(ids, "ids");
            List<Ref> refs = new ArrayList<>(ids.length);
            for (ActionId id : ids) {
                refs.add(Ref.required(Objects.requireNonNull(id, "id")));
            }
            return List.copyOf(refs);
        }

        private static List<Ref> copyRefs(List<Ref> refs) {
            Objects.requireNonNull(refs, "refs");
            List<Ref> copy = new ArrayList<>(refs.size());
            for (Ref ref : refs) {
                copy.add(Objects.requireNonNull(ref, "ref"));
            }
            return List.copyOf(copy);
        }
    }

    /** Переопределения одной команды; повторные вызовы дополняют друг друга. */
    public static final class CommandOverrideBuilder {

        private final Builder owner;
        private final ActionId id;

        private CommandOverrideBuilder(Builder owner, ActionId id) {
            this.owner = owner;
            this.id = id;
        }

        public CommandOverrideBuilder iconOnly() {
            owner.put(id, owner.current(id).withView(CommandView.ICON_ONLY));
            return this;
        }

        public CommandOverrideBuilder iconAndText() {
            owner.put(id, owner.current(id).withView(CommandView.ICON_AND_TEXT));
            return this;
        }

        public CommandOverrideBuilder onPanel() {
            owner.put(id, owner.current(id).withPlacement(Placement.PANEL));
            return this;
        }

        public CommandOverrideBuilder inMore() {
            owner.put(id, owner.current(id).withPlacement(Placement.MORE));
            return this;
        }

        public CommandOverrideBuilder order(int order) {
            owner.put(id, owner.current(id).withOrder(order));
            return this;
        }

        public CommandOverrideBuilder included(boolean included) {
            owner.put(id, owner.current(id).withIncluded(included));
            return this;
        }

        public CommandOverrideBuilder title(String title) {
            owner.put(id, owner.current(id).withTitle(title));
            return this;
        }
    }
}
