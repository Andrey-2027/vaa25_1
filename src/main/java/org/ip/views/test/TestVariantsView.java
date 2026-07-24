package org.ip.views.test;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.ip.form.coordinator.FormCoordinator;
import org.ip.model.Nomenclature;
import org.ip.model.UnitOfMeasurement;

/**
 * Тестовый view для проверки Form Variants с наследованием, baseFilter, lookupVariant.
 *
 * Phase 0 (baseline):
 *   - 3 кнопки открывают generic ListForm (реестр пуст, variant ничего не делает)
 *   - ожидаемое поведение: все три кнопки приводят к ОДНОЙ И ТОЙ ЖЕ форме (generic)
 *
 * Phase 3+ (после реализации):
 *   - "Открыть Nomenclature (default)" → generic Nomenclature ListForm
 *   - "Открыть Nomenclature (archived)" → ListForm с baseFilter (только архивные) + readOnly
 *   - "Открыть Unit (default)" → generic Unit ListForm
 *
 * Доступ: /test-variants (минуя MainLayout, чтобы не возиться с workspace).
 */
@Route("test-variants")
@PageTitle("Test Form Variants")
public class TestVariantsView extends VerticalLayout {

    private final FormCoordinator coordinator;

    public TestVariantsView(FormCoordinator coordinator) {
        this.coordinator = coordinator;
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H2("Form Variants — baseline / smoke test"));

        add(new Paragraph(
            "Phase 0: реестр вариантов пуст. Все кнопки должны открывать generic ListForm. " +
            "Различий в UI быть не должно — это нормальный baseline перед Phase 1."
        ));

        // === Nomenclature ===

        HorizontalLayout nomenclatureRow = new HorizontalLayout();
        nomenclatureRow.setSpacing(true);
        nomenclatureRow.add(new Paragraph("Nomenclature:"));

        Button nomenclatureDefault = new Button("default", e ->
            coordinator.openListForm(Nomenclature.class, null, null)
        );

        Button nomenclatureArchived = new Button("archived", e ->
            coordinator.openListForm(Nomenclature.class, "archived", null)
        );

        Button nomenclatureActive = new Button("active", e ->
            coordinator.openListForm(Nomenclature.class, "active", null)
        );

        nomenclatureRow.add(nomenclatureDefault, nomenclatureArchived, nomenclatureActive);
        add(nomenclatureRow);

        // === UnitOfMeasurement ===

        HorizontalLayout unitRow = new HorizontalLayout();
        unitRow.setSpacing(true);
        unitRow.add(new Paragraph("Unit:"));

        Button unitDefault = new Button("default", e ->
            coordinator.openListForm(UnitOfMeasurement.class, null, null)
        );

        Button unitCompact = new Button("compact", e ->
            coordinator.openListForm(UnitOfMeasurement.class, "compact", null)
        );

        unitRow.add(unitDefault, unitCompact);
        add(unitRow);

        // === Diagnostic info ===

        add(new Paragraph(
            "Baseline-ожидание: все 5 кнопок открывают формы. " +
            "Различий между default / archived / active / compact быть не должно — " +
            "это значит, что variant пока НЕ маршрутизируется в кастомный FormConfig. " +
            "После Phase 1+ у кнопок появится разное поведение."
        ));
    }
}
