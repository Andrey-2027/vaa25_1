package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import org.ipro.vaadin.explorer.EntitySummary;

/** W9 owns fresh components and values; it never changes the Explorer selection or URL. */
final class ExplorerFormPreviewDialog extends Dialog {
    private final Div content = new Div();

    ExplorerFormPreviewDialog(Class<?> entity, EntitySummary.FormRow registration) {
        setHeaderTitle("Предпросмотр · " + registration.formClass().getSimpleName());
        addClassNames("explorer-form-preview", "form-1c");
        getElement().getThemeList().add("form-1c");
        getElement().getThemeList().add("explorer-preview");
        setWidth("920px");
        setMaxWidth("calc(100vw - 24px)");
        setMaxHeight("calc(100dvh - 24px)");
        RadioButtonGroup<String> modes = new RadioButtonGroup<>();
        modes.getElement().setAttribute("aria-label", "Данные предпросмотра");
        modes.setItems("Пустая", "С примером");
        modes.setValue("Пустая");
        modes.addValueChangeListener(e -> show(entity, registration, "С примером".equals(e.getValue())));
        Div controls = new Div(modes, new Span("Данные только в памяти"));
        controls.addClassName("explorer-preview-controls");
        add(controls, content);
        getFooter().add(new Span("Режим просмотра · сохранение и прикладные команды недоступны"),
            new Button("Закрыть", e -> close()));
        addOpenedChangeListener(e -> { if (!e.isOpened()) content.removeAll(); });
        show(entity, registration, false);
    }

    private void show(Class<?> entity, EntitySummary.FormRow registration, boolean example) {
        content.removeAll();
        var form = ExplorerFormPreview.build(entity, registration, example);
        form.setHeight(null);
        form.addClassName("explorer-preview-form");
        content.add(form);
        getElement().setAttribute("data-preview-mode", example ? "example" : "empty");
    }
}
