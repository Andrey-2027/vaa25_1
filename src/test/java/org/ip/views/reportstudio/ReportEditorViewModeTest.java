package org.ip.views.reportstudio;

import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D3.6.4: вкладка «Макет» и два представления одного шаблона.
 *
 * <p>Набор перенесён из variant-стека (структурированная вьюха удалена), поэтому его
 * формулировки описывают канонический экран. Проверяется ровно то, из-за чего режимы могут
 * разойтись или испортить контракт изменений:</p>
 *
 * <ul>
 *   <li>стартовый режим — расширенный;</li>
 *   <li>в простом режиме показывается простая панель, а не редактор структуры;</li>
 *   <li>оба режима держат <b>один объект</b> {@code ReportTemplate};</li>
 *   <li>смена режима — состояние экрана, она не делает отчёт изменённым (F2);</li>
 *   <li>правка макета в расширенном режиме делает отчёт изменённым (F1);</li>
 *   <li>правка в простом режиме видна расширенному после переключения (паритет).</li>
 * </ul>
 */
class ReportEditorViewModeTest {

    @Test
    void startsInAdvancedMode() {
        ReportEditorView view = newView();

        assertThat(view.layoutMode()).isEqualTo(ReportEditorMode.ADVANCED);
        assertThat(view.layoutContent().getChildren().toList())
                .containsExactly(view.structureEditor());
    }

    @Test
    void switchingToUserModeDoesNotChangeTemplate() {
        ReportEditorView view = newView();
        ReportTemplate template = view.editedTemplate();
        int bandCount = template.getBands().size();

        view.setLayoutModeForTest(ReportEditorMode.USER);

        assertThat(view.layoutMode()).isEqualTo(ReportEditorMode.USER);
        assertThat(view.layoutContent().getChildren().toList())
                .as("в простом режиме показывается панель, а не редактор структуры")
                .containsExactly(view.userLayoutEditor());
        assertThat(template.getBands()).hasSize(bandCount);
        assertThat(template.getBands()).extracting(band -> band.getKind())
                .containsExactly(ReportBandKind.DETAIL);

        view.setLayoutModeForTest(ReportEditorMode.ADVANCED);
        assertThat(view.layoutContent().getChildren().toList())
                .containsExactly(view.structureEditor());
    }

    /**
     * F3 characterization: два представления обязаны работать с одним объектом
     * {@code ReportTemplate}, а не с копиями. Значение проверки в том, что она фиксирует
     * <b>способ</b> согласования — общий mutable-объект: если консолидация когда-нибудь
     * заменит его копированием между режимами, тест упадёт на идентичности ссылки раньше,
     * чем пользователь увидит расхождение представлений.
     */
    @Test
    void userAndAdvancedModesShareOneTemplateInstance() {
        ReportEditorView view = newView();
        ReportTemplate template = view.editedTemplate();

        assertThat(view.structureEditor().getTemplate())
                .as("расширенный режим обязан редактировать тот же объект шаблона, что и view")
                .isSameAs(template);

        view.setLayoutModeForTest(ReportEditorMode.USER);
        assertThat(view.editedTemplate())
                .as("USER-режим не имеет права подменять шаблон копией")
                .isSameAs(template);

        view.setLayoutModeForTest(ReportEditorMode.ADVANCED);
        assertThat(view.editedTemplate())
                .as("возврат в ADVANCED обязан вернуть тот же объект, а не пересобранный")
                .isSameAs(template);
        assertThat(view.structureEditor().getTemplate())
                .as("структура обязана сохранить привязку к шаблону после обоих переключений")
                .isSameAs(template);
    }

    /**
     * F2: переключение USER/ADVANCED — UI-состояние, а не свойство {@code ReportTemplate};
     * оно не имеет права делать форму dirty, иначе пользователь видит «есть несохранённые
     * изменения» просто за просмотр второго представления.
     */
    @Test
    void switchingLayoutModeIsNotAnEditOfTheReport() {
        ReportEditorView view = newView();
        assertThat(view.isDirty()).isFalse();

        view.setLayoutModeForTest(ReportEditorMode.USER);
        view.setLayoutModeForTest(ReportEditorMode.ADVANCED);

        assertThat(view.isDirty())
                .as("смена режима — UI-состояние, она не является правкой отчёта (F2)")
                .isFalse();
    }

    /**
     * F1: мутация макета в расширенном режиме обязана переводить редактор в dirty. До шва
     * {@code setChangeListener} редактор менял общий mutable-шаблон, но ничем об этом не
     * сообщал: обратный вызов был только у query/param-редакторов.
     */
    @Test
    void advancedLayoutMutationMarksTheEditorDirty() {
        ReportEditorView view = newView();
        assertThat(view.isDirty()).as("новый черновик чист").isFalse();

        dropColumn(view.structureEditor(), "name");

        assertThat(view.isDirty())
                .as("правка макета в расширенном режиме обязана помечать отчёт изменённым (F1)")
                .isTrue();
    }

    /**
     * Паритет режимов: правка через простую панель меняет тот же объект шаблона и после
     * переключения видна расширенному представлению. Это и есть требование «один шаблон, два
     * представления»; без него простое представление жило бы своей копией состояния.
     */
    @Test
    void userModeEditIsVisibleInAdvancedMode() {
        ReportEditorView view = newView();
        QueryField code = QueryField.scalar("code", String.class);
        view.structureEditor().updateSchema(List.of(code));
        view.userLayoutEditor().refresh();

        view.setLayoutModeForTest(ReportEditorMode.USER);
        view.userLayoutEditor().userFieldCombo().setValue(code);
        invokeAddUserField(view.userLayoutEditor());

        assertThat(view.isDirty())
                .as("правка через простую панель — изменение отчёта")
                .isTrue();

        view.setLayoutModeForTest(ReportEditorMode.ADVANCED);
        ReportTemplate template = view.structureEditor().getTemplate();
        assertThat(template).isSameAs(view.editedTemplate());
        assertThat(template.getBands().get(0).getFields())
                .as("колонка, добавленная в простом режиме, обязана быть в общей модели")
                .extracting(field -> field.getQueryField())
                .contains("code");
    }

    /** Действие простой панели — приватный обработчик: дёргается отражением, как и DnD. */
    private static void invokeAddUserField(ReportUserLayoutEditor panel) {
        try {
            java.lang.reflect.Method method =
                    ReportUserLayoutEditor.class.getDeclaredMethod("addUserField");
            method.setAccessible(true);
            method.invoke(panel);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("не удалось добавить поле через простой режим", ex);
        }
    }

    /** Перетаскивание колонки — приватный обработчик дорожки: дёргается отражением. */
    private static void dropColumn(ReportStructureEditor editor, String alias) {
        try {
            java.lang.reflect.Method method =
                    ReportStructureEditor.class.getDeclaredMethod("handleDropColumn", String.class);
            method.setAccessible(true);
            method.invoke(editor, alias);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("не удалось выполнить перетаскивание колонки", ex);
        }
    }

    private static ReportEditorView newView() {
        QueryMetadataCatalogService catalog = mock(QueryMetadataCatalogService.class);
        when(catalog.entityOptions()).thenReturn(List.of());
        when(catalog.roots(any())).thenReturn(List.of());
        return new SilentEditorView(catalog);
    }

    /** Тестовый подкласс глушит уведомления: без UI-контекста открывать нотификации нельзя. */
    private static final class SilentEditorView extends ReportEditorView {

        private SilentEditorView(QueryMetadataCatalogService catalog) {
            super(
                mock(ReportQueryGuard.class),
                mock(ReportPreviewService.class),
                mock(QueryEditorAnalysisService.class),
                catalog,
                mock(ReportTemplateService.class),
                mock(ReportExecutionService.class),
                mock(EntityLookup.class),
                mock(SelectionFormAssembler.class),
                mock(ReportQueryAssemblyService.class),
                null);
        }

        @Override
        protected void showNotification(String message) {
        }
    }
}
