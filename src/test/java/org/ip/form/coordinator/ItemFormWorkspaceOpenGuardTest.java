package org.ip.form.coordinator;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.notification.Notification;
import org.ip.model.Workshop;
import org.ipro.crud.BaseService;
import org.ipro.crud.EntityCopyService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.FieldFactory;
import org.ipro.form.TableSectionFactory;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.form.coordinator.FormOpenMode;
import org.ipro.form.coordinator.ItemFormAccessBinder;
import org.ipro.form.coordinator.ItemFormWrapperView;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridViewStore;
import org.ipro.form.spi.WorkspaceGateway;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.RlsUiGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.TableSectionService;
import org.ipro.form.FormSaveHandler;
import org.ipro.form.FormSaveResult;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ItemTable;
import org.ipro.form.link.EntityStructureNavigation;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ip.views.workspace.Workspace;
import org.ip.views.workspace.WorkspaceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * E2.0a: открытие существующей записи во вкладке Workspace обязано быть fail-closed.
 *
 * <p>Раньше при {@code findById(id) == empty} wrapper всё равно добавлял форму, а
 * {@code ItemForm.getEntity()} лениво создавал новый объект — то есть недоступная (в том числе
 * скрытая row-level RLS) или удалённая между чтениями запись могла превратиться в карточку
 * <b>создания</b>. Это не косметика: при разрешённом {@code CREATE} серверный write-guard
 * отклонять было бы нечего, потому что создаётся действительно новый объект.</p>
 *
 * <p>Здесь проверяются две линии защиты: preflight в координаторе (вкладка вообще не открывается)
 * и fail-closed в самом wrapper'е (даже если бы запись исчезла между preflight и инициализацией,
 * форма не собирается и не добавляется).</p>
 */
class ItemFormWorkspaceOpenGuardTest {

    private static final long MISSING_ID = 42L;

    private final List<String> notifications = new ArrayList<>();

    private UI ui;
    private MockedConstruction<Notification> notificationConstruction;

    private MetadataResolver metadataResolver;
    private FormResolver formResolver;
    private ServiceLocator serviceLocator;
    private WorkspaceGateway gateway;
    private ItemFormWrapperView wrapper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ui = new UI();
        UI.setCurrent(ui);
        notificationConstruction = mockConstruction(Notification.class, (mock, context) -> {
            if (!context.arguments().isEmpty() && context.arguments().get(0) instanceof String text) {
                notifications.add(text);
            }
        });

        metadataResolver = mock(MetadataResolver.class);
        EntityMetadataInfo meta = mock(EntityMetadataInfo.class);
        when(meta.getItemFormTitle()).thenReturn("Цех");
        when(metadataResolver.resolve(Workshop.class)).thenReturn(meta);

        formResolver = mock(FormResolver.class);
        serviceLocator = mock(ServiceLocator.class);
        gateway = mock(WorkspaceGateway.class);

        wrapper = new ItemFormWrapperView(
            mock(ApplicationContext.class),
            formResolver,
            serviceLocator,
            mock(ItemFormAccessBinder.class),
            mock(ActionRegistry.class),
            mock(ActionContextProvider.class),
            mock(ActionHandlerRegistry.class),
            mock(FormLinkService.class));
    }

    @AfterEach
    void tearDown() {
        try {
            notificationConstruction.close();
        } catch (Exception ignored) {
            // закрытие перехвата не влияет на результат теста
        }
        UI.setCurrent(null);
        notifications.clear();
    }

    @Test
    void missingRecordDoesNotOpenWorkspaceTabAndReportsReason() {
        FormCoordinator coordinator = coordinator(gateway);
        stubService(Workshop.class, MISSING_ID, Optional.empty());

        coordinator.openItemForm(Workshop.class, MISSING_ID, saved -> {
        });

        verify(gateway, never()).open(any(), anyString(), anyString(), any());
        assertThat(notifications)
            .as("отсутствие записи обязано быть названной причиной, а не пустой вкладкой")
            .anySatisfy(message -> assertThat(message).contains("не найдена"));
    }

    @Test
    void existingRecordStillOpensWorkspaceTab() {
        FormCoordinator coordinator = coordinator(gateway);
        stubService(Workshop.class, MISSING_ID, Optional.of(mock(Workshop.class)));

        coordinator.openItemForm(Workshop.class, MISSING_ID, saved -> {
        });

        verify(gateway).open(eq(ItemFormWrapperView.class), eq("item-workshop-42"), anyString(), any());
        assertThat(notifications)
            .as("успешное открытие существующей записи не сообщает об ошибке")
            .isEmpty();
    }

    @Test
    void wrapperRefusesToBuildFormWhenRecordIsGoneBetweenPreflightAndInit() {
        stubService(Workshop.class, MISSING_ID, Optional.empty());

        assertThatThrownBy(() -> wrapper.init(Workshop.class, null, MISSING_ID, saved -> {
        }, () -> {
        }))
            .isInstanceOf(ItemFormWrapperView.RecordUnavailableException.class);

        assertThat(wrapper.getItemForm())
            .as("форма не собрана — сохранять нечего и некого")
            .isNull();
        assertThat(wrapper.getComponentCount())
            .as("в представление не добавлен даже пустой контейнер формы")
            .isZero();
        verify(formResolver, never()).resolveItemForm(any(), any(), any(), any());
    }

    private FormCoordinator coordinator(WorkspaceGateway workspaceGateway) {
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkspaceGateway> gateways = mock(ObjectProvider.class);
        when(gateways.getIfAvailable()).thenReturn(workspaceGateway);

        FormCoordinator coordinator = new FormCoordinator(
            metadataResolver,
            mock(FieldFactory.class),
            mock(ApplicationContext.class),
            formResolver,
            serviceLocator,
            mock(FormSettingsStore.class),
            mock(GridViewStore.class),
            mock(RlsUiGate.class),
            mock(ItemFormAccessBinder.class),
            mock(ActionRegistry.class),
            mock(ActionContextProvider.class),
            mock(ActionHandlerRegistry.class),
            mock(FormLinkService.class),
            mock(EntityCopyService.class),
            mock(TableSectionFactory.class),
            gateways,
            // Мост адреса здесь не нужен: тест про preflight существования записи.
            null);
        coordinator.setItemFormOpenMode(FormOpenMode.WORKSPACE_TAB);
        return coordinator;
    }

    /**
     * Стаб сервиса для пары {@code (type, id)}.
     *
     * <p>Тип параметров остаётся переменными ({@code T, ID}, а не {@code Workshop, Long}), потому
     * что при {@code ID = Long} вызов {@code service.findById(id)} становится неоднозначным: границы
     * {@code BaseService.findById(ID)} и {@code CrudService.findById(Long)} совпадают. В
     * продакшене вызов идёт с переменной типа, поэтому там выбора нет; в тесте его тоже не должно
     * быть искусственно.</p>
     */
    @SuppressWarnings("unchecked")
    private <T extends IdentifiableEntity, ID> void stubService(
            Class<T> entityClass, ID id, Optional<T> result) {
        BaseService<T, ID> service = mock(BaseService.class);
        when(service.findById(id)).thenReturn(result);
        doReturn(service).when(serviceLocator).findService(entityClass);
    }

    // E3.2.2 §10.2: переход к структуре сохраняет экземпляр формы, owned-строки и dirty-guard.
    public static class Document extends BaseEntity { String title = ""; }
    public static class Row extends BaseEntity { int quantity; }

    @AfterEach void clearUi() { UI.setCurrent(null); }

    private static final class Fixture {
        final Workspace workspace = new Workspace(mock(WorkspaceManager.class));
        final ItemForm<Document> form = new ItemForm<>(Document.class, List.of(), mock(FieldFactory.class));
        final TextField title;
        final ItemTable<Row, Document> table;
        final EntityStructureNavigation navigation = mock(EntityStructureNavigation.class);
        final ItemFormWrapperView wrapper;
        final FormSaveHandler<Document> saveHandler;
        final Runnable closed = mock(Runnable.class);
        final Div explorer = new Div();

        @SuppressWarnings({"unchecked", "rawtypes"}) Fixture(boolean existing, boolean readOnly) {
            UI.setCurrent(new UI());
            FieldMetadataInfo titleMeta = mock(FieldMetadataInfo.class);
            when(titleMeta.getName()).thenReturn("title");
            when(titleMeta.getLabel()).thenReturn("Наименование");
            when(titleMeta.getResolvedType()).thenReturn(org.ipro.metadata.annotation.FieldType.TEXT);
            when(titleMeta.getPlaceholder()).thenReturn("");
            when(titleMeta.getValue(any())).thenAnswer(call -> ((Document) call.getArgument(0)).title);
            doAnswer(call -> { ((Document) call.getArgument(0)).title = call.getArgument(1); return null; })
                .when(titleMeta).setValue(any(), any());
            title = (TextField) new FieldFactory(null, null).createField(titleMeta, form.getBindingRegistry());
            TableSectionMetadataInfo section = mock(TableSectionMetadataInfo.class);
            when(section.getRowClass()).thenReturn((Class) Row.class);
            when(section.getGridFields()).thenReturn(List.of());
            when(section.getFormFields()).thenReturn(List.of());
            table = new ItemTable<>(section, mock(FieldFactory.class), mock(TableSectionService.class),
                mock(MetadataResolver.class), null, null, null, () -> null);
            form.addTableSection("Строки", table);

            FormResolver resolver = mock(FormResolver.class);
            doReturn(form).when(resolver).resolveItemForm(eq(Document.class), any(), any(), any());
            ApplicationContext context = mock(ApplicationContext.class);
            saveHandler = mock(FormSaveHandler.class);
            when(context.getBean(FormSaveHandler.class)).thenReturn(saveHandler);
            wrapper = new ItemFormWrapperView(context, resolver, mock(ServiceLocator.class),
                mock(ItemFormAccessBinder.class), mock(ActionRegistry.class), mock(ActionContextProvider.class),
                mock(ActionHandlerRegistry.class), mock(FormLinkService.class));
            when(navigation.availability(Document.class)).thenReturn(EntityStructureNavigation.Availability.allowed());
            when(navigation.link(Document.class)).thenReturn(Optional.of("/entity-explorer/documents"));
            when(navigation.open(Document.class, null)).thenAnswer(call -> {
                workspace.openComponent(explorer, "entity-explorer", "Структура сущностей");
                return new EntityStructureNavigation.OpenResult.Opened(Document.class,
                    Optional.of("/entity-explorer/documents"));
            });
            wrapper.setEntityStructureNavigation(navigation);
            Document loaded = new Document();
            if (existing) loaded.setId(42L);
            wrapper.init(Document.class, "custom", existing ? 42L : null, existing ? loaded : null,
                saved -> {}, closed, null);
            if (readOnly) form.setReadOnly(true);
            workspace.openComponent(wrapper, "source", "Документ");
        }

        void openExplorer() {
            MenuBar menu = form.getFooter().getChildren().filter(MenuBar.class::isInstance)
                .map(MenuBar.class::cast).findFirst().orElseThrow();
            MenuItem item = menu.getItems().get(0).getSubMenu().getItems().get(0);
            ComponentUtil.fireEvent(item, new ClickEvent<>(item, true, 0, 0, 0, 0, 1, 0,
                false, false, false, false));
        }

        Row edit() {
            title.setValue("Изменённое наименование");
            Row row = new Row();
            row.quantity = 1;
            table.applyPersistedRows(form.peekEntity(), List.of(row));
            row.quantity = 2;
            table.markDirty();
            return row;
        }
    }

    @Test void aNewDirtyFormAndOwnedRowSurviveRepeatedOpeningAndReturn() {
        Fixture f = new Fixture(false, false);
        Document draft = f.form.peekEntity();
        Row row = f.edit();
        f.openExplorer();
        f.openExplorer();
        assertThat(f.workspace.activeEntryId()).isEqualTo("entity-explorer");
        f.workspace.activate("source");
        assertThat(f.workspace.activeEntryId()).isEqualTo("source");
        assertThat(f.wrapper.getItemForm()).isSameAs(f.form);
        assertThat(f.form.peekEntity()).isSameAs(draft);
        assertThat(draft.getId()).isNull();
        assertThat(f.title.getValue()).isEqualTo("Изменённое наименование");
        assertThat(f.table.getRows()).containsExactly(row);
        assertThat(row.quantity).isEqualTo(2);
        assertThat(f.wrapper.isDirty()).isTrue();
        assertThat(f.workspace.unsavedEntryIds()).containsExactly("source");
        verifyNoInteractions(f.saveHandler, f.closed);
    }

    @Test void cancellingCloseAndThenSavingUseTheOriginalDirtyGuardAndDraft() {
        Fixture f = new Fixture(false, false);
        Row row = f.edit();
        f.openExplorer();
        f.workspace.activate("source");
        try (var dialogs = mockConstruction(ConfirmDialog.class)) {
            f.workspace.close("source");
            fire(dialogs.constructed().get(0), "setRejectButton", ConfirmDialog.RejectEvent.class);
            assertThat(f.workspace.activeEntryId()).isEqualTo("source");
            assertThat(f.wrapper.isDirty()).isTrue();
            verifyNoInteractions(f.saveHandler);
            when(f.saveHandler.save(f.form)).thenAnswer(call -> {
                Document saved = f.form.getEntity();
                assertThat(saved.title).isEqualTo("Изменённое наименование");
                assertThat(f.table.getRows()).containsExactly(row);
                assertThat(row.quantity).isEqualTo(2);
                saved.setId(77L);
                f.form.applyPersistedEntity(saved);
                f.table.applyPersistedRows(saved, List.of(row));
                return new FormSaveResult.Success<>(saved);
            });
            f.workspace.close("source");
            fire(dialogs.constructed().get(1), "setConfirmButton", ConfirmDialog.ConfirmEvent.class);
        }
        verify(f.saveHandler).save(f.form);
        assertThat(f.workspace.unsavedEntryIds()).isEmpty();
        assertThat(f.workspace.activeEntryId()).isEqualTo("entity-explorer");
        verifyNoInteractions(f.closed);
    }

    @Test void aReadOnlyCardCanOpenItsTypeWithoutSaving() {
        Fixture f = new Fixture(true, true);
        Document entity = f.form.peekEntity();
        f.openExplorer();
        f.workspace.activate("source");
        assertThat(f.form.peekEntity()).isSameAs(entity);
        assertThat(f.wrapper.isReadOnly()).isTrue();
        verify(f.navigation).open(Document.class, null);
        verifyNoInteractions(f.saveHandler, f.closed);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void fire(ConfirmDialog dialog, String method, Class<? extends ComponentEvent> event) {
        var call = mockingDetails(dialog).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals(method)).findFirst().orElseThrow();
        ((ComponentEventListener) call.getArgument(1)).onComponentEvent(mock(event));
    }

}
