package org.ipro.form.coordinator;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.icon.VaadinIcon;
import org.ipro.form.CommandBar;
import org.ipro.form.action.*;
import org.ipro.form.builder.*;
import org.ipro.form.link.EntityStructureNavigation;
import org.ipro.form.registry.*;
import org.springframework.context.support.StaticApplicationContext;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.data.selection.SingleSelect;
import com.vaadin.flow.dom.Element;
import org.ipro.crud.BaseEntity;
import org.ipro.filtergrid.FilterGrid;
import org.ipro.form.builtin.ListForm;
import org.ipro.metadata.EntityMetadataInfo;

import java.util.List;
import java.util.Optional;

/** Регрессии замечаний ревью меню от 08.11.2026. */
class CommandMenuRegressionTest {
    static class Doc {}
    public static class Entity extends BaseEntity {}

    @org.junit.jupiter.api.TestFactory
    List<org.junit.jupiter.api.DynamicTest> reviewCases() {
        return List.of(
        org.junit.jupiter.api.DynamicTest.dynamicTest("EXPLICIT panel contains only CREATE", () -> {
            CommandBar bar = basicBar();
            bar.setLayout(ListCommandLayout.builder().panel(CrudAction.CREATE.id()).build()
                .overlayOn(ListCommandLayout.platformDefaults()));
            check(panel(bar).equals(List.of("crud.create")), panel(bar).toString());
        }),
        org.junit.jupiter.api.DynamicTest.dynamicTest("EXPLICIT empty panel is empty", () -> {
            CommandBar bar = basicBar();
            bar.setLayout(ListCommandLayout.builder().panel().build()
                .overlayOn(ListCommandLayout.platformDefaults()));
            check(panel(bar).isEmpty(), panel(bar).toString());
        }),
        org.junit.jupiter.api.DynamicTest.dynamicTest("ALL panel includes DELETE", () -> {
            CommandBar bar = basicBar();
            bar.setLayout(ListCommandLayout.builder().allOnPanel().build()
                .overlayOn(ListCommandLayout.platformDefaults()));
            check(bar.command(CrudAction.DELETE.id()).isOnPanel(), panel(bar).toString());
        }),
        org.junit.jupiter.api.DynamicTest.dynamicTest("switch explicit panel to defaults discards old list", () -> {
            ListCommandLayout lower = ListCommandLayout.builder()
                .commands(CrudAction.CREATE.id()).panel(CrudAction.CREATE.id()).build();
            ListCommandLayout upper = ListCommandLayout.builder()
                .commands(CrudAction.COPY.id()).defaultPanel().build();
            upper.overlayOn(lower).validate(ListCommandLayout::isPlatformKey, "Doc/named");
        }),
        org.junit.jupiter.api.DynamicTest.dynamicTest("REQUIRED override rejects unknown command", () -> {
            ListCommandLayout.Builder builder = ListCommandLayout.builder();
            builder.command(ListCommandLayout.Ref.required(ActionId.of("review.typo"))).onPanel();
            boolean rejected = false;
            try { builder.build().validate(ListCommandLayout::isPlatformKey, "review"); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "unknown REQUIRED override accepted");
        }),
        org.junit.jupiter.api.DynamicTest.dynamicTest("registered application action is valid in resolver", () -> {
            ActionId id = ActionId.of("review.registered-action");
            ActionDefinition def = ActionDefinition.forEntity(id, ActionSurface.LIST_TOOLBAR,
                Doc.class, "Registered action", "EDIT", 100, ActionRequirement.none());
            ActionRegistry actions = new ActionRegistry(List.of(def), List.of());
            check(actions.resolve(ActionSurface.LIST_TOOLBAR, Doc.class, null, id).isPresent(), "fixture setup");
            StaticApplicationContext context = new StaticApplicationContext();
            context.getBeanFactory().registerSingleton("lookup", org.mockito.Mockito.mock(org.ipro.crud.LookupService.class));
            context.getBeanFactory().registerSingleton("actions", actions);
            FormRegistry registry = new FormRegistry();
            registry.registerListCommandLayout(Doc.class, null,
                ListCommandLayout.builder().commands(id).build(), "review");
            new FormResolver(registry, null, null, context, null, null, null)
                .resolveListCommands(Doc.class, null);
        }),
        org.junit.jupiter.api.DynamicTest.dynamicTest("unresolved LOCAL is rejected before first client response", () -> {
            UI ui = new UI();
            UI.setCurrent(ui);
            com.vaadin.flow.server.VaadinSession session = org.mockito.Mockito.mock(
                com.vaadin.flow.server.VaadinSession.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
            org.mockito.Mockito.when(session.getErrorHandler()).thenReturn(null);
            ui.getInternals().setSession(session);
            try {
                ListForm<Entity, Long> form = form();
                form.configureCommands(c -> c.commandRefs(List.of(
                    ListCommandLayout.Ref.local(ActionId.of("review.missing-local")))));
                boolean rejected = false;
                try {
                    ui.add(form);
                    ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
                } catch (IllegalArgumentException expected) {
                    rejected = expected.getMessage().contains("review.missing-local");
                    if (!rejected) throw expected;
                }
                check(rejected, "first response completed without rejecting unresolved LOCAL");
            } finally { UI.setCurrent(null); }
        }),
        org.junit.jupiter.api.DynamicTest.dynamicTest("empty composition hides empty Settings group", () -> {
            CommandBar bar = new CommandBar();
            bar.addMenuCommand(ListUiCommand.GROUPING.id(), "Grouping", () -> {}, CommandBar.Group.SETTINGS);
            bar.setLayout(ListCommandLayout.builder().commands().build()
                .overlayOn(ListCommandLayout.platformDefaults()));
            check(!bar.command(ListUiCommand.GROUPING.id()).menuItem().isVisible(), "fixture setup");
            MenuItem settings = bar.moreMenuBar().getItems().getFirst().getSubMenu().getItems().stream()
                .filter(item -> item.getText().equals("Настройки")).findFirst().orElseThrow();
            check(!settings.isVisible(), "Settings visible, all its commands hidden");
        }),
        org.junit.jupiter.api.DynamicTest.dynamicTest("DEVELOPMENT participates in composition", () -> {
            CommandBar bar = new CommandBar();
            bar.setLayout(ListCommandLayout.builder().exclude(ListUiCommand.DEVELOPMENT.id()).build()
                .overlayOn(ListCommandLayout.platformDefaults()));
            EntityStructureNavigation nav = new EntityStructureNavigation() {
                public Availability availability(Class<?> type) { return Availability.allowed(); }
                public Optional<String> link(Class<?> type) { return Optional.of("/review"); }
                public OpenResult open(Class<?> type, String anchor) { return new OpenResult.Opened(type, Optional.empty()); }
            };
            FormStructureAffordance.attach(bar, Doc.class, nav, false);
            MenuItem service = bar.moreMenuBar().getItems().getFirst().getSubMenu().getItems().stream()
                .filter(item -> item.getText().equals("Сервис")).findFirst().orElseThrow();
            check(service.getSubMenu().getItems().stream().noneMatch(MenuItem::isVisible),
                "Development visible despite exclude(ui.list.development)");
        }),
        org.junit.jupiter.api.DynamicTest.dynamicTest("per-command order applies to full menu", () -> {
            CommandBar bar = basicBar();
            ListCommandLayout.Builder builder = ListCommandLayout.builder();
            builder.command(CrudAction.COPY.id()).order(-100);
            bar.setLayout(builder.build().overlayOn(ListCommandLayout.platformDefaults()));
            List<String> items = bar.moreMenuBar().getItems().getFirst().getSubMenu().getItems().stream()
                .filter(item -> item.getElement().hasAttribute("data-command-id"))
                .map(item -> item.getElement().getAttribute("data-command-id")).toList();
            check(items.getFirst().equals(CrudAction.COPY.id().value()), items.toString());
        }));
    }

    @org.junit.jupiter.api.Test
    void higherPanelModeResetsLowerPlacementButOwnPlacementWins() {
        var lower = ListCommandLayout.builder();
        lower.command(CrudAction.COPY.id()).onPanel();
        var upper = ListCommandLayout.builder().panel();
        upper.command(CrudAction.DELETE.id()).onPanel();
        var bar = basicBar();
        bar.setLayout(upper.build().overlayOn(lower.build().overlayOn(ListCommandLayout.platformDefaults())));
        check(!bar.command(CrudAction.COPY.id()).isOnPanel(), "lower placement leaked");
        check(bar.command(CrudAction.DELETE.id()).isOnPanel(), "upper placement lost");
    }

    @org.junit.jupiter.api.Test
    void autoCompositionDiscardsTheLowerExplicitReferences() {
        var lower = ListCommandLayout.builder().commandRefs(List.of(
            ListCommandLayout.Ref.local(ActionId.of("stale")))).build();
        var result = ListCommandLayout.builder().allCommands().build().overlayOn(lower);
        result.validateLocal(id -> false, "auto");
        check(result.commands().isEmpty(), "stale explicit commands");
    }

    @org.junit.jupiter.api.Test
    void optionalOverridesMayBeAbsentAndLocalOverridesMustResolve() {
        var builder = ListCommandLayout.builder();
        builder.command(ListCommandLayout.Ref.optional(ActionId.of("absent"))).iconOnly();
        builder.build().validate(id -> false, "optional");
        builder.build().validateLocal(id -> false, "optional");
        builder.command(ListCommandLayout.Ref.local(ActionId.of("pending"))).inMore();
        builder.build().validate(id -> false, "local");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.build().validateLocal(id -> false, "local"))
            .hasMessageContaining("pending");
    }

    @org.junit.jupiter.api.Test
    void hiddenLastChildHidesGroupAndShowingItRestoresGroup() {
        var bar = new CommandBar();
        var command = bar.addMenuCommand(ListUiCommand.GROUPING.id(), "Grouping", () -> {}, CommandBar.Group.SETTINGS);
        var settings = bar.moreMenuBar().getItems().getFirst().getSubMenu().getItems().stream()
            .filter(item -> item.getText().equals("Настройки")).findFirst().orElseThrow();
        command.setVisible(false);
        check(!settings.isVisible(), "empty group visible");
        command.setVisible(true);
        check(settings.isVisible(), "nonempty group hidden");
    }

    @org.junit.jupiter.api.Test
    void declaredOrderAndIdAreIndependentOfRegistrationOrder() {
        var bar = new CommandBar();
        bar.register(ActionId.of("z"), "Z", null, () -> {}).setDefinitionOrder(10);
        bar.register(CrudAction.REFRESH.id(), "Refresh", null, () -> {});
        bar.register(ActionId.of("a"), "A", null, () -> {}).setDefinitionOrder(10);
        bar.register(ListUiCommand.VIEWS.id(), "Views", null, () -> {});
        bar.register(ActionId.of("report.print"), "Print", null, () -> {});
        var ids = bar.moreMenuBar().getItems().getFirst().getSubMenu().getItems().stream()
            .filter(item -> item.getElement().hasAttribute("data-command-id"))
            .map(item -> item.getElement().getAttribute("data-command-id")).toList();
        check(ids.equals(List.of("ui.list.views", "report.print", "crud.refresh", "a", "z")), ids.toString());
    }

    @org.junit.jupiter.api.Test
    void localMayRegisterInTheSameResponseAndLaterCyclesAreChecked() {
        UI ui = new UI();
        UI.setCurrent(ui);
        var session = org.mockito.Mockito.mock(com.vaadin.flow.server.VaadinSession.class,
            org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(session.getErrorHandler()).thenReturn(null);
        ui.getInternals().setSession(session);
        try {
            var form = form();
            ui.add(form);
            ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
            var id = ActionId.of("late");
            form.configureCommands(c -> c.command(ListCommandLayout.Ref.local(id)).onPanel());
            form.addAction(new ActionHandler() {
                public ActionDefinition definition() {
                    return ActionDefinition.forEntity(id, ActionSurface.LIST_TOOLBAR, Entity.class,
                        "Late", null, 1, ActionRequirement.none());
                }
                public void execute(ActionInvocation invocation) {}
            });
            ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
            form.configureCommands(c -> c.command(ListCommandLayout.Ref.local(ActionId.of("missing-next"))).inMore());
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ui.getInternals().getStateTree().runExecutionsBeforeClientResponse())
                .hasMessageContaining("missing-next").hasMessageContaining(Entity.class.getName());
        } finally { UI.setCurrent(null); }
    }

    @org.junit.jupiter.api.Test
    void twoInstancesKeepLateCommandsIsolatedAcrossHideDetachAndReattach() {
        UI ui = new UI();
        UI.setCurrent(ui);
        var session = org.mockito.Mockito.mock(com.vaadin.flow.server.VaadinSession.class,
            org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(session.getErrorHandler()).thenReturn(null);
        ui.getInternals().setSession(session);
        try {
            var first = form();
            var second = form();
            ui.add(first, second);
            ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
            var firstBar = CommandBar.forForm(first);
            var secondBar = CommandBar.forForm(second);
            int secondCount = secondBar.commands().size();
            first.setVisible(false);
            ui.remove(first);
            var late = ActionId.of("instance-local");
            first.configureCommands(c -> c.command(ListCommandLayout.Ref.local(late)).onPanel());
            first.addAction(new ActionHandler() {
                public ActionDefinition definition() {
                    return ActionDefinition.forEntity(late, ActionSurface.LIST_TOOLBAR, Entity.class,
                        "Поздняя команда первого экземпляра", null, 1, ActionRequirement.none());
                }
                public void execute(ActionInvocation invocation) {}
            });
            int firstCount = firstBar.commands().size();
            for (int cycle = 0; cycle < 3; cycle++) {
                first.setVisible(true);
                ui.add(first);
                ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();
                check(CommandBar.forForm(first) == firstBar, "renderer replaced on attach");
                check(firstBar.commands().size() == firstCount, "commands duplicated on attach");
                check(firstBar.command(late).menuItem().getText().equals("Поздняя команда первого экземпляра"),
                    "late caption lost on attach");
                check(secondBar.commands().size() == secondCount, "second form commands changed");
                check(secondBar.commands().stream().noneMatch(command -> command.id().equals(late)),
                    "local command leaked into another form instance");
                ui.remove(first);
            }
        } finally { UI.setCurrent(null); }
    }

    @org.junit.jupiter.api.Test
    void registryValidationRespectsSurfaceTypeAndVariant() {
        var id = ActionId.of("scoped");
        var definition = ActionDefinition.forEntity(id, ActionSurface.LIST_TOOLBAR, Doc.class,
            "Scoped", null, 1, ActionRequirement.none()).withVariant("v");
        var context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("actions", new ActionRegistry(List.of(definition), List.of()));
        context.getBeanFactory().registerSingleton("lookup", org.mockito.Mockito.mock(org.ipro.crud.LookupService.class));
        var resolver = new FormResolver(new FormRegistry(), null, null, context, null, null, null);
        var layout = ListCommandLayout.builder().commands(id).build();
        resolver.resolveListCommands(Doc.class, "v", layout);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolveListCommands(Doc.class, null, layout))
            .hasMessageContaining("scoped");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolveListCommands(Entity.class, "v", layout))
            .hasMessageContaining("scoped");
        var card = ActionDefinition.forEntity(ActionId.of("card-only"), ActionSurface.ITEM_MENU, Doc.class,
            "Card", null, 1, ActionRequirement.none());
        context.getBeanFactory().registerSingleton("card-actions", new ActionRegistry(List.of(card), List.of()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolveListCommands(Doc.class, null,
            ListCommandLayout.builder().commands(card.id()).build())).hasMessageContaining("card-only");
    }

    @org.junit.jupiter.api.Test
    void requiredPrintNeedsItsModuleButPlatformDefaultDoesNot() {
        var context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("lookup", org.mockito.Mockito.mock(org.ipro.crud.LookupService.class));
        var resolver = new FormResolver(new FormRegistry(), null, null, context, null, null, null);
        resolver.resolveListCommands(Doc.class, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.resolveListCommands(Doc.class, null,
            ListCommandLayout.builder().commands(ActionId.of("report.print")).build()))
            .hasMessageContaining("report.print");
    }

    private static CommandBar basicBar() {
        CommandBar bar = new CommandBar();
        bar.register(CrudAction.CREATE.id(), "Create", VaadinIcon.PLUS, () -> {});
        bar.register(CrudAction.COPY.id(), "Copy", VaadinIcon.COPY, () -> {});
        bar.register(CrudAction.DELETE.id(), "Delete", VaadinIcon.TRASH, () -> {});
        return bar;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ListForm<Entity, Long> form() {
        EntityMetadataInfo meta = org.mockito.Mockito.mock(EntityMetadataInfo.class);
        org.mockito.Mockito.when(meta.getEntityClass()).thenReturn((Class) Entity.class);
        org.mockito.Mockito.when(meta.getListColumnPaths()).thenReturn(List.of());
        Grid<Entity> grid = org.mockito.Mockito.mock(Grid.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        SingleSelect<Grid<Entity>, Entity> selection = org.mockito.Mockito.mock(SingleSelect.class);
        org.mockito.Mockito.when(grid.asSingleSelect()).thenReturn(selection);
        FilterGrid<Entity> filterGrid = org.mockito.Mockito.mock(FilterGrid.class);
        org.mockito.Mockito.when(filterGrid.getGrid()).thenReturn(grid);
        org.mockito.Mockito.when(filterGrid.getElement()).thenReturn(new Element("div"));
        return new ListForm<>(meta, filterGrid);
    }
    private static List<String> panel(CommandBar bar) {
        return bar.commands().stream().filter(CommandBar.Command::isOnPanel)
            .map(c -> c.id().value()).toList();
    }
    private static void check(boolean condition, String actual) {
        if (!condition) throw new AssertionError(actual);
    }
}

