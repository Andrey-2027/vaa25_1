package org.ip.views.reportstudio;

import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Швы свойств поля вне раскладки уведомляют владельца так же, как палитра.
 *
 * <p>Что было. У {@code captionCell}/{@code widthCell}/… два поведения: для <b>выбранного</b>
 * поля они возвращают контрол палитры (та идёт через {@code applyToPalette} →
 * {@code afterFieldEdit} → уведомление), а для <b>любого другого</b> поля создают отдельный
 * контрол, слушатель которого писал в модель напрямую: значение менялось, а владелец об этом не
 * узнавал. Продуктовых вызовов у этих швов нет — они обслуживают тесты, — но именно поэтому
 * дефект и жил: тест, правящий свойство через шов, выглядел «тихой» правкой, тогда как тот же
 * пользовательский путь через палитру считался изменением.</p>
 *
 * <p>Проверяется ровно то, что ломалось: правка через шов поля, которое <b>не</b> выбрано, меняет
 * модель и порождает ровно одно уведомление.</p>
 */
class ReportStructureEditorPropertySeamTest {

    @Test
    void editingAPropertyOfAnUnselectedFieldNotifiesTheOwner() {
        ReportTemplate template = new ReportTemplate();
        ReportStructureEditor editor = new ReportStructureEditor();
        editor.setTemplate(template);
        editor.updateSchema(List.of(
            QueryField.scalar("code", String.class),
            QueryField.scalar("amount", java.math.BigDecimal.class)));
        editor.addColumn("code");
        editor.addColumn("amount");

        List<ReportField> fields = template.getBands().get(0).getFields();
        ReportField selected = selectedField(editor);
        ReportField other = fields.stream()
            .filter(field -> field != selected)
            .findFirst()
            .orElseThrow();

        AtomicInteger notifications = new AtomicInteger();
        editor.setChangeListener(notifications::incrementAndGet);

        editor.captionCell(other).setValue("Код номенклатуры");

        assertThat(other.getCaption())
            .as("шов обязан менять ту же модель, что и палитра")
            .isEqualTo("Код номенклатуры");
        assertThat(notifications.get())
            .as("правка через шов вне раскладки — такое же изменение отчёта, как правка в палитре")
            .isEqualTo(1);
    }

    /** Выбранное поле — приватное состояние редактора: тест читает его отражением, без тест-хуков. */
    private static ReportField selectedField(ReportStructureEditor editor) {
        try {
            java.lang.reflect.Field field =
                ReportStructureEditor.class.getDeclaredField("selectedField");
            field.setAccessible(true);
            return (ReportField) field.get(editor);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("не удалось прочитать выбранное поле редактора", ex);
        }
    }
}
