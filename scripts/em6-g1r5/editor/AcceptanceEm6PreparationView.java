package org.ip.views.admin;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import org.ip.config.AcceptanceEm6Fixture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Explicit fixture action inside a real admin request, where the normal session RLS cache exists. */
@Route("acceptance-em6-prepare")
@RolesAllowed("ADMIN")
@ConditionalOnProperty(name="acceptance.em6.prepare",havingValue="true")
public class AcceptanceEm6PreparationView extends VerticalLayout {
    public AcceptanceEm6PreparationView(AcceptanceEm6Fixture fixture,
            org.ipro.form.SelectionFormAssembler selections,
            org.ipro.form.coordinator.FormNavigator forms) {
        var result=new Span();
        add(new Span("Изолированная подготовка контрольных данных EM6"),new Button("Подготовить контрольные данные EM6",e->{
            try {fixture.prepare();result.setText("Контрольные данные EM6 подготовлены");}
            catch(Exception failure){result.setText("Отказ подготовки: "+failure.getMessage());}
        }),new Button("Добавить контрольную позицию EM6",e->{
            try {fixture.prepareDocumentRow();result.setText("Контрольная позиция EM6 подготовлена");}
            catch(Exception failure){result.setText("Отказ подготовки позиции: "+failure.getMessage());}
        }),result);
        var body=new VerticalLayout();
        add(new Button("Стандартный список Workshop",e->{body.removeAll();body.add(forms.createListForm(org.ip.model.Workshop.class));}),
            new Button("Стандартный список ReceivingDocument",e->{body.removeAll();body.add(forms.createListForm(org.ip.model.ReceivingDocument.class));}),
            new Button("Стандартный выбор Workshop",e->selections.assemble(org.ip.model.Workshop.class,row->result.setText("Выбран Workshop: "+row.getId())).open()),
            new Button("Стандартный выбор ReceivingDocument",e->selections.assemble(org.ip.model.ReceivingDocument.class,row->result.setText("Выбран документ: "+row.getId())).open()),body);
    }
}
