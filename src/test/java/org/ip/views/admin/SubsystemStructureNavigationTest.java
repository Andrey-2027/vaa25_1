package org.ip.views.admin;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.form.link.EntityStructureNavigation;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.vaadin.explorer.SubsystemSummaryAssembler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SubsystemStructureNavigationTest {
    static class Subsystem {}
    static class Entity {}

    private final SubsystemSummaryAssembler assembler = mock(SubsystemSummaryAssembler.class);
    private final EntityExplorerAccess access = mock(EntityExplorerAccess.class);
    private final EntityStructureNavigation navigation = mock(EntityStructureNavigation.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<EntityStructureNavigation> provider = mock(ObjectProvider.class);
    private final SubsystemStructureView view = new SubsystemStructureView(assembler, provider,
        mock(FormNavigator.class), access);
    private final SubsystemSummaryAssembler.EntityFacet facet = new SubsystemSummaryAssembler.EntityFacet(
        Entity.class, "Entity", ResolvedValue.code("Сущность"), "", false, false, false);

    @AfterEach void clearUi() { UI.setCurrent(null); }

    private Button init() {
        when(access.allows()).thenReturn(true);
        when(provider.getIfAvailable()).thenReturn(navigation);
        when(assembler.catalog()).thenReturn(new SubsystemSummaryAssembler.Catalog(List.of(
            new SubsystemSummaryAssembler.SubsystemRef(Subsystem.class, "Справочники", "Справочники", 0, 1)), List.of()));
        when(assembler.groupsOf(Subsystem.class)).thenReturn(List.of(
            new SubsystemSummaryAssembler.Group(Subsystem.class, "Справочники", "Справочники", List.of(facet))));
        view.init();
        return ReflectionTestUtils.invokeMethod(view, "structureAction", facet);
    }

    @Test void repeatedOpeningKeepsTheSubsystemComponentSearchAndSelection() {
        Button button = init();
        TextField search = (TextField) ReflectionTestUtils.getField(view, "searchField");
        search.setValue("Справ");
        Object selection = ReflectionTestUtils.getField(view, "selectedItem");
        Object detail = ReflectionTestUtils.getField(view, "detail");
        when(navigation.open(Entity.class, null)).thenReturn(
            new EntityStructureNavigation.OpenResult.Opened(Entity.class, Optional.empty()));
        clearInvocations(assembler);

        button.click();
        button.click();

        verify(navigation, times(2)).open(Entity.class, null);
        verifyNoInteractions(assembler);
        assertThat(search.getValue()).isEqualTo("Справ");
        assertThat(ReflectionTestUtils.getField(view, "selectedItem")).isSameAs(selection);
        assertThat(ReflectionTestUtils.getField(view, "detail")).isSameAs(detail);
    }

    @Test void roleRevocationBeforeClickDoesNotEvenResolveTheNavigationSpi() {
        Button button = init();
        when(access.allows()).thenReturn(false);
        clearInvocations(provider);
        List<String> messages = new ArrayList<>();
        UI.setCurrent(new UI());
        try (var ignored = mockConstruction(Notification.class, (mock, context) ->
            messages.add((String) context.arguments().get(0)))) {
            button.click();
        }
        verifyNoInteractions(provider, navigation);
        assertThat(messages).containsExactly(EntityExplorerAccess.REFUSAL_TEXT);
    }

    @Test void absentSpiAndExplicitRefusalAreShownWithoutASecondDiagnosticDialog() {
        Button button = init();
        List<String> messages = new ArrayList<>();
        UI.setCurrent(new UI());
        try (var ignored = mockConstruction(Notification.class, (mock, context) ->
            messages.add((String) context.arguments().get(0)))) {
            when(provider.getIfAvailable()).thenReturn(null);
            button.click();
            when(provider.getIfAvailable()).thenReturn(navigation);
            when(navigation.open(Entity.class, null)).thenReturn(
                new EntityStructureNavigation.OpenResult.Unavailable("Неоднозначный владелец"));
            button.click();
        }
        assertThat(messages).containsExactly("Переход в Entity Explorer не подключён", "Неоднозначный владелец");
    }
}
