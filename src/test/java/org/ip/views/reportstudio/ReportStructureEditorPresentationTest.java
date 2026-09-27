package org.ip.views.reportstudio;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.ListDataProvider;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportGroupHeaderLayout;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import com.vaadin.flow.component.combobox.ComboBox;
import org.ipro.reportstudio.dom.ReportFieldKind;
import org.ipro.reportstudio.layout.ReportLayoutOperations;

class ReportStructureEditorPresentationTest {

    @Test
    void initializesDetailBand() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        assertThat(template.getBands()).extracting(ReportBand::getKind).containsExactly(ReportBandKind.DETAIL);
    }

    @Test
    void dndZone1_addColumnInsideGroup() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("discount", String.class), QueryField.scalar("code", String.class)));
        editor.addGroupPair("item");
        ReportBand detail = template.getBands().stream().filter(b -> b.getKind() == ReportBandKind.DETAIL).findFirst().orElseThrow();
        detail.addField(col("code"));
        int before = detail.getFields().size();
        editor.handleDropColumn("discount");
        assertThat(detail.getFields()).hasSize(before + 1);
        assertThat(detail.getFields().get(detail.getFields().size() - 1).getQueryField()).isEqualTo("discount");
    }

    @Test
    void dndZone1_duplicateColumnRejected() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        editor.handleDropColumn("code");
        int sizeAfterFirst = template.getBands().stream().filter(b -> b.getKind() == ReportBandKind.DETAIL).findFirst().get().getFields().size();
        editor.handleDropColumn("code");
        int sizeAfterSecond = template.getBands().stream().filter(b -> b.getKind() == ReportBandKind.DETAIL).findFirst().get().getFields().size();
        assertThat(sizeAfterSecond).isEqualTo(sizeAfterFirst);
    }

    @Test
    void addColumnRefusesDuplicateThroughTheButtonPath() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        ReportBand detail = template.getBands().stream()
                .filter(b -> b.getKind() == ReportBandKind.DETAIL).findFirst().orElseThrow();
        editor.selectBand(detail);

        editor.addColumn("code");
        editor.addColumn("code");

        assertThat(detail.getFields())
                .as("правило «одно поле — одна колонка» принадлежит модели: повтор — подсказка, а не вторая колонка")
                .hasSize(1);
    }

    // ------------------------------------------------------------------------------------------
    // Характеризация к слиянию редакторов (D3.6.4, подшаг 4.2).
    //
    // Три приспособления structured'а не имели ни одного теста: селектор бэндов, панель доступных
    // полей и синхронизация блока «нет данных». Именно они переносятся в канонический редактор,
    // поэтому контракт фиксируется ДО переноса: перенос непроверяемого UI не доказывает ничего,
    // кроме того, что код скомпилировался. Приватные члены читаются отражением — расширять
    // production-видимость ради тестов здесь не нужно.

    @Test
    void clearingTheBandSelectionClearsTheFieldList() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        ReportBand detail = template.getBands().stream()
                .filter(band -> band.getKind() == ReportBandKind.DETAIL).findFirst().orElseThrow();
        editor.selectBand(detail);
        editor.addColumn("code");
        assertThat(fieldGridItems(editor)).as("к этому месту список полей не пуст").isNotEmpty();

        editor.selectBand(null);

        assertThat(fieldGridItems(editor))
                .as("без выбранного бэнда список полей не должен показывать чужие поля")
                .isEmpty();
    }

    @Test
    void availableFieldsListEverySchemaFieldAndTheFilterNarrowsThem() {
        ReportStructureEditor editor = newEditor();
        editor.setTemplate(new ReportTemplate());
        editor.updateSchema(List.of(QueryField.scalar("code", String.class),
                QueryField.scalar("amount", java.math.BigDecimal.class),
                QueryField.scalar("city", String.class)));

        assertThat(availableRows(editor)).containsExactly("code", "amount", "city");

        filterField(editor).setValue("COD");

        assertThat(availableRows(editor))
                .as("фильтр панели регистронезависим — иначе он бесполезен при именах вроде clientName")
                .containsExactly("code");
    }

    @Test
    void availableFieldsFollowSchemaUpdates() {
        ReportStructureEditor editor = newEditor();
        editor.setTemplate(new ReportTemplate());
        editor.updateSchema(List.of(QueryField.scalar("code", String.class),
                QueryField.scalar("amount", java.math.BigDecimal.class)));
        assertThat(availableRows(editor)).hasSize(2);

        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));

        assertThat(availableRows(editor))
                .as("панель обязана следовать за схемой: исчезнувшее поле нельзя тащить в отчёт")
                .containsExactly("code");
    }

    @Test
    void enablingNoDataCreatesBandWithTextAndDisablingRemovesIt() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);

        noDataText(editor).setValue("Нет данных");
        noDataToggle(editor).setValue(true);

        assertThat(template.getBands())
                .filteredOn(band -> band.getKind() == ReportBandKind.NO_DATA)
                .as("включение флага создаёт ровно один NO_DATA-бэнд с текстом из поля")
                .singleElement()
                .satisfies(band -> assertThat(band.getFields()).singleElement().satisfies(field -> {
                    assertThat(field.isText()).isTrue();
                    assertThat(field.getText()).isEqualTo("Нет данных");
                }));

        noDataToggle(editor).setValue(false);

        assertThat(template.getBands())
                .as("выключение флага убирает бэнд, а не оставляет пустой NO_DATA в отчёте")
                .noneMatch(band -> band.getKind() == ReportBandKind.NO_DATA);
    }

    @Test
    void changingNoDataTextUpdatesTheExistingBlockInsteadOfAddingASecond() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        noDataToggle(editor).setValue(true);

        noDataText(editor).setValue("Первый");
        noDataText(editor).setValue("Второй");

        ReportBand noData = template.getBands().stream()
                .filter(band -> band.getKind() == ReportBandKind.NO_DATA)
                .findFirst().orElseThrow();
        assertThat(noData.getFields())
                .as("правка текста — это изменение блока, а не добавление второго")
                .singleElement()
                .satisfies(field -> assertThat(field.getText()).isEqualTo("Второй"));
    }

    @Test
    void noDataTextWhileDisabledDoesNotTouchTheTemplate() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);

        noDataText(editor).setValue("Игнор");

        assertThat(template.getBands())
                .as("пока флаг выключен, текст в отчёт не попадает")
                .noneMatch(band -> band.getKind() == ReportBandKind.NO_DATA);
    }

    // ------------------------------------------------------------------------------------------
    // Два дефекта, найденные паритетным харнессом (D3.6.4): каждый из них был виден только
    // сравнением двух редакторов, поэтому закрепляются здесь — в наборе владельца.

    @Test
    void loadingATemplateIsNotAnEdit() {
        ReportStructureEditor editor = newEditor();
        int[] changes = {0};
        editor.setChangeListener(() -> changes[0]++);
        ReportTemplate template = new ReportTemplate();

        editor.setTemplate(template);

        assertThat(changes[0])
                .as("загрузка шаблона — не правка пользователя: до D3.6.4 пять контролов страницы"
                        + " заполнялись вне guard и давали три ложных «отчёт изменён» на загрузке")
                .isZero();
        Checkbox gridEnabled = field(editor, "gridEnabled");
        assertThat(gridEnabled.getValue())
                .as("при этом контрол обязан показать значение шаблона, а не своё исходное")
                .isEqualTo(template.isGridEnabled());
    }

    /**
     * Обратная сторона того же шва: загрузка не только не сообщает о правке, но и не меняет
     * загруженный шаблон. До D3.6.4 слушатели панели страницы писали в модель при программном
     * выставлении контролов, а слушатель блока «нет данных» мог создать или убрать бэнд
     * прямо в момент открытия отчёта — то есть открытие меняло содержимое.
     */
    @Test
    void loadingATemplateDoesNotChangeItsComposition() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = richTemplate();
        String before = describe(template);

        editor.setTemplate(template);

        assertThat(describe(template))
                .as("открытие шаблона не является его правкой: состав обязан совпасть до и после")
                .isEqualTo(before);
        // Вторая половина того же утверждения: программное заполнение не должно записывать в модель
        // «нормализованные» значения. Контрол показывает значение по умолчанию, а шаблон при этом
        // обязан остаться с незаполненным полем — нормализация происходит при рендере, не при загрузке.
        assertThat(template.getPageSize())
                .as("загрузка не подставляет A4 вместо незаполненного размера страницы")
                .isNull();
        assertThat(template.getName())
                .as("загрузка не пишет в шаблон значение контрола названия")
                .isNull();
    }

    /**
     * Вложенная синхронизация: setTemplate входит в sync, внутри вызывает selectBand, а тот —
     * selectField и fillPalette. Счётчик глубины обязан удержать guard до выхода из внешнего
     * блока: одиночный boolean снимался бы внутренним finally и наружу потекли бы уведомления.
     */
    @Test
    void deeplyNestedSyncDoesNotLeakNotifications() {
        ReportStructureEditor editor = newEditor();
        int[] changes = {0};
        editor.setChangeListener(() -> changes[0]++);
        ReportTemplate template = richTemplate();

        editor.setTemplate(template);
        ReportBand detail = template.getBands().stream()
                .filter(band -> band.getKind() == ReportBandKind.DETAIL).findFirst().orElseThrow();
        editor.selectField(detail.getFields().isEmpty() ? null : detail.getFields().get(0));

        assertThat(changes[0])
                .as("вложенная программная синхронизация не считается правкой на любой глубине")
                .isZero();
    }

    /**
     * Guard'ы, которые держали поведение, но не имели покрытия: их снятие не роняло ни одного
     * набора, потому что контрол стартует пустым и {@code setValue} с тем же значением события
     * не даёт. Сценарий «два шаблона подряд» — недостающее звено: контрол несёт значение
     * первого отчёта, и программная установка второго становится отличимой от исходного
     * состояния. Оба теста падают, если guard снят.
     */
    @Test
    void loadingASecondTemplateDoesNotWriteThePreviousTitleIntoIt() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate first = new ReportTemplate();
        first.setName("Первый отчёт");
        editor.setTemplate(first);

        ReportTemplate second = new ReportTemplate();
        editor.setTemplate(second);

        assertThat(second.getName())
                .as("без guard'а контрол названия нёс значение первого отчёта, а setValue давал "
                        + "событие — загрузка записала бы в новый шаблон пустое имя вместо null")
                .isNull();
    }

    @Test
    void loadingASecondTemplateDoesNotFillItsEmptyNoDataBlockWithThePreviousText() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate first = new ReportTemplate();
        ReportLayoutOperations.addTextField(
                ReportLayoutOperations.createBand(first, ReportBandKind.NO_DATA, null), "Первый текст");
        editor.setTemplate(first);

        ReportTemplate second = new ReportTemplate();
        ReportBand empty = ReportLayoutOperations.createBand(second, ReportBandKind.NO_DATA, null);
        editor.setTemplate(second);

        assertThat(empty.getFields())
                .as("без guard'а загрузка дозаполняла пустой блок «нет данных» унаследованным "
                        + "текстом контрола — то есть создавала поле в момент открытия отчёта")
                .isEmpty();
    }

    /**
     * Несущий guard комбо родителя (4.6). Его пробовали снять как дублирующий: заполнение формы
     * действительно показывает <b>собственного</b> родителя бэнда, а повторное применение той же
     * связи идемпотентно. Но программный вызов доходит до {@code refreshBandParentCandidates()}
     * → {@code setItems}, а смена data provider сбрасывает значение комбо — вложенная группа
     * показывала «без родителя». Все поведенческие наборы такую потерю пропускали: этот тест
     * держит <b>отображение</b>, а не состав шаблона.
     */
    @Test
    void theParentComboAlwaysShowsTheBandsOwnCurrentParent() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("outer");
        editor.addGroupPair("inner");
        editor.applyGroupingValues(groupHeader(template, "inner"), "inner",
                groupHeader(template, "outer"), false, message -> { });

        editor.selectBand(groupHeader(template, "outer"));
        assertThat(parentCombo(editor).getValue()).as("верхний уровень: родителя нет").isNull();
        editor.selectBand(groupHeader(template, "inner"));
        assertThat(parentCombo(editor).getValue())
                .as("вложенная группа показывает объёмлющую")
                .isSameAs(groupHeader(template, "outer"));
        editor.selectBand(groupFooter(template, "inner"));
        assertThat(parentCombo(editor).getValue())
                .as("подвал показывает объёмлющую группу своей пары — после F10 это её же заголовок")
                .isSameAs(groupHeader(template, "inner"));
    }

    @Test
    void reparentingToTheSameParentChangesNothing() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("outer");
        editor.addGroupPair("inner");
        ReportBand inner = groupHeader(template, "inner");
        ReportBand outer = groupHeader(template, "outer");
        editor.applyGroupingValues(inner, "inner", outer, false, message -> { });
        String before = describe(template);

        assertThat(editor.reparentGroup(inner, outer))
                .as("повторное применение той же связи — не отказ и не правка")
                .isTrue();
        assertThat(describe(template)).isEqualTo(before);
    }

    @Test
    void selectingAFieldShowsItsOwnKindInThePalette() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("code", String.class)));
        ReportBand detail = detailBand(template);
        editor.selectBand(detail);
        ReportField column = col("code");
        column.setKind(ReportFieldKind.COLUMN);
        detail.addField(column);

        editor.selectField(column);

        assertThat(kindCombo(editor).getValue())
                .as("программное заполнение палитры выставляет вид самого поля — этим равенством "
                        + "и снята надобность в отдельном guard'е: программный вызов отсекает "
                        + "условие «вид совпадает с текущим», а не флаг синхронизации")
                .isEqualTo(column.kindOrDefault());
    }


    @Test
    void reconcileCleanupAlsoDropsSortingRulesOfGoneColumns() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.updateSchema(List.of(QueryField.scalar("kept", String.class)));
        editor.selectBand(template.getBands().stream()
                .filter(band -> band.getKind() == ReportBandKind.DETAIL).findFirst().orElseThrow());
        editor.addSort("kept");
        editor.addGroupPair("kept");
        editor.updateSchema(List.of(QueryField.scalar("other", String.class)));

        editor.removeMissingFields(editor.lastReconcile());

        assertThat(template.getOrders())
                .as("правило сортировки исчезнувшей колонки уходит вместе с ней: иначе отчёт"
                        + " падает уже при выполнении запроса (алиас проверяется в рантайме)")
                .isEmpty();
        assertThat(template.getBands())
                .allSatisfy(band -> assertThat(band.getGroupField()).isNull());
    }

    // ------------------------------------------------------------------ отражение к приватным членам

    /** Шаблон со всеми приспособлениями сразу: группа, колонка, сортировка, no-data, параметры страницы. */
    private static ReportTemplate richTemplate() {
        ReportTemplate template = new ReportTemplate();
        template.setGridEnabled(true);
        template.setStripeRows(true);
        template.setBaseFontSize(12);
        // DETAIL присутствует: сохранённый отчёт без него не собирается, и редактор добавляет его
        // при загрузке — это единственное изменение, которое допускается, поэтому в фикстуре он есть.
        ReportBand detail = new ReportBand();
        detail.setKind(ReportBandKind.DETAIL);
        detail.setPosition(0);
        ReportField column = new ReportField();
        column.setKind(org.ipro.reportstudio.dom.ReportFieldKind.COLUMN);
        column.setQueryField("code");
        column.setPosition(0);
        detail.addField(column);
        template.addBand(detail);
        ReportBand noData = new ReportBand();
        noData.setKind(ReportBandKind.NO_DATA);
        noData.setPosition(1);
        ReportField text = new ReportField();
        text.setKind(org.ipro.reportstudio.dom.ReportFieldKind.TEXT);
        text.setText("Нет данных");
        noData.addField(text);
        template.addBand(noData);
        org.ipro.reportstudio.dom.ReportOrder order = new org.ipro.reportstudio.dom.ReportOrder();
        order.setColumnName("code");
        order.setPosition(0);
        template.addOrder(order);
        return template;
    }

    /** Компактное описание состава: бэнды с полями и правила сортировки. */
    private static String describe(ReportTemplate template) {
        StringBuilder sb = new StringBuilder();
        for (ReportBand band : template.getBands()) {
            sb.append(band.getKind()).append('=').append(band.getGroupField()).append(';');
            for (ReportField field : band.getFields()) {
                sb.append(field.kindOrDefault()).append(':').append(field.getQueryField())
                        .append(':').append(field.getText()).append('|');
            }
            sb.append('\n');
        }
        template.getOrders().forEach(order -> sb.append("order:").append(order.getColumnName())
                .append('\n'));
        return sb.toString();
    }

    private static ReportBand selectedBand(ReportStructureEditor editor) {
        return field(editor, "selectedBand");
    }

    private static List<ReportField> fieldGridItems(ReportStructureEditor editor) {
        return new ArrayList<>(fieldGridDataProvider(editor).getItems());
    }

    @SuppressWarnings("unchecked")
    private static ListDataProvider<ReportField> fieldGridDataProvider(ReportStructureEditor editor) {
        return (ListDataProvider<ReportField>) grid(editor, "fieldsGrid").getDataProvider();
    }

    private static TextField filterField(ReportStructureEditor editor) {
        return field(editor, "availableFilter");
    }

    private static Checkbox noDataToggle(ReportStructureEditor editor) {
        return field(editor, "noDataEnabled");
    }

    private static TextField noDataText(ReportStructureEditor editor) {
        return field(editor, "noDataText");
    }

    /** Подписи строк панели «Доступные поля» в порядке отрисовки. */
    private static List<String> availableRows(ReportStructureEditor editor) {
        Component list = field(editor, "availableList");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < ((com.vaadin.flow.component.HasComponents) list).getComponentCount(); i++) {
            Component row = ((com.vaadin.flow.component.HasComponents) list).getComponentAt(i);
            Component cell = ((com.vaadin.flow.component.HasComponents) row).getComponentAt(0);
            names.add(((Span) cell).getText());
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        try {
            Field f = ReportStructureEditor.class.getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("нет приватного поля " + name, e);
        }
    }

    private static Grid<?> grid(Object editor, String name) {
        return field(editor, name);
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method m = ReportStructureEditor.class.getDeclaredMethod(name, types);
            m.setAccessible(true);
            m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("нет приватного метода " + name, e);
        }
    }

    @Test
    void dndZone2_betweenGroupsCreatesSibling() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("region");
        editor.addGroupPair("item");
        List<ReportBand> before = List.copyOf(template.getBands());
        editor.handleDropBetween("warehouse", before, 1);
        assertThat(template.getBands()).anySatisfy(b -> assertThat(b.getGroupField()).isEqualTo("warehouse"));
        ReportBand whHeader = groupHeader(template, "warehouse");
        assertThat(whHeader.getParent()).isNull();
    }

    @Test
    void dndZone2_betweenNestedGroupsKeepsSameParent() {
        // Регрессия: раньше handleDropBetween всегда ставил parent=null,
        // независимо от того, между какими бэндами реально произошёл дроп.
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("region");
        ReportBand region = groupHeader(template, "region");
        editor.handleDropNestedGroup("item", region);
        ReportBand item = groupHeader(template, "item");
        assertThat(item.getParent()).isSameAs(region);

        List<ReportBand> ordered = template.getBands().stream()
                .sorted(java.util.Comparator.comparingInt(ReportBand::getPosition))
                .toList();
        // Дроп сразу после ЗАКРЫВАЮЩЕГО GROUP_FOOTER группы "item" (а не после
        // её заголовка) — это точка "рядом с item, всё ещё внутри region";
        // дроп сразу после заголовка означал бы "внутрь item", другой сценарий.
        ReportBand itemFooter = ordered.stream()
                .filter(b -> b.getKind() == ReportBandKind.GROUP_FOOTER && "item".equals(b.getGroupField()))
                .findFirst().orElseThrow();
        int dropIndex = ordered.indexOf(itemFooter) + 1;
        editor.handleDropBetween("warehouse", ordered, dropIndex);

        ReportBand warehouse = groupHeader(template, "warehouse");
        assertThat(warehouse.getParent()).isSameAs(region);
        ReportBand itemAfter = groupHeader(template, "item");
        assertThat(itemAfter.getPosition()).isLessThan(warehouse.getPosition());
    }

    @Test
    void dndZone3_nestedGroupMovesChildren() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("region");
        editor.addGroupPair("item");
        ReportBand region = groupHeader(template, "region");
        ReportBand item = groupHeader(template, "item");
        editor.applyGroupingValues(item, "item", region, false, null, null, m -> {});
        assertThat(item.getParent()).isSameAs(region);
        editor.handleDropNestedGroup("warehouse", region);
        ReportBand warehouse = groupHeader(template, "warehouse");
        assertThat(warehouse.getParent()).isSameAs(region);
        assertThat(item.getParent()).isSameAs(warehouse);
    }

    @Test
    void deletingNestedGroupPromotesChildrenAndKeepsTemplateConsistent() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("outer");
        ReportBand outer = groupHeader(template, "outer");
        editor.handleDropNestedGroup("inner", outer);
        ReportBand inner = groupHeader(template, "inner");
        editor.handleDropNestedGroup("leaf", inner);
        ReportBand leaf = groupHeader(template, "leaf");
        assertThat(leaf.getParent()).isSameAs(inner);

        editor.selectBand(inner);
        invoke(editor, "removeSelectedBand", new Class<?>[0]);

        assertThat(leaf.getParent()).isSameAs(outer);
        assertThat(template.getBands()).noneMatch(b -> "inner".equals(b.getGroupField()));
    }

    /**
     * Вторая половина расхождения D3.6.4: structured-редактор удаляет бэнд через операции модели
     * ({@code ReportLayoutOperations.removeBand -> renumberGroupPositions}) и потому позиции
     * перенумеровывает, а канонический — нет (см. ReportStructureEditorTest
     * {@code removingAGroupBandRemovesTheWholePair}). При слиянии выигрывает эта сторона.
     */
    @Test
    void removingAGroupPairRenumbersPositions() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("alpha");
        editor.addGroupPair("beta");
        editor.selectBand(groupHeader(template, "alpha"));

        invoke(editor, "removeSelectedBand", new Class<?>[0]);

        assertThat(template.getBands()).extracting(ReportBand::getGroupField)
                .containsExactly(null, "beta", "beta");
        assertThat(template.getBands()).extracting(ReportBand::getPosition)
                .as("позиции перенумеровываются операциями модели")
                .containsExactly(0, 1, 2);
    }

    @Test
    void groupHeaderSettingsAreCopiedToFooterPair() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("region");
        ReportBand header = groupHeader(template, "region");
        editor.applyGroupingValues(header, "region", null, false, 140, ReportGroupHeaderLayout.TITLE_AND_VALUE, null);
        ReportBand footer = template.getBands().stream()
                .filter(b -> b.getKind() == ReportBandKind.GROUP_FOOTER)
                .findFirst().orElseThrow();
        assertThat(header.getTitleWidth()).isEqualTo(140);
        assertThat(header.getHeaderLayout()).isEqualTo(ReportGroupHeaderLayout.TITLE_AND_VALUE);
        assertThat(footer.getTitleWidth()).isNull();
    }

    @Test
    void dndNestedDuplicateAncestorRejected() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("region");
        ReportBand region = groupHeader(template, "region");
        int before = template.getBands().size();
        editor.handleDropNestedGroup("region", region);
        assertThat(template.getBands()).hasSize(before);
    }

    @Test
    void reparentViaComboUsesSameValidationAsDnd() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("outer");
        editor.addGroupPair("inner");
        ReportBand outer = groupHeader(template, "outer");
        ReportBand inner = groupHeader(template, "inner");
        boolean ok = editor.reparentGroup(inner, outer);
        assertThat(ok).isTrue();
        assertThat(inner.getParent()).isSameAs(outer);
        boolean cycle = editor.reparentGroup(outer, inner);
        assertThat(cycle).isFalse();
        assertThat(outer.getParent()).isNull();
    }

    @Test
    void toggleSortAndStartNewPagePills() {
        ReportStructureEditor editor = newEditor();
        ReportTemplate template = new ReportTemplate();
        editor.setTemplate(template);
        editor.addGroupPair("region");
        ReportBand region = groupHeader(template, "region");
        region.setGroupField("region");
        assertThat(region.isStartNewPage()).isFalse();
        editor.handleDropNestedGroup("item", region);
    }

    private static ReportStructureEditor newEditor() {
        return new ReportStructureEditor();
    }

    private static ReportField col(String qf) {
        ReportField f = new ReportField();
        f.setQueryField(qf);
        return f;
    }

    private static ReportBand groupHeader(ReportTemplate t, String field) {
        return t.getBands().stream().filter(b -> b.getKind() == ReportBandKind.GROUP_HEADER && field.equals(b.getGroupField())).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ReportBand> parentCombo(ReportStructureEditor editor) {
        return field(editor, "groupParent");
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<ReportFieldKind> kindCombo(ReportStructureEditor editor) {
        return field(editor, "paletteKind");
    }

    private static ReportBand groupFooter(ReportTemplate t, String field) {
        return t.getBands().stream()
                .filter(b -> b.getKind() == ReportBandKind.GROUP_FOOTER && field.equals(b.getGroupField()))
                .findFirst().orElseThrow();
    }

    private static ReportBand detailBand(ReportTemplate t) {
        return t.getBands().stream().filter(b -> b.getKind() == ReportBandKind.DETAIL)
                .findFirst().orElseThrow();
    }
}
