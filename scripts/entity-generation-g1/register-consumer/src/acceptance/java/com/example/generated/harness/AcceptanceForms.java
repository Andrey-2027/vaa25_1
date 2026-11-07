package com.example.generated.harness;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.form.coordinator.FormNavigator;
import java.util.Comparator;
import java.util.Map;

/** Navigation host only; all register controls come from the standard platform forms. */
@Route("") @PermitAll
@SuppressWarnings({"rawtypes", "unchecked"})
public class AcceptanceForms extends VerticalLayout {
    public AcceptanceForms(ManagedEntityCatalog catalog, FormNavigator forms,
            org.ipro.data.EntityDataAccess data, org.ipro.rls.RlsCurrentUser current) {
        setSizeFull();
        add(new H3("G1R.5 · стандартные формы"));
        add(new Span("Пользователь: " + current.username()));
        var types = new ComboBox<Class<?>>("Сущность");
        types.setItems(catalog.managedEntityClasses().stream()
            .filter(t -> t.getName().startsWith("com.example."))
            .sorted(Comparator.comparing(Class::getName)).toList());
        types.setItemLabelGenerator(Class::getSimpleName);
        var body = new VerticalLayout(); body.setSizeFull();
        var outcome = new Span(); outcome.setId("acceptance-outcome");
        add(types, new Button("Открыть стандартный список", e -> {
            body.removeAll();
            try {
                if (types.getValue() == null) return;
                // Refuse in the navigation handler before a lazy data provider attaches to UI.
                // This is a normal canonical read under the authenticated subject, with no bypass.
                data.list((Class)types.getValue(),null,org.springframework.data.domain.PageRequest.of(0,1));
                var list = forms.createListForm((Class) types.getValue(), null, Map.of());
                list.setSizeFull(); body.add(list);
                outcome.setText("Открыт: " + types.getValue().getName());
            } catch (RuntimeException refused) {
                outcome.setText("Отказ: " + refused.getMessage());
            }
        }), outcome, body);
        expand(body);
    }
}
