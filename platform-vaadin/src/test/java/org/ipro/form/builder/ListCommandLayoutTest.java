package org.ipro.form.builder;

import org.ipro.form.action.ActionId;
import org.ipro.form.action.CrudAction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListCommandLayoutTest {

    @Test void titlesInheritAcrossLayersAndOldOverrideConstructorRemainsCompatible() {
        var lower=ListCommandLayout.builder(); lower.command(CrudAction.CREATE.id()).title("Создать накладную");
        var upper=ListCommandLayout.builder(); upper.command(CrudAction.CREATE.id()).inMore();
        var merged=upper.build().overlayOn(lower.build());
        assertThat(merged.override(CrudAction.CREATE.id()).title()).isEqualTo("Создать накладную");
        var renamed=ListCommandLayout.builder(); renamed.command(CrudAction.CREATE.id()).title("Добавить");
        assertThat(renamed.build().overlayOn(merged).override(CrudAction.CREATE.id()).title()).isEqualTo("Добавить");
        assertThat(new ListCommandLayout.CommandOverride(null,null,null,null).title()).isNull();
        assertThatThrownBy(()->ListCommandLayout.builder().command(CrudAction.CREATE.id()).title(" "))
            .hasMessageContaining("Blank command title");
    }

    @Test
    void explicitEmptyAndInheritRemainDistinct() {
        assertThat(ListCommandLayout.builder().build().composition())
            .isEqualTo(ListCommandLayout.Composition.INHERIT);

        ListCommandLayout empty = ListCommandLayout.builder().commands().build();

        assertThat(empty.composition()).isEqualTo(ListCommandLayout.Composition.EXPLICIT);
        assertThat(empty.commands()).isEmpty();
    }

    @Test
    void panelModeIsIndependentFromComposition() {
        ListCommandLayout layout = ListCommandLayout.builder().allCommands().panel().build();

        assertThat(layout.composition()).isEqualTo(ListCommandLayout.Composition.AUTO);
        assertThat(layout.panelMode()).isEqualTo(ListCommandLayout.PanelMode.EXPLICIT);
        assertThat(layout.panel()).isEmpty();
    }

    @Test
    void overlayChangesOnlyExplicitProperties() {
        ListCommandLayout.Builder lowerBuilder = ListCommandLayout.builder()
            .allCommands().defaultPanel();
        lowerBuilder.command(CrudAction.CREATE.id()).iconOnly();
        ListCommandLayout lower = lowerBuilder.build();

        ListCommandLayout.Builder upperBuilder = ListCommandLayout.builder();
        upperBuilder.command(CrudAction.CREATE.id()).onPanel();
        ListCommandLayout merged = upperBuilder.build().overlayOn(lower);

        assertThat(merged.composition()).isEqualTo(ListCommandLayout.Composition.AUTO);
        assertThat(merged.panelMode()).isEqualTo(ListCommandLayout.PanelMode.DEFAULT);
        assertThat(merged.override(CrudAction.CREATE.id()).view())
            .isEqualTo(ListCommandLayout.CommandView.ICON_ONLY);
        assertThat(merged.override(CrudAction.CREATE.id()).placement())
            .isEqualTo(ListCommandLayout.Placement.PANEL);

        ListCommandLayout all = ListCommandLayout.builder().allOnPanel().build();
        assertThat(all.overlayOn(merged).panelMode())
            .isEqualTo(ListCommandLayout.PanelMode.ALL);

        ListCommandLayout empty = ListCommandLayout.builder().commands().build();
        ListCommandLayout explicit = empty.overlayOn(merged);
        assertThat(explicit.composition()).isEqualTo(ListCommandLayout.Composition.EXPLICIT);
        assertThat(explicit.commands()).isEmpty();
    }

    @Test
    void includeAndExcludeDoNotFreezeAutoComposition() {
        ListCommandLayout.Builder builder = ListCommandLayout.builder().allCommands();
        builder.command(CrudAction.DELETE.id()).included(false);
        ListCommandLayout excluded = builder.build();

        assertThat(excluded.composition()).isEqualTo(ListCommandLayout.Composition.AUTO);
        assertThat(excluded.override(CrudAction.DELETE.id()).included()).isFalse();

        ListCommandLayout.Builder reinclude = ListCommandLayout.builder();
        reinclude.command(CrudAction.DELETE.id()).included(true);
        assertThat(reinclude.build().overlayOn(excluded)
            .override(CrudAction.DELETE.id()).included()).isTrue();
    }

    @Test
    void requiredUnknownIsRejectedButOptionalAndLocalArePending() {
        ListCommandLayout unknown = ListCommandLayout.builder()
            .commands(ActionId.of("fixture.unknown")).build();

        assertThatThrownBy(() -> unknown.validate(ListCommandLayout::isPlatformKey, "test"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fixture.unknown");

        ListCommandLayout pending = ListCommandLayout.builder()
            .commandRefs(List.of(
                ListCommandLayout.Ref.optional(ActionId.of("report.print")),
                ListCommandLayout.Ref.local(ActionId.of("fixture.local"))))
            .build();

        assertThatCode(() -> pending.validate(ListCommandLayout::isPlatformKey, "test"))
            .doesNotThrowAnyException();
    }

    @Test
    void duplicatesAndPanelOutsideExplicitCompositionFail() {
        assertThatThrownBy(() -> ListCommandLayout.builder()
            .commands(CrudAction.CREATE.id(), CrudAction.CREATE.id()).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Дубликат");

        ListCommandLayout.Builder outside = ListCommandLayout.builder()
            .commands(CrudAction.CREATE.id());
        outside.panel(CrudAction.COPY.id());
        assertThatThrownBy(outside::build)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("отсутствует в явном составе");

        ListCommandLayout.Builder excluded = ListCommandLayout.builder().allCommands();
        excluded.panel(CrudAction.DELETE.id());
        excluded.command(CrudAction.DELETE.id()).included(false);
        assertThatThrownBy(excluded::build)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Исключённая команда");
    }

    @Test
    void nullConfigurationIsRejected() {
        assertThatThrownBy(() -> ListCommandLayout.builder().commands((ActionId[]) null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ListCommandLayout.builder().command((ActionId) null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ListCommandLayout.builder()
            .commandRefs((List<ListCommandLayout.Ref>) null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void platformDefaultsDescribeTheApprovedPanelAndMorePlacement() {
        ListCommandLayout defaults = ListCommandLayout.platformDefaults();

        assertThat(defaults.composition()).isEqualTo(ListCommandLayout.Composition.AUTO);
        assertThat(defaults.override(CrudAction.CREATE.id()).placement())
            .isEqualTo(ListCommandLayout.Placement.PANEL);
        assertThat(defaults.override(CrudAction.CREATE.id()).view())
            .isEqualTo(ListCommandLayout.CommandView.ICON_AND_TEXT);
        assertThat(defaults.override(CrudAction.COPY.id()).view())
            .isEqualTo(ListCommandLayout.CommandView.ICON_ONLY);
        assertThat(defaults.override(CrudAction.OPEN.id()).placement())
            .isEqualTo(ListCommandLayout.Placement.MORE);
        assertThat(defaults.override(CrudAction.DELETE.id()).placement())
            .isEqualTo(ListCommandLayout.Placement.MORE);
        assertThat(defaults.override(ListUiCommand.VIEWS.id()).placement())
            .isEqualTo(ListCommandLayout.Placement.PANEL);
        assertThat(defaults.override(ListUiCommand.QUICK_SEARCH.id()).placement())
            .isEqualTo(ListCommandLayout.Placement.MORE);
        assertThat(defaults.override(ActionId.of("report.print")).placement())
            .isEqualTo(ListCommandLayout.Placement.PANEL);
    }
}
