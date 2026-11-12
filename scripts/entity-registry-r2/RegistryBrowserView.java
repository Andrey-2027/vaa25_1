package org.ipro.registryfixture;

import com.vaadin.flow.router.Route;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.select.Select;
import org.ipro.vaadin.explorer.RegistryFixture;
import org.ipro.vaadin.explorer.ui.*;
import org.ipro.metadata.annotation.EntityKind;

@Route("")
public class RegistryBrowserView extends Div {
    public RegistryBrowserView() {
        getStyle().set("padding","12px").set("background","#f3f5f9").set("min-height","100vh").set("box-sizing","border-box");
        var state = new EntityExplorerRegistryState();
        var note = new Span("R2 · изолированный стенд · демонстрационные факты");
        var width = new Select<Integer>(); width.setItems(1024,736,320); width.setValue(1024); width.setAriaLabel("Ширина панели");
        var region = new Div(); region.getStyle().set("width","1024px").set("max-width","100%").set("height","830px");
        var feedback = new Span(); feedback.getElement().setAttribute("role","status");
        var snapshot = RegistryFixture.snapshot();
        Runnable[] registry = new Runnable[1];
        java.util.function.BiConsumer<Class<?>,EntityExplorerCardPanel.Mode> showCard = (type, mode) -> {
            region.removeAll();
            var cardActions = new EntityExplorerCardPanel.Actions(other -> feedback.setText("Структура " + other.getSimpleName()),
                other -> {}, other -> false, () -> registry[0].run(), anchor -> feedback.setText("Место: " + anchor),
                ExplorerSourcePort.unavailable(), ExplorerFormPreviewPort.unavailable());
            region.add(new EntityExplorerCardPanel(snapshot,type,new EntityExplorerCardState(),mode,cardActions));
        };
        var actions = new EntityExplorerRegistryActions(type -> ExplorerActionAvailability.ready(),
            type -> showCard.accept(type,EntityExplorerCardPanel.Mode.DETAILED), type -> ExplorerActionAvailability.ready(),
            type -> feedback.setText("Изменение " + type.getSimpleName()),
            kind -> kind == EntityKind.PLAIN ? ExplorerActionAvailability.unavailable("Создание простых сущностей пока не поддерживается") : ExplorerActionAvailability.ready(),
            kind -> feedback.setText("Создание " + kind), ExplorerActionAvailability.ready(),
            () -> { region.removeAll(); region.add(new EntityExplorerDiagnosticsPanel(snapshot)); });
        var panel = new EntityExplorerRegistryPanel(snapshot,state,actions); region.add(panel);
        registry[0] = () -> {region.removeAll();region.add(panel);panel.refresh();};
        width.addValueChangeListener(event -> region.getStyle().set("width", event.getValue() + "px"));
        var reset = new Button("Начальное состояние", event -> { state.select(null); state.resetFilters(); state.setOverview(false); region.removeAll(); region.add(panel); panel.refresh(); });
        var compact = new Button("Компактная карточка", event -> showCard.accept(RegistryFixture.Nomenclature.class,EntityExplorerCardPanel.Mode.COMPACT));
        var controls = new Div(note,width,reset,compact); controls.getStyle().set("display","flex").set("gap","16px").set("align-items","center").set("flex-wrap","wrap");
        add(controls,region,feedback);
    }
}
