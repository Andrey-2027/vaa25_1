package org.ip.views.reportstudio;

import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.crud.EntityLookup;
import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.jr.run.JrxmlExecutionService;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.reportstudio.transfer.ReportTemplateTransferService;
import org.ipro.ureport.catalog.ReportCatalogItem;
import org.ipro.ureport.catalog.ReportCatalogService;
import org.ipro.ureport.catalog.ReportEngineType;
import org.ipro.ureport.dom.UreportTemplate;
import org.ipro.ureport.service.UreportTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D3.6 characterization: канонический каталог отчётов до консолидации.
 *
 * <p>Каталог — единственное место, где три движка (UDR-конструктор, UReport3, JR) сходятся в одну
 * таблицу, и все действия диспетчеризуются по паре {@code (type, id)}. Вариантные каталоги
 * (compact/structured) удаляются в D3.6.7, поэтому здесь закрепляется именно то поведение
 * канонического экрана, которое обязано пережить удаление вариантов:</p>
 *
 * <ul>
 *   <li>открытие и запуск по типу движка — какой движок реально трогается, а какой нет;</li>
 *   <li>UDR-операции (копия/экспорт/импорт), их отказ для чужих движков и откат при ошибке;</li>
 *   <li>удаление только через подтверждение, с последующим перечитыванием каталога;</li>
 *   <li>отсутствующий файл шаблона — отказ без обращения к движку;</li>
 *   <li>действие без выбранной строки ничего не делает.</li>
 * </ul>
 *
 * <p><b>Как тест видит UI-эффекты.</b> Каталог сообщает о результате через
 * {@code Notification}/{@code ConfirmDialog}, а Vaadin 25 добавляет overlay через
 * {@code OverlayAutoAddController}, которому нужен активный UI с сессией и блокировкой — в
 * unit-тесте такая сессия не собирается. Поэтому фикстура перехватывает <i>создание</i> самих
 * компонентов ({@code Mockito.mockConstruction}): это даёт и текст пользовательского сообщения
 * (первый аргумент конструктора {@code Notification}), и возможность «нажать» подтверждение
 * удаления, не поднимая Vaadin-окружение. UI-контекст нужен только для того, чтобы
 * {@code Dialog.open()} не падал.</p>
 *
 * <p><b>Что здесь осознанно не проверяется.</b> Класс конкретного диалога запуска
 * ({@code ReportRunDialog}/{@code JrxmlRunDialog}/{@code UreportParamsDialog}) — сам факт
 * «какой движок пошёл в работу» уже различается по вызовам сервисов и по состоянию редактора
 * (запуск, в отличие от открытия UDR, не подменяет черновик редактора).</p>
 */
class ReportCatalogViewTest {

    private static final String DESIGNER_URL = "http://host/ureport/designer?_u=file:/tmp/a.ureport.xml";

    private final List<ReportCatalogItem> catalogItems = new ArrayList<>();
    private final List<String> notifications = new ArrayList<>();
    private final AtomicInteger catalogLoads = new AtomicInteger();

    private ReportTemplateService templateService;
    private ReportTemplateTransferService transferService;
    private ReportCatalogService catalogService;
    private UreportTemplateService ureportService;
    private JrxmlTemplateService jrxmlService;
    private JrxmlExecutionService jrxmlExecutionService;
    private UI ui;
    private MockedConstruction<Notification> notificationConstruction;

    @BeforeEach
    void setUp() {
        ui = new UI();
        UI.setCurrent(ui);
        // диалог запуска читает пользователя из Spring Security, а не из бин-контекста
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));

        notificationConstruction = mockConstruction(Notification.class, (mock, context) -> {
            if (!context.arguments().isEmpty() && context.arguments().get(0) instanceof String text) {
                notifications.add(text);
            }
        });

        templateService = mock(ReportTemplateService.class);
        transferService = mock(ReportTemplateTransferService.class);
        catalogService = mock(ReportCatalogService.class);
        ureportService = mock(UreportTemplateService.class);
        jrxmlService = mock(JrxmlTemplateService.class);
        jrxmlExecutionService = mock(JrxmlExecutionService.class);
        when(catalogService.findAll(any(), anyBoolean())).thenAnswer(invocation -> {
            catalogLoads.incrementAndGet();
            return List.copyOf(catalogItems);
        });
    }

    @AfterEach
    void tearDown() {
        try {
            notificationConstruction.close();
        } catch (Exception ignored) {
            // закрытие перехвата не влияет на результат теста
        }
        UI.setCurrent(null);
        SecurityContextHolder.clearContext();
    }

    // === каталог ===

    @Test
    void loadsUnifiedCatalogOnInitialization() {
        newView();

        // единый каталог мёржит оба движка, а не только UDR
        verify(catalogService).findAll("", true);
    }

    @Test
    void typingInSearchRefreshesTheCatalogWithTheTermAndDisabledReports() {
        ReportCatalogView view = newView();

        searchField(view).setValue("остат");

        // поле поиска само перечитывает каталог; выключенные отчёты каталог показывает всегда
        verify(catalogService).findAll("остат", true);
        assertThat(catalogLoads.get()).as("конструктор + реакция на ввод").isEqualTo(2);
    }

    // === открытие по типу движка ===

    @Test
    void openingUdrLoadsTheTemplateIntoTheCanonicalEditor() {
        ReportTemplate template = udrTemplate(3L, "Остатки");
        when(templateService.loadTemplate(3L)).thenReturn(template);
        ReportCatalogView view = viewWithSelection(udrItem(3L, "Остатки"));

        invoke(view, "openSelected");

        assertThat(editorOf(view).editedTemplate())
                .as("открытие UDR отдаёт шаблон именно каноническому редактору")
                .isSameAs(template);
        assertThat(notifications).as("успешное открытие не сообщает об ошибке").isEmpty();
    }

    @Test
    void openingJrShowsInfoWithoutTouchingEngineServices() {
        ReportCatalogView view = viewWithSelection(jrItem(4L, "Прайс лист"));

        invoke(view, "openSelected");

        assertThat(notifications).as("шаблон JR открывается сведениями, а не ошибкой").isEmpty();
        assertThat(editorOf(view).editedTemplate().getId())
                .as("чужой движок не подменяет черновик редактора")
                .isNull();
    }

    @Test
    void openingUreportOpensTheExternalDesignerAndNotAnInternalEditor() {
        ReportCatalogView view = viewWithSelection(ureportItem(2L, "Азбука"));

        invoke(view, "openSelected");

        assertThat(notifications).isEmpty();
        assertThat(editorOf(view).editedTemplate().getId())
                .as("дизайнер UReport3 живёт вне редактора UDR")
                .isNull();
    }

    @Test
    void openingAnItemWithMissingTemplateFileIsRefusedWithoutEngineCalls() {
        ReportCatalogView view = viewWithSelection(missingFileItem(5L, "Пропавший"));

        invoke(view, "openSelected");

        assertThat(notifications).containsExactly("Файл шаблона отсутствует");
        assertThat(catalogLoads.get()).as("отказ не трогает каталог").isEqualTo(2);
    }

    // === запуск по типу движка ===

    @Test
    void runningUdrUsesTheUdrEngineOnlyAndKeepsTheEditorDraft() {
        ReportTemplate template = udrTemplate(3L, "Остатки");
        when(templateService.loadTemplate(3L)).thenReturn(template);
        ReportCatalogView view = viewWithSelection(udrItem(3L, "Остатки"));

        invoke(view, "runSelected");

        verify(templateService).loadTemplate(3L);
        verify(jrxmlService, never()).findById(any());
        verify(ureportService, never()).findById(any());
        assertThat(editorOf(view).editedTemplate().getId())
                .as("запуск отчёта не считается его открытием на правку")
                .isNull();
        assertThat(notifications).isEmpty();
    }

    @Test
    void runningJrResolvesTheTemplateByIdOnly() {
        JrxmlTemplate template = new JrxmlTemplate();
        template.setId(4L);
        template.setName("Прайс лист");
        template.setFileName("price.jrxml");
        when(jrxmlService.findById(4L)).thenReturn(Optional.of(template));
        ReportCatalogView view = viewWithSelection(jrItem(4L, "Прайс лист"));

        invoke(view, "runSelected");

        verify(jrxmlService).findById(4L);
        verify(templateService, never()).loadTemplate(any());
        verify(ureportService, never()).findById(any());
    }

    @Test
    void runningUreportLoadsParamSpecsFromTheDesignerFile() {
        UreportTemplate template = new UreportTemplate();
        template.setId(2L);
        template.setName("Азбука");
        template.setFileName("a.ureport.xml");
        when(ureportService.findById(2L)).thenReturn(Optional.of(template));
        when(ureportService.loadParamSpecs("a.ureport.xml")).thenReturn(List.of());
        ReportCatalogView view = viewWithSelection(ureportItem(2L, "Азбука"));

        invoke(view, "runSelected");

        verify(ureportService).findById(2L);
        verify(ureportService).loadParamSpecs("a.ureport.xml");
        verify(templateService, never()).loadTemplate(any());
        verify(jrxmlService, never()).findById(any());
    }

    @Test
    void runningAnItemWithMissingTemplateFileIsRefusedWithoutEngineCalls() {
        ReportCatalogView view = viewWithSelection(missingFileItem(5L, "Пропавший"));

        invoke(view, "runSelected");

        assertThat(notifications).containsExactly("Файл шаблона отсутствует");
        verify(templateService, never()).loadTemplate(any());
        verify(jrxmlService, never()).findById(any());
        verify(ureportService, never()).findById(any());
    }

    // === копия / экспорт / импорт (только UDR) ===

    @Test
    void copyIsUdrOnlyAndMovesTheSelectionToTheCopy() {
        ReportCatalogItem source = udrItem(3L, "Остатки");
        ReportTemplate copy = udrTemplate(9L, "Остатки (копия)");
        when(templateService.copyTemplate(3L)).thenReturn(copy);
        ReportCatalogView view = viewWithSelection(source);
        // строку копии НЕ добавляем в мок каталога: выделение обязано встать и без второго чтения

        invoke(view, "copySelected");

        verify(templateService).copyTemplate(3L);
        assertThat(editorOf(view).editedTemplate())
                .as("после копии редактор показывает именно копию")
                .isSameAs(copy);
        assertThat(catalogGrid(view).asSingleSelect().getValue())
                .as("выделение переезжает на копию, иначе пользователь правит не тот отчёт")
                .extracting(ReportCatalogItem::id)
                .isEqualTo(9L);
        // одно чтение: перечитывание списка; запись копии собирает общий узел сборки (D3.6)
        assertThat(catalogLoads.get()).as("каталог перечитан после создания копии").isEqualTo(3);
        assertThat(notifications).containsExactly("Создана копия «Остатки (копия)»");
    }

    @Test
    void copyOfANonUdrReportIsRefused() {
        ReportCatalogItem item = ureportItem(2L, "Азбука");
        ReportCatalogView view = viewWithSelection(item);

        invoke(view, "copySelected");

        verify(templateService, never()).copyTemplate(any());
        assertThat(notifications).containsExactly("Копирование только для UDR");
        assertThat(catalogGrid(view).asSingleSelect().getValue())
                .as("отказ не двигает выделение")
                .isSameAs(item);
        assertThat(catalogLoads.get()).as("отказ не перечитывает каталог").isEqualTo(2);
    }

    @Test
    void copyFailureIsReportedAndChangesNothing() {
        ReportTemplate failed = udrTemplate(3L, "Остатки");
        when(templateService.copyTemplate(3L)).thenThrow(new IllegalStateException("нет прав"));
        ReportCatalogView view = viewWithSelection(udrItem(3L, "Остатки"));

        invoke(view, "copySelected");

        assertThat(notifications).containsExactly("Не удалось создать копию: нет прав");
        assertThat(catalogLoads.get()).as("неудачная копия не перечитывает каталог").isEqualTo(2);
        assertThat(editorOf(view).editedTemplate().getId()).isNull();
        verify(templateService).copyTemplate(3L);
    }

    @Test
    void exportIsUdrOnlyAndHandsTheTemplateToTheTransferService() {
        ReportTemplate template = udrTemplate(3L, "Остатки");
        when(templateService.loadTemplate(3L)).thenReturn(template);
        when(transferService.exportTemplate(template)).thenReturn("{\"name\":\"Остатки\"}");
        ReportCatalogView view = viewWithSelection(udrItem(3L, "Остатки"));

        invoke(view, "exportSelected");

        verify(transferService).exportTemplate(template);
        assertThat(notifications).as("успешный экспорт не сообщает об ошибке").isEmpty();
    }

    @Test
    void exportOfANonUdrReportIsRefused() {
        ReportCatalogView view = viewWithSelection(jrItem(4L, "Прайс лист"));

        invoke(view, "exportSelected");

        verify(templateService, never()).loadTemplate(any());
        verify(transferService, never()).exportTemplate(any());
        assertThat(notifications).containsExactly("Экспорт JSON только для UDR");
    }

    @Test
    void importFeedsTheJsonIntoTheTransferServiceAndOpensTheResult() {
        String json = "{\"name\":\"Импортированный\"}";
        ReportTemplate imported = udrTemplate(12L, "Импортированный");
        when(transferService.importTemplate(json)).thenReturn(imported);
        ReportCatalogView view = newView();

        invoke(view, "importJson", new Class<?>[]{MemoryBuffer.class}, uploadOf(json));

        verify(transferService).importTemplate(json);
        assertThat(editorOf(view).editedTemplate())
                .as("после импорта редактор открывает импортированный шаблон")
                .isSameAs(imported);
        assertThat(catalogGrid(view).asSingleSelect().getValue())
                .extracting(ReportCatalogItem::id)
                .isEqualTo(12L);
        // одно чтение: перечитывание списка; запись импортированного собирает общий узел сборки
        assertThat(catalogLoads.get()).as("каталог перечитан после импорта").isEqualTo(2);
        assertThat(notifications).containsExactly("Импортирован «Импортированный»");
    }

    @Test
    void importRejectionIsReportedAndChangesNothing() {
        when(transferService.importTemplate(any()))
                .thenThrow(new IllegalArgumentException("битый JSON"));
        ReportCatalogView view = newView();

        invoke(view, "importJson", new Class<?>[]{MemoryBuffer.class}, uploadOf("{битый"));

        assertThat(notifications).containsExactly("Импорт отклонён: битый JSON");
        assertThat(catalogLoads.get()).as("отклонённый импорт не перечитывает каталог").isEqualTo(1);
        assertThat(editorOf(view).editedTemplate().getId()).isNull();
    }

    // === удаление: только через подтверждение ===

    @Test
    void deleteIsRefusedForUdrWithoutAnyDialog() {
        ReportCatalogView view = viewWithSelection(udrItem(3L, "Остатки"));

        try (MockedConstruction<ConfirmDialog> construction = mockConstruction(ConfirmDialog.class)) {
            invoke(view, "deleteSelected");

            assertThat(construction.constructed())
                    .as("UDR удаляется из редактора, а не из каталога")
                    .isEmpty();
            assertThat(notifications)
                    .containsExactly("Удаление UDR в каталоге не поддерживается");
            assertThat(catalogLoads.get()).isEqualTo(2);
        }
    }

    @Test
    void deletingUreportRequiresConfirmationThenRemovesAndRefreshes() {
        ReportCatalogView view = viewWithSelection(ureportItem(2L, "Азбука"));

        try (MockedConstruction<ConfirmDialog> construction = mockConstruction(ConfirmDialog.class)) {
            invoke(view, "deleteSelected");

            ConfirmDialog confirm = onlyDialog(construction);
            verify(confirm).setHeader("Удалить UReport3?");
            verify(confirm).setText(contains("Азбука"));
            assertThat(notifications)
                    .as("до подтверждения удаления нет")
                    .doesNotContain("Отчёт «Азбука» удалён");
            verify(ureportService, never()).delete(any());

            fireConfirm(confirm);

            verify(ureportService).delete(2L);
            assertThat(catalogLoads.get()).as("каталог перечитан после удаления").isEqualTo(3);
            assertThat(catalogGrid(view).asSingleSelect().getValue())
                    .as("после перечитывания выделение снимается")
                    .isNull();
            assertThat(notifications).contains("Отчёт «Азбука» удалён");
        }
    }

    @Test
    void deletingJrRequiresConfirmationThenRemovesAndRefreshes() {
        ReportCatalogView view = viewWithSelection(jrItem(4L, "Прайс лист"));

        try (MockedConstruction<ConfirmDialog> construction = mockConstruction(ConfirmDialog.class)) {
            invoke(view, "deleteSelected");

            ConfirmDialog confirm = onlyDialog(construction);
            verify(confirm).setHeader("Удалить отчёт JR?");
            verify(confirm).setText(contains("Прайс лист"));
            verify(jrxmlService, never()).delete(any());

            fireConfirm(confirm);

            verify(jrxmlService).delete(4L);
            assertThat(catalogLoads.get()).isEqualTo(3);
            assertThat(notifications).contains("Отчёт «Прайс лист» удалён");
        }
    }

    // === действие без выбранной строки ===

    @Test
    void anyActionWithoutSelectionAsksToChooseAReport() {
        ReportCatalogView view = newView();

        for (String action : List.of("openSelected", "runSelected", "copySelected",
                "exportSelected", "deleteSelected")) {
            invoke(view, action);
        }

        assertThat(notifications)
                .as("каждое действие без выделения просит выбрать отчёт")
                .hasSize(5)
                .allMatch("Выберите отчёт в каталоге"::equals);
        verify(templateService, never()).loadTemplate(any());
        verify(templateService, never()).copyTemplate(any());
        verify(transferService, never()).exportTemplate(any());
        verify(ureportService, never()).delete(any());
        verify(jrxmlService, never()).delete(any());
        assertThat(catalogLoads.get()).isEqualTo(1);
    }

    // === fixtures ===

    private ReportCatalogView newView() {
        QueryMetadataCatalogService metadata = mock(QueryMetadataCatalogService.class);
        when(metadata.roots(any())).thenReturn(List.of());
        return new ReportCatalogView(templateService, transferService, catalogService,
                ureportService, jrxmlService, jrxmlExecutionService,
                mock(ReportQueryGuard.class), mock(ReportPreviewService.class),
                mock(QueryEditorAnalysisService.class), metadata,
                mock(ReportExecutionService.class), mock(EntityLookup.class),
                mock(SelectionFormAssembler.class), mock(ReportQueryAssemblyService.class), null);
    }

    /** Одна запись в каталоге, выбранная строка — как после клика пользователя. */
    private ReportCatalogView viewWithSelection(ReportCatalogItem item) {
        ReportCatalogView view = newView();
        catalogItems.clear();
        catalogItems.add(item);
        view.refreshCatalog();
        catalogGrid(view).select(item);
        return view;
    }

    private static ReportCatalogItem udrItem(long id, String name) {
        return new ReportCatalogItem(id, ReportEngineType.UDR, name, "описание", true, null, false);
    }

    private static ReportCatalogItem ureportItem(long id, String name) {
        return new ReportCatalogItem(id, ReportEngineType.UREPORT3, name, "описание", true,
                DESIGNER_URL, false);
    }

    private static ReportCatalogItem jrItem(long id, String name) {
        return new ReportCatalogItem(id, ReportEngineType.JR, name, "описание", true, null, false);
    }

    private static ReportCatalogItem missingFileItem(long id, String name) {
        return new ReportCatalogItem(id, ReportEngineType.UREPORT3, name, "описание", true,
                DESIGNER_URL, true);
    }

    private static ReportTemplate udrTemplate(long id, String name) {
        ReportTemplate template = new ReportTemplate();
        template.setId(id);
        template.setName(name);
        return template;
    }

    private static MemoryBuffer uploadOf(String json) {
        MemoryBuffer buffer = new MemoryBuffer();
        try (OutputStream out = buffer.receiveUpload("report.json", "application/json")) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            throw new AssertionError("не удалось подготовить загрузку", ex);
        }
        return buffer;
    }

    private static ConfirmDialog onlyDialog(MockedConstruction<ConfirmDialog> construction) {
        assertThat(construction.constructed())
                .as("удаление обязано спросить подтверждение")
                .hasSize(1);
        return construction.constructed().get(0);
    }

    /** «Нажать» кнопку подтверждения, не поднимая Vaadin-UI. */
    private static void fireConfirm(ConfirmDialog confirm) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<ComponentEventListener<ConfirmDialog.ConfirmEvent>> listener =
                ArgumentCaptor.forClass(ComponentEventListener.class);
        verify(confirm).addConfirmListener(listener.capture());
        listener.getValue().onComponentEvent(new ConfirmDialog.ConfirmEvent(confirm, true));
    }

    // === доступ к внутренностям view ===

    @SuppressWarnings("unchecked")
    private static Grid<ReportCatalogItem> catalogGrid(ReportCatalogView view) {
        return (Grid<ReportCatalogItem>) field(view, "grid");
    }

    private static TextField searchField(ReportCatalogView view) {
        return (TextField) field(view, "search");
    }

    private static ReportEditorView editorOf(ReportCatalogView view) {
        return (ReportEditorView) field(view, "editor");
    }

    private static Object field(ReportCatalogView view, String name) {
        try {
            Field field = ReportCatalogView.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(view);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("не удалось прочитать поле " + name, ex);
        }
    }

    private static void invoke(ReportCatalogView view, String method, Class<?>[] parameterTypes,
                               Object... args) {
        try {
            Method target = ReportCatalogView.class.getDeclaredMethod(method, parameterTypes);
            target.setAccessible(true);
            target.invoke(view, args);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("не удалось вызвать " + method, ex);
        }
    }

    private static void invoke(ReportCatalogView view, String method) {
        invoke(view, method, new Class<?>[0]);
    }
}
