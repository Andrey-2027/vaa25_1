package org.ip.views.reportstudio;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasLabel;
import com.vaadin.flow.component.HasText;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D3.6.4, срез B: принятый capability ledger канонического экрана.
 *
 * <p><b>Зачем она нужна именно сейчас.</b> Ревью D3.6 справедливо указало, что канонический
 * {@code /report-editor} — ещё не полный superset варианта: панель страницы, простой режим и
 * визуальный отбор жили только в варианте. До его удаления parity был измерен двусторонне.
 * Эта книга сохраняется как контракт возможностей: каждый сценарий должен оставаться видимым
 * в каноническом экране после удаления дублей.</p>
 *
 * <p>Книга фиксирует <b>состав возможностей</b>, подтверждённый перед удалением дублей:</p>
 *
 * <ul>
 *   <li>каждая строка должна оставаться видимой в каноническом экране;</li>
 *   <li>проверка должна достигать каждого раздела, иначе книга станет вакуумной.</li>
 * </ul>
 *
 * <p>Возможность описывается строками интерфейса — подписями контролов, заголовками колонок и
 * заголовками раскрывающихся секций: это то, что пользователь видит и чем пользуется. Обход идёт
 * по разделам экрана (вкладка параметров, расширенный режим, простой режим, страница), потому что
 * разделы подключаются по выбору вкладки и в одном дереве одновременно не живут.</p>
 *
 * <p>Проверка <b>не</b> измеряет поведение: диалог расхождений, предупреждение о недоступных
 * настройках и замена ссылки проверены поведенческими наборами
 * {@code ReportEditorViewReconcileTest} и {@code ReportEditorViewModeTest}.</p>
 */
class ReportEditorViewSupersetTest {

    /** Разделы экрана: обход делается по одному разу на раздел, строки книги ссылаются на них. */
    private enum Region { SHELL, PARAMS, ADVANCED, USER, PAGE }

    /** Строка книги: что именно должна давать консолидированная форма экрана и чем это видно. */
    private record Capability(String name, Region region, List<String> evidence) { }

    private static final List<Capability> LEDGER = List.of(
        new Capability("оболочка: шапка, действия и текст запроса", Region.SHELL, List.of(
            "Наименование отчёта", "Максимум строк", "Описание шаблона", "Текст запроса",
            "Редактировать запрос…", "Запрос…", "Новый шаблон", "Сохранить", "Запустить",
            "Запросы", "Макет", "Страница")),
        new Capability("параметры отчёта", Region.PARAMS, List.of(
            "Параметры отчёта", "Добавить параметр", "Удалить выбранный",
            "Имя", "Вид", "Источник", "Обязательный", "На форме")),
        // Заголовки колонок грида бэндов не входят: bandsPanel не подключена к экрану,
        // поэтому пользователь этого грида не видит. Строка про него измеряла бы мёртвое UI.
        new Capability("расширенный редактор структуры", Region.ADVANCED, List.of(
            "Режим редактирования", "Структура отчёта", "Добавить бэнд", "Доступные поля",
            "Поле запроса", "Свойства поля", "Агрегация", "Видимость",
            "Поле группировки", "Родительская группа", "С новой страницы",
            "Расположение заголовка", "Применить к бэнду", "Текст…")),
        new Capability("простой режим: поля отчёта", Region.USER, List.of(
            "Настройка макета отчёта", "Поля отчёта", "Поле", "Добавить поле",
            "Заголовок", "Видно", "Ширина, px", "Выравнивание", "Формат", "Показывать",
            "Граница", "Применить свойства", "Свойства выбранного поля")),
        new Capability("простой режим: группировки и итоги", Region.USER, List.of(
            "Группировки и итоги", "Группировать по", "Добавить группировку", "Группировка",
            "Группа для итога", "Поле итога", "Итог", "Добавить итог группы", "Добавить общий итог")),
        new Capability("простой режим: отбор данных", Region.USER, List.of("Отбор данных")),
        new Capability("простой режим: сортировка", Region.USER, List.of(
            "Сортировка", "Поле сортировки", "Направление", "Добавить сортировку")),
        new Capability("простой режим: оформление и подсказка о странице", Region.USER, List.of(
            "Оформление колонок", "Настройки страницы")),
        new Capability("страница: параметры страницы и сортировка отчёта", Region.PAGE, List.of(
            "Параметры страницы", "Сортировка отчёта", "Границы колонок", "Полосатость строк",
            "Размер шрифта, pt", "Формат страницы", "Ориентация", "Колонка (alias)"))
    );

    @Test
    void canonicalViewRetainsEveryAcceptedCapability() {
        Evidence canonical = evidenceOf(canonicalView());

        for (Capability capability : LEDGER) {
            assertThat(canonical.of(capability.region()))
                .as("канонический экран потерял возможность «%s», принятую при parity-аудите",
                    capability.name())
                .containsAll(capability.evidence());
        }
    }

    /**
     * Четыре раздела канонического экрана. Количество проверяется отдельно от книги: пропуск
     * вкладки должен быть заметен независимо от конкретных подписей внутри неё.
     */
    @Test
    void canonicalViewKeepsFourSections() {
        assertThat(sectionCount(canonicalView())).isEqualTo(4);
    }

    /**
     * Не-вакуумность харнесса: обход обязан доходить до каждого раздела. Без этой проверки сломавшийся
     * переход по вкладкам дал бы пустые множества, а книга «прошла» бы сравнением пустого с пустым.
     */
    @Test
    void theProbesReachEveryRegion() {
        Evidence evidence = evidenceOf(canonicalView());
        assertThat(evidence.of(Region.PARAMS)).contains("Добавить параметр");
        assertThat(evidence.of(Region.ADVANCED)).contains("С новой страницы");
        assertThat(evidence.of(Region.USER)).contains("Добавить поле");
        assertThat(evidence.of(Region.PAGE)).contains("Формат страницы");
    }

    // === обход ===

    /** Снимок строк интерфейса по разделам: разделы подключаются по выбору вкладки. */
    private record Evidence(Set<String> queries, Set<String> params, Set<String> advanced,
                            Set<String> user, Set<String> page) {

        Set<String> of(Region region) {
            if (region == Region.SHELL) {
                Set<String> union = new LinkedHashSet<>(queries);
                union.addAll(params);
                union.addAll(advanced);
                union.addAll(user);
                union.addAll(page);
                return union;
            }
            return switch (region) {
                case PARAMS -> params;
                case ADVANCED -> advanced;
                case USER -> user;
                case PAGE -> page;
                case SHELL -> throw new IllegalStateException("SHELL обработан выше");
            };
        }
    }

    private static Evidence evidenceOf(Component view) {
        selectTabByIndex(view, 0);
        Set<String> queries = evidenceSet(view);
        selectTabByIndex(view, 1);
        Set<String> params = evidenceSet(view);
        selectTabByIndex(view, 2);
        Set<String> advanced = evidenceSet(view);
        selectUserMode(view);
        Set<String> user = evidenceSet(view);
        selectTabByIndex(view, 3);
        Set<String> page = evidenceSet(view);
        return new Evidence(queries, params, advanced, user, page);
    }

    /** Строки интерфейса дерева компонентов: подписи, тексты, заголовки секций и колонок. */
    private static Set<String> evidenceSet(Component root) {
        Set<String> found = new LinkedHashSet<>();
        descendants(root).forEach(component -> collectEvidence(component, found));
        return found;
    }

    /**
     * Заголовок колонки читается и как текст, и как содержимое: библиотека заворачивает строку
     * заголовка в собственный компонент, поэтому одного {@code getHeaderText()} недостаточно —
     * иначе заголовки колонок молча выпадали бы из книги, а книга «проходила» бы неполной.
     */
    private static void collectEvidence(Component component, Set<String> found) {
        if (component instanceof HasText text) {
            add(found, text.getText());
        }
        if (component instanceof HasLabel labelled) {
            add(found, labelled.getLabel());
        }
        if (component instanceof Details details) {
            add(found, details.getSummaryText());
        }
        if (component instanceof Tab tab) {
            add(found, tab.getLabel());
        }
        if (component instanceof Grid<?> grid) {
            for (Grid.Column<?> column : grid.getColumns()) {
                add(found, column.getHeaderText());
                Component header = column.getHeaderComponent();
                if (header != null && header != component) {
                    descendants(header).forEach(part -> collectEvidence(part, found));
                }
            }
        }
    }

    private static void add(Set<String> found, String text) {
        if (text != null && !text.isBlank()) {
            found.add(text.trim());
        }
    }

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

    // === переходы (обе вьюхи держат вкладки и режим приватными: швами варианта пользоваться нельзя) ===

    /**
     * Выбор вкладки по порядку, а не по подписи: подпись вкладки параметров у варианта отличается,
     * а порядок разделов у обоих экранов одинаков. Шов варианта {@code selectPageTab()} для этого не
     * годится: в нём {@code pageTab} указывает на вкладку «Макет», то есть шов ведёт не на ту страницу.
     */
    private static void selectTabByIndex(Component view, int index) {
        Tabs tabs = (Tabs) readField(view, "tabs");
        tabs.setSelectedTab(tabs.getChildren().filter(Tab.class::isInstance).map(Tab.class::cast)
            .skip(index).findFirst()
            .orElseThrow(() -> new AssertionError("у экрана нет вкладки с номером " + index)));
    }

    /** Простой режим: enum у варианта — своя переходная копия, поэтому значение берётся по имени. */
    private static void selectUserMode(Object view) {
        Object current = invoke(view, "layoutMode");
        Class<?> modeType = current.getClass();
        Object user = java.lang.Enum.valueOf(modeType.asSubclass(java.lang.Enum.class), "USER");
        invoke(view, "setLayoutModeForTest", modeType, user);
    }

    private static int sectionCount(Component view) {
        Tabs tabs = (Tabs) readField(view, "tabs");
        return (int) tabs.getChildren().filter(Tab.class::isInstance).count();
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException absent) {
                type = type.getSuperclass();
            } catch (IllegalAccessException denied) {
                throw new AssertionError("нет доступа к полю " + name, denied);
            }
        }
        throw new AssertionError("у " + target.getClass().getName() + " нет поля " + name);
    }

    private static Object invoke(Object target, String name, Class<?> parameterType, Object argument) {
        try {
            Method method = declaredMethod(target.getClass(), name, parameterType);
            method.setAccessible(true);
            return method.invoke(target, argument);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("не удалось вызвать " + name, ex);
        }
    }

    private static Object invoke(Object target, String name) {
        try {
            Method method = declaredMethod(target.getClass(), name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("не удалось вызвать " + name, ex);
        }
    }

    private static Method declaredMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException absent) {
                current = current.getSuperclass();
            }
        }
        throw new AssertionError("у " + type.getName() + " нет метода " + name);
    }

    // === fixtures ===

    private static Component canonicalView() {
        return new SilentEditorView(catalogWithNoRoots());
    }

    private static QueryMetadataCatalogService catalogWithNoRoots() {
        QueryMetadataCatalogService catalog = mock(QueryMetadataCatalogService.class);
        when(catalog.roots(any())).thenReturn(List.of());
        return catalog;
    }

    /** Тестовый подкласс глушит уведомления: без UI-контекста открывать нотификации нельзя. */
    private static final class SilentEditorView extends ReportEditorView {

        private SilentEditorView(QueryMetadataCatalogService catalog) {
            super(mock(ReportQueryGuard.class), mock(ReportPreviewService.class),
                mock(QueryEditorAnalysisService.class), catalog,
                mock(ReportTemplateService.class), mock(ReportExecutionService.class),
                mock(EntityLookup.class), mock(SelectionFormAssembler.class),
                mock(ReportQueryAssemblyService.class), null);
        }

        @Override
        protected void showNotification(String message) {
        }
    }
}
