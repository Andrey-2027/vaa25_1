package org.ipro.form.coordinator;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.ipro.form.link.EntityStructureNavigation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FormStructureAffordanceTest {
    static class Type {}
    private final HorizontalLayout toolbar = new HorizontalLayout();
    private final EntityStructureNavigation navigation = mock(EntityStructureNavigation.class);
    private final List<String> refused = new ArrayList<>();
    private final List<String> browser = new ArrayList<>();
    private final List<String> copied = new ArrayList<>();

    private MenuBar attach(boolean dialog) {
        return FormStructureAffordance.attach(toolbar, Type.class, navigation, dialog,
            refused::add, browser::add, copied::add);
    }

    private void allowed(boolean published) {
        when(navigation.availability(Type.class)).thenReturn(EntityStructureNavigation.Availability.allowed());
        when(navigation.link(Type.class)).thenReturn(published ? Optional.of("/entity-explorer/types") : Optional.empty());
        when(navigation.open(Type.class, null)).thenReturn(new EntityStructureNavigation.OpenResult.Opened(Type.class, Optional.empty()));
    }

    @Test void absentSpiAndDeniedAccessDoNotShowDevelopmentMenus() {
        assertThat(FormStructureAffordance.attach(toolbar, Type.class, null, false)).isNull();
        when(navigation.availability(Type.class)).thenReturn(EntityStructureNavigation.Availability.unavailable("Адрес не найден"));
        assertThat(attach(false)).isNull();
        assertThat(toolbar.getChildren()).isEmpty();
        verify(navigation).availability(Type.class);
        verifyNoMoreInteractions(navigation);
    }

    @Test void repeatedAttachmentLeavesOneMenuAndOneOpeningAction() {
        allowed(true);
        attach(false);
        MenuBar current = attach(false);
        assertThat(toolbar.getChildren()).containsExactly(current);
        click(items(current).get(0));
        verify(navigation).open(Type.class, null);
        assertThat(browser).isEmpty();
    }

    @Test void aWorkspaceTypeWithoutAKeyStillOpensButHasNoCopyItem() {
        allowed(false);
        MenuBar menu = attach(false);
        assertThat(items(menu)).hasSize(1);
        click(items(menu).get(0));
        verify(navigation).open(Type.class, null);
    }

    @Test void aDialogOpensThePublishedAddressInAnotherBrowserTab() {
        allowed(true);
        MenuBar menu = attach(true);
        click(items(menu).get(0));
        assertThat(browser).containsExactly("/entity-explorer/types");
        verify(navigation, never()).open(any(), any());
    }

    @Test void aDialogWithoutAPublishedTypeNamesWhyOpeningIsDisabled() {
        allowed(false);
        MenuBar menu = attach(true);
        assertThat(items(menu)).hasSize(1);
        assertThat(items(menu).get(0).isEnabled()).isFalse();
        assertThat(items(menu).get(0).getText()).contains("тип не опубликован");
        verify(navigation, never()).open(any(), any());
    }

    @Test void copyingUsesOnlyTheTypeAddressWithoutRecordIdOrVariant() {
        allowed(true);
        MenuBar menu = attach(false);
        click(items(menu).get(1));
        assertThat(copied).containsExactly("/entity-explorer/types");
        verify(navigation, never()).open(any(), any());
    }

    @Test void aRoleChangeBetweenShowingAndClickingBlocksOpenAndCopy() {
        allowed(true);
        MenuBar menu = attach(true);
        when(navigation.availability(Type.class)).thenReturn(EntityStructureNavigation.Availability.unavailable("Адрес не найден"));
        click(items(menu).get(0));
        click(items(menu).get(1));
        assertThat(refused).containsExactly("Адрес не найден", "Адрес не найден");
        assertThat(browser).isEmpty();
        assertThat(copied).isEmpty();
        verify(navigation, never()).open(any(), any());
        assertThat(attach(false)).isNull();
        assertThat(toolbar.getChildren()).isEmpty();
    }

    @Test void aHostFailureIsShownInsteadOfReportingSuccess() {
        allowed(true);
        when(navigation.open(Type.class, null)).thenReturn(new EntityStructureNavigation.OpenResult.Unavailable("Нет рабочей области"));
        click(items(attach(false)).get(0));
        assertThat(refused).containsExactly("Нет рабочей области");
    }

    private static List<MenuItem> items(MenuBar menu) {
        return menu.getItems().get(0).getSubMenu().getItems();
    }

    private static void click(MenuItem item) {
        ComponentUtil.fireEvent(item, new ClickEvent<>(item, true, 0, 0, 0, 0, 1, 0, false, false, false, false));
    }
}
