package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import java.util.function.Consumer;
import org.ip.application.access.*;

/** Common object/scope cell. Effective checkbox edits only the active subject's direct flag. */
public class PermissionCell extends HorizontalLayout {
    public PermissionCell(String label, SubjectRef subject, PermissionCellState state, Consumer<Boolean> change) {
        this(label, subject, state, change, ignored -> { });
    }
    public PermissionCell(String label, SubjectRef subject, PermissionCellState state, Consumer<Boolean> change,
                          Consumer<SubjectRef> navigateRole) {
        setPadding(false); setSpacing(false); setAlignItems(Alignment.CENTER);
        getStyle().set("gap", "var(--lumo-space-xs)");
        var checkbox = new Checkbox(); checkbox.setValue(state.effective()); checkbox.setEnabled(state.editable());
        checkbox.getElement().setAttribute("aria-label", label);
        checkbox.setTooltipText(label + ". " + (state.explanation().isBlank() ? "Изменяется только личное назначение" : state.explanation()));
        checkbox.addValueChangeListener(event -> { if (event.isFromClient()) change.accept(event.getValue()); });
        add(checkbox);
        if (state.direct()) {
            var direct = new Span("Л"); direct.getElement().setAttribute("title", "Личное назначение"); add(direct);
        }
        if (state.dirty()) {
            var dirty = new Span("•"); dirty.getElement().setAttribute("title", "Несохранённое изменение"); add(dirty);
        }
        var inherited = state.grantingRoles(subject);
        if (!inherited.isEmpty()) {
            var roles = new Button("Р", event -> explain(label, subject, state, change, navigateRole, event.getSource()));
            roles.getElement().setAttribute("aria-label", label + ": показать роли-источники");
            roles.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            roles.setTooltipText("Роли-источники: " + inherited.size()); add(roles);
        }
        if (inherited.isEmpty()) {
            var details = new Button(VaadinIcon.INFO_CIRCLE_O.create(), event -> explain(label, subject, state, change, navigateRole, event.getSource()));
            details.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ICON);
            details.getElement().setAttribute("aria-label", label + ": источники права");
            details.setTooltipText("Источники права"); add(details);
        }
    }
    private void explain(String label, SubjectRef subject, PermissionCellState state, Consumer<Boolean> change,
                         Consumer<SubjectRef> navigateRole, Button opener) {
        var dialog = new Dialog(); dialog.setHeaderTitle(label + " — источники");
        dialog.setCloseOnEsc(true); dialog.setCloseOnOutsideClick(true);
        dialog.addOpenedChangeListener(event -> { if (!event.isOpened() && opener.isAttached()) opener.focus(); });
        var list = new VerticalLayout();
        var grouped = new java.util.LinkedHashMap<String, java.util.List<PermissionContribution>>();
        state.sources().forEach(source -> grouped.computeIfAbsent(source.subject() == null ? "system:" + source.explanation()
            : source.subject().identity().toString(), ignored -> new java.util.ArrayList<>()).add(source));
        grouped.values().forEach(contributions -> {
            var source = contributions.getFirst();
            if (source.subject() == null) { list.add(new Span(source.explanation())); return; }
            var line = new VerticalLayout(); line.setPadding(false);
            if (source.subject().type() == org.ipro.rls.AccessGrant.SubjectType.ROLE && !source.subject().identity().equals(subject.identity())) {
                line.add(new Button(source.subject().title(), event -> { dialog.close(); navigateRole.accept(source.subject()); }));
            } else line.add(new Span("Личное назначение — " + source.subject().title()));
            contributions.stream().map(value -> value.explanation()
                + (value.address().valueId() == null ? "" : " (id=" + value.address().valueId() + ")")
                + (value.kind() == PermissionContribution.Kind.DRAFT_GRANT ? " (черновик)" : "")).distinct()
                .forEach(text -> line.add(new Span(text)));
            list.add(line);
        });
        if (state.sources().isEmpty()) list.add(new Span("Разрешающих источников нет"));
        if (!state.explanation().isBlank()) list.add(new Span(state.explanation()));
        dialog.add(list);
        if (!state.direct()) dialog.getFooter().add(new Button("Назначить лично", event -> { change.accept(true); dialog.close(); }));
        dialog.getFooter().add(new Button("Закрыть", event -> dialog.close())); dialog.open();
    }
}
