package org.ipro.e5browser;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.orderedlayout.*;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import org.ipro.data.EntityDataAccess;
import org.ipro.crud.ServiceLocator;
import org.ipro.metadata.MetadataResolver;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.builtin.ListForm;
import com.example.generated.OwnedCatalog;
import com.example.generated.targets.ReferenceTarget;
import org.springframework.data.domain.PageRequest;
import java.util.*;

/** Browser-only host: delegates owner filtering to the unchanged E5.H standard forms. */
@Route("standard") @RolesAllowed("ADMIN")
@SuppressWarnings({"rawtypes","unchecked"})
public class BrowserStandardView extends VerticalLayout {
    public BrowserStandardView(EntityDataAccess data, ServiceLocator services, MetadataResolver metadata, SelectionFormAssembler selections) {
        setSizeFull(); add(new H3("E5.B · стандартные ListForm / SelectionForm"));
        var owners=data.list(ReferenceTarget.class,null,PageRequest.of(0,10)).getContent();
        var owner=new ComboBox<ReferenceTarget>("Владелец стандартных форм");
        owner.setItems(owners);owner.setItemLabelGenerator(ReferenceTarget::getName);
        var body=new VerticalLayout();body.setSizeFull();var result=new Span();
        add(new Button("Создать тестовые записи A/B",e->{
            if(data.list(OwnedCatalog.class,null,PageRequest.of(0,1)).isEmpty()) {
                for(var target:owners){var item=new OwnedCatalog();item.setName("Standard "+target.getName());item.setCounterparty(target);data.create(OwnedCatalog.class,item);}
            }
            result.setText("Тестовые записи A/B готовы");
        }),owner,new HorizontalLayout(
            new Button("Стандартный список",e->{
                body.removeAll();var list=new ListForm(metadata.resolve(OwnedCatalog.class),services.findService(OwnedCatalog.class));
                list.setOpeningContextFilters(Map.of("counterparty",owner.getValue()));list.setSizeFull();body.add(list);
            }),new Button("Стандартный выбор",e->{
                var selection=selections.assemble(OwnedCatalog.class,item->result.setText("Выбрано: "+item.getName()),Map.of("counterparty",owner.getValue()));selection.open();
            })),result,body);
        owner.setValue(owners.getFirst());
    }
}
