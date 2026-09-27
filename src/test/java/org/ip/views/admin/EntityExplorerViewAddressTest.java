package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import org.ip.model.Nomenclature;
import org.ip.model.ReceivingDocument;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntityExplorerViewAddressTest {

    @Test
    void aRouteEntrySelectsItsTypeDespiteThePreviousSearchFilter() {
        EntityExplorerView view = newView();
        view.init(Nomenclature.class);
        TextField search = component(view, TextField.class);
        search.setValue("Nomenclature");

        view.init(ReceivingDocument.class);

        assertThat(search.getValue()).isEmpty();
        assertThat(selectedType(view)).isEqualTo(ReceivingDocument.class);
    }

    @Test
    void internalTabSwitchesNotifyTheHostAndMenuEntryClearsTheCards() {
        EntityExplorerView view = newView();
        List<Class<?>> selections = new ArrayList<>();
        view.setTypeSelectionListener(selections::add);
        view.init(Nomenclature.class);

        selectTreeRoot(view, 1);
        assertThat(selections).containsExactly(ReceivingDocument.class);

        TabSheet sheet = component(view, TabSheet.class);
        sheet.setSelectedTab(openTabs(view).get(Nomenclature.class));
        assertThat(selections).containsExactly(ReceivingDocument.class, Nomenclature.class);

        ReflectionTestUtils.invokeMethod(view, "closeTab", Nomenclature.class);
        assertThat(selections)
            .as("закрытие активной карточки выбирает соседнюю и сообщает её тип один раз")
            .containsExactly(ReceivingDocument.class, Nomenclature.class, ReceivingDocument.class);

        view.init();
        assertThat(openTabs(view)).isEmpty();
        assertThat(sheet.getSelectedTab()).isNull();
    }

    @Test
    void repeatedRouteEntriesDoNotAccumulateSelectionListeners() {
        EntityExplorerView view = newView();
        List<Class<?>> selections = new ArrayList<>();
        view.setTypeSelectionListener(selections::add);
        view.init(Nomenclature.class);
        view.init(Nomenclature.class);
        view.init(Nomenclature.class);

        selectTreeRoot(view, 1);

        assertThat(selections).containsExactly(ReceivingDocument.class);
    }

    private static EntityExplorerView newView() {
        EntitySummaryAssembler assembler = mock(EntitySummaryAssembler.class);
        when(assembler.entities()).thenReturn(List.of(
            new EntitySummaryAssembler.EntityRef(Nomenclature.class, "Nomenclature",
                ResolvedValue.code("Nomenclature")),
            new EntitySummaryAssembler.EntityRef(ReceivingDocument.class, "ReceivingDocument",
                ResolvedValue.code("ReceivingDocument"))));
        when(assembler.tableSectionRowNames(Nomenclature.class)).thenReturn(List.of());
        when(assembler.tableSectionRowNames(ReceivingDocument.class)).thenReturn(List.of());
        when(assembler.summarize(Nomenclature.class)).thenReturn(summary(Nomenclature.class));
        when(assembler.summarize(ReceivingDocument.class)).thenReturn(summary(ReceivingDocument.class));

        EntityExplorerAccess access = mock(EntityExplorerAccess.class);
        when(access.allows()).thenReturn(true);
        return new EntityExplorerView(assembler, mock(FormNavigator.class), access,
            mock(FormRouteCatalog.class));
    }

    private static EntitySummary summary(Class<?> type) {
        return new EntitySummary(type, type.getSimpleName(), ResolvedValue.code(type.getSimpleName()),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void selectTreeRoot(EntityExplorerView view, int index) {
        TreeGrid tree = component(view, TreeGrid.class);
        TreeDataProvider provider = (TreeDataProvider) tree.getDataProvider();
        tree.select(provider.getTreeData().getRootItems().get(index));
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, Tab> openTabs(EntityExplorerView view) {
        return (Map<Class<?>, Tab>) ReflectionTestUtils.getField(view, "openTabs");
    }

    private static Class<?> selectedType(EntityExplorerView view) {
        Tab selected = component(view, TabSheet.class).getSelectedTab();
        return openTabs(view).entrySet().stream()
            .filter(entry -> entry.getValue() == selected)
            .map(Map.Entry::getKey)
            .findFirst().orElse(null);
    }

    private static <T extends Component> T component(Component root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        return root.getChildren()
            .map(child -> component(child, type))
            .filter(value -> value != null)
            .findFirst().orElse(null);
    }
}
