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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
}
