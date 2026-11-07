package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Disposable stand entry points for the actual modification view, including non-root types. */
@Route("acceptance-em6-negative")
@RolesAllowed({"ADMIN", "MANAGER", "VIEWER"})
@ConditionalOnProperty(name="acceptance.em6.prepare", havingValue="true")
public class AcceptanceEm6NegativeView extends VerticalLayout {
    public AcceptanceEm6NegativeView(EntityDraftAccess access, EntityModificationService service) {
        var body = new VerticalLayout();
        for (Class<?> type : new Class<?>[]{org.ip.model.Workshop.class,
                org.ip.model.NomenclaturePrice.class, org.ip.model.ReceivingDocumentItem.class}) {
            add(new Button("Проверить " + type.getSimpleName(), event -> {
                body.removeAll();
                var view = new EntityModifyView(access, service);
                view.initialize(type, () -> {});
                body.add(view);
            }));
        }
        add(body);
    }
}
