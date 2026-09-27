package org.ip.views.reportstudio;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryAssemblyService;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.editor.QueryEditorAnalysisService;
import org.ipro.reportstudio.query.editor.QueryMetadataCatalogService;
import org.ipro.reportstudio.run.ReportExecutionService;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Забор формы экрана: панель действий канонического редактора отрисована <b>один раз</b>.
 *
 * <p>Что было. Шапка редактора собирается методом {@code headerRow()}, и она же складывает
 * в одну строку наименование, лимит строк и действия. Конструктор при этом добавлял ещё одну
 * строку с {@code toolbar()}, то есть панель действий рисовалась дважды: два независимых набора
 * из четырёх кнопок. Ни один поведенческий набор этого не видел — тесты звали действия
 * напрямую, а не искали кнопки на экране, и проверка «в раскладке ровно одно приспособление»
 * здесь бесполезна: два вызова {@code toolbar()} создают два <i>разных</i> контрола, и по
 * отдельности каждый из них существует.</p>
 *
 * <p>Поэтому проверяется не наличие, а <b>кратность</b>: каждая другая функция тулбара
 * (запрос, новый черновик, сохранение, запуск) обязана встречаться в дереве компонентов ровно
 * один раз. Данные подписи уникальны в пределах дерева редактора, что и делает проверку
 * точной, а не «примерно про кнопки».</p>
 */
class ReportEditorViewToolbarUniquenessTest {

    private static final List<String> TOOLBAR_ACTIONS =
        List.of("Запрос…", "Новый шаблон", "Сохранить", "Запустить");

    @Test
    void everyToolbarActionIsRenderedExactlyOnce() {
        ReportEditorView view = newView();

        List<String> captions = buttonCaptions(view);
        for (String action : TOOLBAR_ACTIONS) {
            assertThat(captions)
                .as("действие «%s» обязано быть в раскладке ровно один раз: второй набор кнопок — "
                    + "это дубль панели действий, а не дополнительная возможность", action)
                .containsOnlyOnce(action);
        }
    }

    @Test
    void theWalkReachesTheWholeView() {
        ReportEditorView view = newView();
        // Вкладки подключают свою страницу по выбору, поэтому редактор структуры попадает
        // в дерево только после перехода на «Макет» (D3.6.4): до этого обход видит одну страницу.
        view.selectLayoutTab();

        assertThat(descendants(view))
            .as("обход обязан доходить до вложенного редактора структуры, иначе проверка кратности "
                + "смотрела бы только на верхний уровень")
            .contains(view.structureEditor());
        assertThat(buttonCaptions(view))
            .as("с подключённой страницей кратность панели действий не меняется")
            .containsOnlyOnce(TOOLBAR_ACTIONS.toArray(String[]::new));
    }

    /** Обход дерева компонентов в глубину, включая сам корень. */
    private static List<Component> descendants(Component root) {
        List<Component> found = new ArrayList<>();
        collect(root, found);
        return found;
    }

    private static void collect(Component component, List<Component> found) {
        found.add(component);
        for (Component child : component.getChildren().toList()) {
            collect(child, found);
        }
    }

    private static List<String> buttonCaptions(Component root) {
        return descendants(root).stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .map(Button::getText)
            .toList();
    }

    private static ReportEditorView newView() {
        QueryMetadataCatalogService catalog = mock(QueryMetadataCatalogService.class);
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
