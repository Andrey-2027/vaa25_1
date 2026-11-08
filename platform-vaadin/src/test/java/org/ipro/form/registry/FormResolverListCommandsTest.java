package org.ipro.form.registry;

import org.ipro.form.action.ActionId;
import org.ipro.form.action.CrudAction;
import org.ipro.form.builder.FormTypeCustomization;
import org.ipro.form.builder.ListCommandLayout;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class FormResolverListCommandsTest {

    static class Doc {
    }

    @Test
    void familyEntityVariantAndInstanceLayersApplyInOrder() {
        FormRegistry registry = new FormRegistry();
        FormResolver resolver = resolver(registry);
        resolver.setTypeCustomizations(List.of(new FormTypeCustomization() {
            @Override
            public boolean supports(Class<?> type) {
                return type == Doc.class;
            }

            @Override
            public void configureListCommands(Class<?> type, ListCommandLayout.Builder commands) {
                commands.command(CrudAction.CREATE.id()).iconOnly();
            }
        }));

        ListCommandLayout.Builder entity = ListCommandLayout.builder()
            .commands(CrudAction.CREATE.id()).panel(CrudAction.CREATE.id());
        entity.command(CrudAction.CREATE.id()).onPanel();
        registry.registerListCommandLayout(Doc.class, null, entity.build(), "entity");

        ListCommandLayout.Builder variant = ListCommandLayout.builder();
        variant.command(CrudAction.CREATE.id()).iconAndText();
        registry.registerListCommandLayout(Doc.class, "v", variant.build(), "variant");

        ListCommandLayout instance = ListCommandLayout.builder().allOnPanel().build();
        ListCommandLayout resolved = resolver.resolveListCommands(Doc.class, "v", instance);

        assertThat(resolved.composition()).isEqualTo(ListCommandLayout.Composition.EXPLICIT);
        assertThat(resolved.commands())
            .extracting(ListCommandLayout.Ref::id)
            .containsExactly(CrudAction.CREATE.id());
        assertThat(resolved.panelMode()).isEqualTo(ListCommandLayout.PanelMode.ALL);
        assertThat(resolved.override(CrudAction.CREATE.id()).view())
            .isEqualTo(ListCommandLayout.CommandView.ICON_AND_TEXT);
        assertThat(resolved.override(CrudAction.CREATE.id()).placement())
            .isEqualTo(ListCommandLayout.Placement.INHERIT);
    }

    @Test
    void entityLayerOverridesFamilyAndInheritsUnsetProperties() {
        FormRegistry registry = new FormRegistry();
        FormResolver resolver = resolver(registry);
        resolver.setTypeCustomizations(List.of(new FormTypeCustomization() {
            @Override
            public boolean supports(Class<?> type) {
                return type == Doc.class;
            }

            @Override
            public void configureListCommands(Class<?> type, ListCommandLayout.Builder commands) {
                commands.command(CrudAction.COPY.id()).iconOnly();
            }
        }));

        ListCommandLayout.Builder entity = ListCommandLayout.builder();
        entity.command(CrudAction.COPY.id()).inMore();
        registry.registerListCommandLayout(Doc.class, null, entity.build(), "entity");

        ListCommandLayout resolved = resolver.resolveListCommands(Doc.class, null);

        assertThat(resolved.override(CrudAction.COPY.id()).view())
            .isEqualTo(ListCommandLayout.CommandView.ICON_ONLY);
        assertThat(resolved.override(CrudAction.COPY.id()).placement())
            .isEqualTo(ListCommandLayout.Placement.MORE);
    }

    @Test
    void unknownRequiredKeyFailsAtResolution() {
        FormRegistry registry = new FormRegistry();
        FormResolver resolver = resolver(registry);
        registry.registerListCommandLayout(Doc.class, null,
            ListCommandLayout.builder().commands(ActionId.of("fixture.unknown")).build(), "bad");

        assertThatThrownBy(() -> resolver.resolveListCommands(Doc.class, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fixture.unknown");
    }

    private static FormResolver resolver(FormRegistry registry) {
        return new FormResolver(registry, null, null, mock(ApplicationContext.class),
            null, null, null);
    }
}
