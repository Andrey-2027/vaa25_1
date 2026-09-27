package org.ip.views.reportstudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.filtergrid.filter.FilterCondition;
import org.ipro.filtergrid.filter.FilterConditionNode;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterGroup;
import org.ipro.filtergrid.filter.FilterNode;
import org.ipro.filtergrid.filter.FilterOperator;
import org.ipro.filtergrid.filter.FilterTreeJson;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportOrder;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.layout.ReportLayoutOperations;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.6.5: характеризация простого режима макета — слой «кнопка → операция → уведомление владельцу».
 *
 * <p><b>Зачем именно он.</b> Модельный слой покрыт {@code ReportLayoutOperationsTest}, а почти два
 * десятка мутирующих методов простого режима переехали в этот компонент из variant-вьюхи, где у них
 * не было ни одного теста. План D3.6 требует характеризовать их <b>первым</b> подшагом D3.6.5 — пока
 * панель только что перенесена, а не после того, как её поведение забудут.</p>
 *
 * <p><b>Что проверяется.</b> Не устройство модели (его знает модельный набор), а контракт панели:</p>
 *
 * <ul>
 *   <li>каждое действие пользователя меняет модель <b>и</b> уведомляет владельца ровно один раз —
 *       одна правка не может стать двумя вопросами «сохранить?» или нулём;</li>
 *   <li>отклонённое действие (повтор колонки, повтор правила сортировки) не меняет модель и не
 *       уведомляет о правке — иначе отчёт становится «изменённым» на пустом месте;</li>
 *   <li>программное {@code refresh()} владельца не уведомляет, но перечитывает списки, сбрасывает
 *       выбор исчезнувшего поля и пересобирает визуальный отбор при смене схемы;</li>
 *   <li>предупреждение о недоступных настройках снимается только владельцем.</li>
 * </ul>
 *
 * <p><b>Границы харнесса.</b> Действия строк (↑, ↓, «Удалить») живут в компонентах ячеек грида:
 * без UI они не отрисовываются, поэтому эти три метода вызываются напрямую. Кнопки разделов
 * («Добавить поле», «Применить свойства», …) нажимаются как пользователем — через поиск по дереву
 * компонентов, потому что именно их связка с операцией и есть предмет проверки.</p>
 */
class ReportUserLayoutEditorTest {

    @Test
    void addFieldButtonAddsTheColumnAndNotifiesOnce() {
        Fixture fx = new Fixture();

        fx.panel.userFieldCombo().setValue(fx.field("c1"));
        fx.click("Добавить поле");

        assertThat(detailFields(fx.template)).extracting(ReportField::getQueryField).containsExactly("c1");
        assertThat(fx.context.modelChanges).as("одно действие — одно уведомление о правке").isEqualTo(1);
        assertThat(fx.context.notices).as("успешное действие не о чем предупреждать").isEmpty();
    }

    @Test
    void addFieldWithoutSelectionIsNotAnEdit() {
        Fixture fx = new Fixture();

        fx.click("Добавить поле");

        assertThat(detailFields(fx.template)).isEmpty();
        assertThat(fx.context.modelChanges).as("нажатие без выбранного поля ничего не меняет").isZero();
    }

    @Test
    void duplicateFieldIsRejectedWithoutTouchingTheModel() {
        Fixture fx = new Fixture();
        fx.panel.userFieldCombo().setValue(fx.field("c1"));
        fx.click("Добавить поле");

        fx.click("Добавить поле");

        assertThat(detailFields(fx.template)).as("повтор не добавил вторую колонку").hasSize(1);
        assertThat(fx.context.modelChanges)
                .as("отклонённая правка не меняет модель, поэтому не может делать отчёт изменённым")
                .isEqualTo(1);
        assertThat(fx.context.notices).as("пользователю сказали причину отказа").hasSize(1);
    }

    @Test
    void movingAndRemovingFieldsNotifyExactlyOnceEach() throws Exception {
        Fixture fx = new Fixture();
        fx.addField("c1");
        fx.addField("c2");
        int before = fx.context.modelChanges;
        ReportField first = detailFields(fx.template).get(0);

        invoke(fx.panel, "moveUserField", new Class<?>[]{ReportField.class, int.class}, first, 1);
        assertThat(detailFields(fx.template)).extracting(ReportField::getQueryField)
                .as("перестановка колонок").containsExactly("c2", "c1");
        assertThat(fx.context.modelChanges).isEqualTo(before + 1);

        invoke(fx.panel, "removeUserField", new Class<?>[]{ReportField.class}, first);
        assertThat(detailFields(fx.template)).extracting(ReportField::getQueryField).containsExactly("c2");
        assertThat(fx.context.modelChanges).isEqualTo(before + 2);
    }

    @Test
    void applyingPropertiesChangesTheSelectedField() throws Exception {
        Fixture fx = new Fixture();
        fx.addField("c1");
        ReportField field = detailFields(fx.template).get(0);
        int before = fx.context.modelChanges;
        invoke(fx.panel, "selectUserField", new Class<?>[]{ReportField.class}, field);
        control(fx.panel, "userCaption", TextField.class).setValue("Наименование");
        control(fx.panel, "userWidth", IntegerField.class).setValue(120);

        fx.click("Применить свойства");

        assertThat(field.getCaption()).isEqualTo("Наименование");
        assertThat(field.getWidth()).isEqualTo(120);
        assertThat(fx.context.modelChanges)
                .as("заполнение контролов свойствами — не правка, а применение — одна правка")
                .isEqualTo(before + 1);
    }

    @Test
    void groupingIsCreatedThroughTheOwner() {
        Fixture fx = new Fixture();

        control(fx.panel, "userGroupField", ComboBox.class).setValue(fx.field("c1"));
        fx.click("Добавить группировку");

        assertThat(fx.context.groupPairs)
                .as("инвариант пары «заголовок + подвал» принадлежит владельцу, а не панели")
                .containsExactly("c1");
        assertThat(fx.context.modelChanges).isEqualTo(1);
    }

    @Test
    void removingAGroupNotifiesOnce() throws Exception {
        Fixture fx = new Fixture();
        fx.addGroupPairDirectly("c1");
        ReportBand header = fx.template.getBands().stream()
                .filter(band -> band.getKind() == ReportBandKind.GROUP_HEADER).findFirst().orElseThrow();
        int before = fx.context.modelChanges;

        invoke(fx.panel, "removeUserGroup", new Class<?>[]{ReportBand.class}, header);

        assertThat(fx.template.getBands()).extracting(ReportBand::getKind)
                .as("пара групп удалена целиком")
                .containsExactly(ReportBandKind.DETAIL);
        assertThat(fx.context.modelChanges).isEqualTo(before + 1);
    }

    @Test
    void groupTotalRequiresBothTargets() {
        Fixture fx = new Fixture();
        fx.addGroupPairDirectly("c1");

        fx.click("Добавить итог группы");
        assertThat(footerFields(fx.template))
                .as("без выбранной группы и поля итог не создаётся").isEmpty();
        assertThat(fx.context.modelChanges).isZero();

        ReportBand header = fx.template.getBands().stream()
                .filter(band -> band.getKind() == ReportBandKind.GROUP_HEADER).findFirst().orElseThrow();
        control(fx.panel, "userGroupTotalGroup", ComboBox.class).setValue(header);
        control(fx.panel, "userTotalField", ComboBox.class).setValue(fx.field("n1"));
        fx.click("Добавить итог группы");

        assertThat(footerFields(fx.template)).extracting(ReportField::getQueryField).containsExactly("n1");
        assertThat(footerFields(fx.template)).extracting(ReportField::getAggregation)
                .as("панель передаёт запрошенный итог; понижение неагрегируемого — забота модели")
                .containsExactly(ReportFieldAggregation.SUM);
        assertThat(fx.context.modelChanges).isEqualTo(1);
    }

    @Test
    void reportTotalGoesToTheReportFooter() {
        Fixture fx = new Fixture();
        control(fx.panel, "userTotalField", ComboBox.class).setValue(fx.field("c2"));

        fx.click("Добавить общий итог");

        assertThat(fx.template.getBands()).extracting(ReportBand::getKind)
                .contains(ReportBandKind.REPORT_FOOTER);
        assertThat(footerFields(fx.template)).extracting(ReportField::getQueryField).containsExactly("c2");
        assertThat(fx.context.modelChanges).isEqualTo(1);
    }

    @Test
    void totalCanBeRemovedByItsRowAction() throws Exception {
        Fixture fx = new Fixture();
        control(fx.panel, "userTotalField", ComboBox.class).setValue(fx.field("c1"));
        fx.click("Добавить общий итог");
        ReportField total = footerFields(fx.template).get(0);
        int before = fx.context.modelChanges;

        invoke(fx.panel, "removeUserTotal", new Class<?>[]{ReportField.class}, total);

        assertThat(footerFields(fx.template)).isEmpty();
        assertThat(fx.context.modelChanges).isEqualTo(before + 1);
    }

    @Test
    void duplicateSortRuleIsRejectedWithoutTouchingTheModel() {
        Fixture fx = new Fixture();
        control(fx.panel, "userSortField", ComboBox.class).setValue(fx.field("c1"));

        fx.click("Добавить сортировку");
        fx.click("Добавить сортировку");

        assertThat(fx.template.getOrders()).extracting(ReportOrder::getColumnName).containsExactly("c1");
        assertThat(fx.context.modelChanges).isEqualTo(1);
        assertThat(fx.context.notices).hasSize(1);
    }

    @Test
    void movingAndRemovingSortRulesNotifyExactlyOnceEach() throws Exception {
        Fixture fx = new Fixture();
        control(fx.panel, "userSortField", ComboBox.class).setValue(fx.field("c1"));
        fx.click("Добавить сортировку");
        control(fx.panel, "userSortField", ComboBox.class).setValue(fx.field("c2"));
        fx.click("Добавить сортировку");
        int before = fx.context.modelChanges;
        ReportOrder first = fx.template.getOrders().get(0);

        invoke(fx.panel, "moveUserSort", new Class<?>[]{ReportOrder.class, int.class}, first, 1);
        assertThat(fx.template.getOrders()).extracting(ReportOrder::getColumnName)
                .as("порядок правил").containsExactly("c2", "c1");
        assertThat(fx.context.modelChanges).isEqualTo(before + 1);

        invoke(fx.panel, "removeUserSort", new Class<?>[]{ReportOrder.class}, first);
        assertThat(fx.template.getOrders()).extracting(ReportOrder::getColumnName).containsExactly("c2");
        assertThat(fx.context.modelChanges).isEqualTo(before + 2);
    }

    @Test
    void clearingAllConditionsWritesNullAndNotifiesOnce() throws Exception {
        Fixture fx = new Fixture();
        fx.setVisualFilter(FilterGroup.and(FilterConditionNode.of(
                FilterCondition.inList("c1", FilterDataType.TEXT, List.of("a")))));
        int before = fx.context.modelChanges;

        invoke(fx.panel, "onUserFilterChanged", new Class<?>[]{FilterNode.class}, (Object) null);

        assertThat(fx.context.visualFilterJson)
                .as("снять все условия можно: в шаблон уходит пустой отбор, а не прежний")
                .isNull();
        assertThat(fx.context.modelChanges).isEqualTo(before + 1);
    }

    @Test
    void validConditionIsWrittenAsIs() throws Exception {
        Fixture fx = new Fixture();
        FilterNode condition = FilterGroup.and(FilterConditionNode.of(
                FilterCondition.inList("c1", FilterDataType.TEXT, List.of("a", "b"))));

        invoke(fx.panel, "onUserFilterChanged", new Class<?>[]{FilterNode.class}, condition);

        String json = fx.context.visualFilterJson;
        assertThat(json).as("условие записано в модель").isNotNull().contains("c1");
        FilterNode restored = FilterTreeJson.read(new ObjectMapper().readTree(json));
        assertThat(FilterTreeJson.write(restored).toString())
                .as("панель пишет отбор как есть, а не пересказывает его своими словами")
                .isEqualTo(json);
        assertThat(fx.context.modelChanges).isEqualTo(1);
    }

    /**
     * Невалидный отбор в модель не попадает. Реальный поток такой: пользователь правит дерево в
     * редакторе, редактор вызывает владельца с корнем дерева, и guard панели видит ровно то же
     * дерево через {@code validationErrors()}. Условие с пустым значением валидатор помечает
     * («укажите значение») — панель обязана отказать: сохранённый отчёт сломался бы уже при
     * выполнении запроса.
     */
    @Test
    void invalidConditionIsNotWrittenToTheModel() throws Exception {
        Fixture fx = new Fixture();
        FilterNode invalid = FilterGroup.and(FilterConditionNode.of(
                new FilterCondition("c1", FilterOperator.EQ, "", null, FilterDataType.TEXT)));

        // Невалидное дерево живёт в редакторе — как после правки пользователя.
        fx.panel.userFilterEditor().setValue(invalid);

        invoke(fx.panel, "onUserFilterChanged", new Class<?>[]{FilterNode.class}, invalid);

        assertThat(fx.context.visualFilterJson)
                .as("невалидный отбор не переносится в модель")
                .isNull();
        assertThat(fx.context.modelChanges)
                .as("сохранять нечего — отчёт не становится изменённым")
                .isZero();
    }

    @Test
    void schemaChangeRebuildsTheFilterEditorBecauseItsResolverCapturesTheSchema() throws Exception {
        Fixture fx = new Fixture();
        Object editorBefore = fx.panel.userFilterEditor();

        fx.context.schema = List.of(QueryField.scalar("c3", String.class));
        fx.panel.refresh();

        assertThat(fx.context.modelChanges)
                .as("программное обновление владельца не является правкой отчёта")
                .isZero();
        assertThat(fx.panel.userFilterEditor())
                .as("резолвер библиотеки захватывает схему в конструкторе — редактор отбора пересобирается")
                .isNotSameAs(editorBefore);
    }

    @Test
    void refreshDropsTheSelectionOfAFieldThatLeftTheLayout() throws Exception {
        Fixture fx = new Fixture();
        fx.addField("c1");
        ReportField field = detailFields(fx.template).get(0);
        invoke(fx.panel, "selectUserField", new Class<?>[]{ReportField.class}, field);

        ReportLayoutOperations.removeField(field.getBand(), field);
        fx.panel.refresh();

        assertThat(fx.panel.selectedUserField())
                .as("иначе свойства правились бы у поля, которого в отчёте уже нет")
                .isNull();
    }

    @Test
    void noticeIsShownAndClearedOnlyByTheOwner() {
        Fixture fx = new Fixture();
        assertThat(fx.panel.hasOrphanedChangesNotice()).isFalse();

        fx.panel.showOrphanedChanges();
        assertThat(fx.panel.hasOrphanedChangesNotice()).isTrue();

        fx.panel.refresh();
        assertThat(fx.panel.hasOrphanedChangesNotice())
                .as("перечитывание списков не снимает предупреждение: это решение владельца")
                .isTrue();

        fx.panel.clearOrphanedChanges();
        assertThat(fx.panel.hasOrphanedChangesNotice()).isFalse();
    }

    // === fixtures ===

    /** Владелец панели в миниатюре: модель, схема, отбор и счётчики уведомлений. */
    private static final class FakeContext implements ReportUserLayoutEditor.Context {

        private ReportTemplate template;
        private List<QueryField> schema = List.of();
        private String visualFilterJson;
        private int modelChanges;
        private final List<String> notices = new ArrayList<>();
        private final List<String> groupPairs = new ArrayList<>();

        @Override
        public ReportTemplate template() {
            return template;
        }

        @Override
        public List<QueryField> schemaFields() {
            return schema;
        }

        @Override
        public String visualFilterJson() {
            return visualFilterJson;
        }

        @Override
        public void visualFilterJson(String json) {
            visualFilterJson = json;
        }

        @Override
        public void addGroupPair(String fieldName) {
            groupPairs.add(fieldName);
            // Владелец делает это через редактор структуры; здесь тот же вызов операции.
            ReportLayoutOperations.addGroupPair(template, fieldName, null);
        }

        @Override
        public void modelChanged() {
            modelChanges++;
        }

        @Override
        public void notify(String message) {
            notices.add(message);
        }
    }

    private static final class Fixture {

        private final FakeContext context = new FakeContext();
        private final ReportTemplate template = new ReportTemplate();
        private final ReportUserLayoutEditor panel;

        private Fixture() {
            template.setJpql("select 1");
            ReportBand detail = new ReportBand();
            detail.setKind(ReportBandKind.DETAIL);
            detail.setPosition(0);
            template.addBand(detail);
            context.template = template;
            context.schema = List.of(QueryField.scalar("c1", String.class), QueryField.scalar("c2", String.class),
                    QueryField.scalar("n1", Integer.class));
            panel = new ReportUserLayoutEditor(context);
        }

        private QueryField field(String alias) {
            return context.schema.stream().filter(field -> field.name().equals(alias)).findFirst().orElseThrow();
        }

        /** Добавление колонки как пользователь: выбор в списке и нажатие кнопки. */
        private void addField(String alias) {
            panel.userFieldCombo().setValue(field(alias));
            click("Добавить поле");
        }

        private void addGroupPairDirectly(String alias) {
            ReportLayoutOperations.addGroupPair(template, alias, null);
        }

        private void setVisualFilter(FilterNode node) {
            context.visualFilterJson = FilterTreeJson.write(node).toString();
        }

        /** Нажатие кнопки раздела: ищем по подписи в дереве компонентов панели. */
        private void click(String caption) {
            for (Component component : descendants(panel)) {
                if (component instanceof Button button && caption.equals(button.getText())) {
                    button.click();
                    return;
                }
            }
            throw new AssertionError("нет кнопки «" + caption + "»");
        }
    }

    /** Поля колонок DETAIL-бэнда — то, что панель показывает в списке полей. */
    private static List<ReportField> detailFields(ReportTemplate template) {
        ReportBand detail = ReportLayoutOperations.findBand(template, ReportBandKind.DETAIL);
        return detail == null ? List.of() : detail.getFields();
    }

    /** Поля подвалов: и подвал группы, и подвал отчёта. */
    private static List<ReportField> footerFields(ReportTemplate template) {
        return template.getBands().stream()
                .filter(band -> band.getKind() == ReportBandKind.REPORT_FOOTER
                        || band.getKind() == ReportBandKind.GROUP_FOOTER)
                .flatMap(band -> band.getFields().stream())
                .toList();
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

    /** Контрол панели — приватное поле: читаем отражением, как это делают наборы редакторов. */
    @SuppressWarnings("unchecked")
    private static <C> C control(ReportUserLayoutEditor panel, String name, Class<C> type) {
        try {
            java.lang.reflect.Field field = ReportUserLayoutEditor.class.getDeclaredField(name);
            field.setAccessible(true);
            return (C) field.get(panel);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("не удалось прочитать контрол " + name, error);
        }
    }

    /** Действие строки грида: сама ячейка без UI не отрисовывается, поэтому зовём метод напрямую. */
    private static void invoke(ReportUserLayoutEditor panel, String name, Class<?>[] types, Object... args) {
        try {
            Method method = ReportUserLayoutEditor.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(panel, args);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("не удалось выполнить " + name, error);
        }
    }
}
