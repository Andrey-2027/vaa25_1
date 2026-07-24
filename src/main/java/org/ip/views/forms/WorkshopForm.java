package org.ip.views.forms;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.ipro.crud.AbstractEntityForm;
import org.ipro.crud.FormBuilder;
import org.ip.model.Workshop;
import org.ip.service.WorkshopService;
import org.ip.views.workspace.Dirtyable;
import org.ip.views.workspace.Savable;

import java.util.Objects;

public class WorkshopForm extends AbstractEntityForm<Workshop> implements Dirtyable, Savable {

    private final WorkshopService service;
    private String initialCode;
    private String initialName;
    private Runnable onClose;
    private Runnable afterSave;

    public WorkshopForm(WorkshopService service) {
        super(Workshop.class);
        this.service = service;
    }

    @Override
    protected void buildForm(FormBuilder<Workshop> form) {
        form.addAuto("code", "Код");
        form.addAuto("name", "Наименование");
    }

    public void editEntity(Long id) {
        Workshop w = id != null ? service.findById(id).orElseThrow() : new Workshop();
        initialCode = w.getCode();
        initialName = w.getName();
        setEntity(w);

        Button cancelBtn = new Button("Отмена", e -> {
            if (onClose != null) onClose.run();
        });

        Button saveBtn = new Button("Записать", VaadinIcon.CHECK.create(), e -> doSave());
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button saveCloseBtn = new Button("Сохранить и закрыть", VaadinIcon.CHECK_CIRCLE.create(), e -> {
            if (doSave() && onClose != null) onClose.run();
        });

        Span spacer = new Span();
        HorizontalLayout footer = new HorizontalLayout(cancelBtn, spacer, saveCloseBtn, saveBtn);
        footer.setWidthFull();
        footer.expand(spacer);
        add(footer);
        setHeightFull();
        expand(getComponentAt(0));
    }

    public boolean doSave() {
        applyChanges();
        Workshop entity = getEntity();
        if (entity != null) {
            try {
                Workshop saved = service.save(entity);
                entity.setVersion(saved.getVersion());
                initialCode = saved.getCode();
                initialName = saved.getName();
                Notification.show("Сохранено", 3000, Notification.Position.BOTTOM_START);
                if (afterSave != null) afterSave.run();
                return true;
            } catch (Exception ex) {
                Notification.show("Ошибка сохранения: " + ex.getMessage(), 5000, Notification.Position.MIDDLE);
            }
        }
        return false;
    }

    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    public void setAfterSave(Runnable afterSave) {
        this.afterSave = afterSave;
    }

    @Override
    public boolean isDirty() {
        applyChanges();
        Workshop w = getEntity();
        return !Objects.equals(w.getCode(), initialCode)
            || !Objects.equals(w.getName(), initialName);
    }

    @Override
    public String getCloseConfirmMessage() {
        return "Есть несохранённые изменения. Закрыть вкладку?";
    }
}
