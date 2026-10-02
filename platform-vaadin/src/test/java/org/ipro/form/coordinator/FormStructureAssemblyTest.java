package org.ipro.form.coordinator;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.page.Page;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.crud.*;
import org.ipro.form.*;
import org.ipro.form.action.*;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.link.EntityStructureNavigation;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.spi.*;
import org.ipro.metadata.*;
import org.ipro.rls.RlsUiGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FormStructureAssemblyTest {
    public static class Entity extends BaseEntity { String title = ""; }
    static class CustomList extends ListForm<Entity, Long> {
        CustomList(EntityMetadataInfo metadata, BaseService<Entity, Long> service) { super(metadata, service); }
    }
    private final FormResolver resolver = mock(FormResolver.class);
    private final EntityStructureNavigation navigation = mock(EntityStructureNavigation.class);
    private final FormSaveHandler<?> saves = mock(FormSaveHandler.class);
    private final EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);

    @AfterEach void clearUi() { UI.setCurrent(null); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private FormCoordinator coordinator() {
        doReturn(Entity.class).when(metadata).getEntityClass();
        when(metadata.getFormFields()).thenReturn(List.of());
        when(metadata.getGridFields()).thenReturn(List.of());
        when(metadata.getItemFormTitle()).thenReturn("Сущность");
        var metadataResolver = mock(MetadataResolver.class);
        when(metadataResolver.resolve(Entity.class)).thenReturn(metadata);
        when(resolver.getFormRegistry()).thenReturn(new FormRegistry());
        var context = mock(ApplicationContext.class);
        when(context.getBean(FormSaveHandler.class)).thenReturn(saves);
        when(context.getBean(LookupService.class)).thenReturn(mock(LookupService.class));
        when(navigation.availability(Entity.class)).thenReturn(EntityStructureNavigation.Availability.allowed());
        when(navigation.link(Entity.class)).thenReturn(Optional.of("/entity-explorer/entities"));
        when(navigation.open(Entity.class, null)).thenReturn(
            new EntityStructureNavigation.OpenResult.Opened(Entity.class, Optional.empty()));
        var coordinator = new FormCoordinator(metadataResolver, mock(FieldFactory.class), context, resolver,
            mock(ServiceLocator.class), mock(FormSettingsStore.class), mock(GridViewStore.class), mock(RlsUiGate.class),
            mock(ItemFormAccessBinder.class), mock(ActionRegistry.class), mock(ActionContextProvider.class),
            mock(ActionHandlerRegistry.class), mock(FormLinkService.class), mock(EntityCopyService.class),
            mock(TableSectionFactory.class), mock(ObjectProvider.class), null);
        coordinator.setEntityStructureNavigation(navigation);
        return coordinator;
    }

    @Test void embeddedDefaultAndCustomListsReceiveOneMenuEvenAfterReconfiguration() {
        var coordinator = coordinator();
        @SuppressWarnings("unchecked") BaseService<Entity, Long> service = mock(BaseService.class);
        ListForm<Entity, Long> generic = new ListForm<>(metadata, service);
        ListForm<Entity, Long> custom = new CustomList(metadata, service);
        doReturn(generic).when(resolver).resolveListForm(Entity.class, null, null);
        doReturn(custom).when(resolver).resolveListForm(Entity.class, "custom", null);

        assertThat(coordinator.createListForm(Entity.class)).isSameAs(generic);
        assertThat(coordinator.createListForm(Entity.class, "custom", null)).isSameAs(custom);
        assertThat(coordinator.createListForm(Entity.class, "custom", null)).isSameAs(custom);
        var menus = custom.getToolbar().getChildren().filter(component ->
            component.getId().filter("entity-structure-menu"::equals).isPresent()).toList();
        assertThat(menus).hasSize(1);
        click(((MenuBar) menus.get(0)).getItems().get(0).getSubMenu().getItems().get(0));
        verify(navigation).open(Entity.class, null);
    }

    @Test void theActualDialogPathKeepsTheUnsavedFormAndOpensOnlyABrowserAddress() {
        var coordinator = coordinator();
        var form = new ItemForm<>(Entity.class, List.of(), mock(FieldFactory.class));
        var title = new TextField();
        var field = mock(FieldMetadataInfo.class);
        when(field.getName()).thenReturn("title");
        form.getBindingRegistry().add(new FormBinding(field, title, entity -> ((Entity) entity).title,
            (entity, value) -> ((Entity) entity).title = (String) value, title::getValue,
            value -> title.setValue((String) value), value -> value == null, title::setReadOnly));
        doReturn(form).when(resolver).resolveItemForm(Entity.class, "custom", null, null);
        UI ui = mock(UI.class);
        Page page = mock(Page.class);
        when(ui.getPage()).thenReturn(page);
        UI.setCurrent(ui);
        try (var dialogs = mockConstruction(Dialog.class)) {
            coordinator.openItemForm(Entity.class, "custom", (Long) null, saved -> {});
            Entity draft = form.peekEntity();
            title.setValue("Несохранённый черновик");
            MenuBar menu = form.getFooter().getChildren().filter(MenuBar.class::isInstance)
                .map(MenuBar.class::cast).findFirst().orElseThrow();
            click(menu.getItems().get(0).getSubMenu().getItems().get(0));

            verify(page).executeJs("window.open($0, '_blank', 'noopener');", "/entity-explorer/entities");
            verify(navigation, never()).open(any(), any());
            verify(dialogs.constructed().get(0), never()).close();
            assertThat(form.peekEntity()).isSameAs(draft);
            assertThat(draft.getId()).isNull();
            assertThat(title.getValue()).isEqualTo("Несохранённый черновик");
            assertThat(form.isDirty()).isTrue();
            verifyNoInteractions(saves);
        }
    }

    private static void click(MenuItem item) {
        ComponentUtil.fireEvent(item, new ClickEvent<>(item, true, 0, 0, 0, 0, 1, 0,
            false, false, false, false));
    }
}
