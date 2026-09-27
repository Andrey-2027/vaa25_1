package org.ip.form.coordinator;

import com.vaadin.flow.component.UI;
import org.ip.model.Workshop;
import org.ipro.crud.BaseService;
import org.ipro.crud.EntityCopyService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.FieldFactory;
import org.ipro.form.TableSectionFactory;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.form.coordinator.FormOpenMode;
import org.ipro.form.coordinator.ItemFormAccessBinder;
import org.ipro.form.coordinator.ItemFormWrapperView;
import org.ipro.form.coordinator.ListFormWrapper;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.link.FormRoute;
import org.ipro.form.link.OpenResult;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridViewStore;
import org.ipro.form.spi.WorkspaceGateway;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsUiGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E2.2: исход открытия по адресу — типизированный и безопасный.
 *
 * <p>Проверяется четыре вещи, которые нельзя оставить комментарием: чтение записи идёт
 * <b>до</b> вкладки; отказ не создаёт вкладку и не выбрасывает исключение наружу; исход различает
 * «нет строки», «нет доступа» и «сломалось»; повторное открытие перепроверяет доступ, а не
 * активирует вкладку с прошлого раза.</p>
 *
 * <p>Координатор здесь настоящий (с заглушками сервиса и рабочей области): именно его порядок
 * проверок и есть предмет контракта, а проверять порядок на моке координатора было бы проверкой
 * мока.</p>
 */
class FormRouteOpenOutcomeTest {

    private static final long EXISTING_ID = 42L;

    private MetadataResolver metadataResolver;
    private FormResolver formResolver;
    private ServiceLocator serviceLocator;
    private WorkspaceGateway gateway;

    @BeforeEach
    void setUp() {
        UI.setCurrent(new UI());

        metadataResolver = mock(MetadataResolver.class);
        EntityMetadataInfo meta = mock(EntityMetadataInfo.class);
        when(meta.getItemFormTitle()).thenReturn("Цех");
        when(meta.getListFormTitle()).thenReturn("Цеха");
        when(metadataResolver.resolve(Workshop.class)).thenReturn(meta);

        formResolver = mock(FormResolver.class);
        when(formResolver.getFormRegistry()).thenReturn(new FormRegistry());
        serviceLocator = mock(ServiceLocator.class);
        gateway = mock(WorkspaceGateway.class);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
    }

    @Test
    void recordIsReadBeforeAnyTabIsOpened() {
        BaseService<Workshop, Long> service =
            serviceReturning(Workshop.class, EXISTING_ID, Optional.of(mock(Workshop.class)));

        OpenResult result = coordinator(gateway).openRoutedRecord(Workshop.class,
            FormRoute.record("workshop", EXISTING_ID));

        assertThat(result)
            .as("исход положительный: адрес существует и открылся")
            .isInstanceOf(OpenResult.Opened.class);
        assertThat(result.outcome()).isEqualTo("opened");
        verifyReadBeforeOpen(service, EXISTING_ID, gateway);
    }

    @Test
    void missingRecordIsNotFoundAndCreatesNoTab() {
        serviceReturning(Workshop.class, EXISTING_ID, Optional.empty());

        OpenResult result = coordinator(gateway).openRoutedRecord(Workshop.class,
            FormRoute.record("workshop", EXISTING_ID));

        assertThat(result).isInstanceOf(OpenResult.NotFound.class);
        assertThat(result.outcome()).isEqualTo("not-found");
        assertThat(result.message())
            .as("hidden и missing не различаются: различение было бы инструментом проверки"
                + " существования чужих записей")
            .contains("не найдена");
        verify(gateway, never()).open(any(), anyString(), anyString(), any());
    }

    @Test
    void classLevelDenyIsForbiddenAndCreatesNoTab() {
        serviceThrowing(Workshop.class, EXISTING_ID,
            new RlsAccessDeniedException("Нет права чтения Workshop"));

        OpenResult result = coordinator(gateway).openRoutedRecord(Workshop.class,
            FormRoute.record("workshop", EXISTING_ID));

        assertThat(result)
            .as("отказ в правах — это 403, а не «не найдено»: иначе пользователь считал бы,"
                + " что записи нет, а администратор — что защита не сработала")
            .isInstanceOf(OpenResult.Forbidden.class);
        assertThat(result.outcome()).isEqualTo("forbidden");
        verify(gateway, never()).open(any(), anyString(), anyString(), any());
    }

    @Test
    void serviceFailureIsUnavailableNotNotFound() {
        serviceThrowing(Workshop.class, EXISTING_ID,
            new IllegalStateException("Workshop не имеет автономного DETAIL-чтения"));

        OpenResult result = coordinator(gateway).openRoutedRecord(Workshop.class,
            FormRoute.record("workshop", EXISTING_ID));

        assertThat(result)
            .as("инфраструктурный отказ не маскируется под отсутствующую запись")
            .isInstanceOf(OpenResult.Unavailable.class);
        assertThat(result.outcome()).isEqualTo("unavailable");
        verify(gateway, never()).open(any(), anyString(), anyString(), any());
    }

    @Test
    void recordLostBetweenPreflightAndTabAssemblyIsStillNotFound() {
        serviceReturning(Workshop.class, EXISTING_ID, Optional.of(mock(Workshop.class)));
        doThrow(new ItemFormWrapperView.RecordUnavailableException(Workshop.class, EXISTING_ID))
            .when(gateway).open(any(), anyString(), anyString(), any());

        OpenResult result = coordinator(gateway).openRoutedRecord(Workshop.class,
            FormRoute.record("workshop", EXISTING_ID));

        assertThat(result)
            .as("гонка между preflight и сборкой даёт тот же 404: вкладка не добавлена,"
                + " компонент не закеширован (E2.0a)")
            .isInstanceOf(OpenResult.NotFound.class);
    }

    @Test
    void accessIsRecheckedOnEveryOpenNotOnlyOnTheFirstOne() {
        BaseService<Workshop, Long> service =
            serviceReturning(Workshop.class, EXISTING_ID, Optional.of(mock(Workshop.class)));
        FormCoordinator coordinator = coordinator(gateway);
        FormRoute route = FormRoute.record("workshop", EXISTING_ID);

        assertThat(coordinator.openRoutedRecord(Workshop.class, route))
            .isInstanceOf(OpenResult.Opened.class);

        // Право/строка «потеряны»: та же вкладка, тот же адрес, другое состояние данных.
        stubReturning(service, EXISTING_ID, Optional.empty());

        OpenResult second = coordinator.openRoutedRecord(Workshop.class, route);

        assertThat(second)
            .as("вкладка уже открыта, но право и видимость строки проверяются заново:"
                + " активация вкладки с прошлого раза не является разрешением")
            .isInstanceOf(OpenResult.NotFound.class);
        verifyReadCount(service, EXISTING_ID, 2);
        verify(gateway, times(1)).open(any(), anyString(), anyString(), any());
    }

    @Test
    void routedRecordOpensInWorkspaceRegardlessOfTheConfiguredOpenMode() {
        serviceReturning(Workshop.class, EXISTING_ID, Optional.of(mock(Workshop.class)));
        FormCoordinator coordinator = coordinator(gateway);
        coordinator.setItemFormOpenMode(FormOpenMode.DIALOG);

        OpenResult result = coordinator.openRoutedRecord(Workshop.class,
            FormRoute.record("workshop", EXISTING_ID));

        assertThat(result).isInstanceOf(OpenResult.Opened.class);
        verify(gateway).open(eq(ItemFormWrapperView.class), anyString(), anyString(), any());
        assertThat(coordinator.getItemFormOpenMode())
            .as("адрес не меняет конфигурацию открытия: иначе ссылка изменила бы поведение"
                + " обычных вызовов у того же пользователя")
            .isEqualTo(FormOpenMode.DIALOG);
    }

    @Test
    void listAddressOpensTheListTabThroughTheExistingPath() {
        OpenResult result = coordinator(gateway).openRoutedList(Workshop.class,
            FormRoute.list("workshop"));

        assertThat(result).isInstanceOf(OpenResult.Opened.class);
        verify(gateway).open(eq(ListFormWrapper.class), eq("workshop"), anyString(), any());
    }

    @Test
    void withoutWorkspaceTheOutcomeIsUnavailableNotAnException() {
        serviceReturning(Workshop.class, EXISTING_ID, Optional.of(mock(Workshop.class)));

        OpenResult result = coordinator(null).openRoutedRecord(Workshop.class,
            FormRoute.record("workshop", EXISTING_ID));

        assertThat(result)
            .as("прямой адрес всегда открывает Workspace, но отсутствие рабочей области —"
                + " состояние окружения, а не повод показать пользователю Java-исключение")
            .isInstanceOf(OpenResult.Unavailable.class);
        verify(gateway, never()).open(any(), anyString(), anyString(), any());
    }

    @Test
    void kindMismatchIsACallerErrorAndFailsLoudly() {
        FormCoordinator coordinator = coordinator(gateway);

        assertThatThrownBy(() -> coordinator.openRoutedRecord(Workshop.class, FormRoute.list("workshop")))
            .as("вид адреса выбирает вызывающий: молча открыть не то, что просили, хуже отказа")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ITEM");
        assertThatThrownBy(() -> coordinator.openRoutedList(Workshop.class,
            FormRoute.record("workshop", 1L)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("LIST");
    }

    // === фикстуры ===

    private FormCoordinator coordinator(WorkspaceGateway workspaceGateway) {
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkspaceGateway> gateways = mock(ObjectProvider.class);
        when(gateways.getIfAvailable()).thenReturn(workspaceGateway);

        return new FormCoordinator(
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
            // Без моста адреса: этот тест проверяет исходы открытия, а не синхронизацию URL.
            // Возможность не передать мост — часть контракта координатора, а не поблажка тесту.
            null);
    }

    /**
     * Сервис-заглушка, возвращающий запись для {@code id}.
     *
     * <p>Тип параметров переменный ({@code T, ID}), а не {@code Workshop, Long}: при {@code ID = Long}
     * вызов {@code service.findById(id)} в необобщённом контексте неоднозначен (границы
     * {@code BaseService.findById(ID)} и {@code CrudService.findById(Long)} совпадают), и заглушка
     * встала бы на другую перегрузку, чем та, которую зовёт продовый код. В продакшене вызов идёт
     * с переменной типа, поэтому и в тесте искусственного выбора нет.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> BaseService<T, ID> serviceReturning(
            Class<T> type, ID id, Optional<T> result) {
        BaseService<T, ID> service = mock(BaseService.class);
        when(service.findById(id)).thenReturn(result);
        doReturn(service).when(serviceLocator).findService(type);
        return service;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> BaseService<T, ID> serviceThrowing(
            Class<T> type, ID id, RuntimeException failure) {
        BaseService<T, ID> service = mock(BaseService.class);
        when(service.findById(id)).thenThrow(failure);
        doReturn(service).when(serviceLocator).findService(type);
        return service;
    }

    /** Смена ответа у той же заглушки: проверка «право перепроверяется на каждом входе». */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> void stubReturning(
            BaseService<T, ID> service, ID id, Optional<T> result) {
        when(service.findById(id)).thenReturn(result);
    }

    /** Порядок «чтение → вкладка»: обе части проверяются на одном и том же вызове. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> void verifyReadBeforeOpen(
            BaseService<T, ID> service, ID id, WorkspaceGateway gateway) {
        InOrder order = inOrder(service, gateway);
        order.verify(service).findById(id);
        order.verify(gateway).open(eq(ItemFormWrapperView.class), eq("item-workshop-42"),
            anyString(), any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity, ID> void verifyReadCount(
            BaseService<T, ID> service, ID id, int expected) {
        verify(service, times(expected)).findById(id);
    }
}
