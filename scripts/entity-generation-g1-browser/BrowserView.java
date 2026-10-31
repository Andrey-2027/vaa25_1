package org.ipro.e5browser;

import com.vaadin.flow.component.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.orderedlayout.*;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.ipro.data.EntityDataAccess;
import org.ipro.form.*;
import org.ipro.form.builtin.*;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.MetadataResolver;
import com.example.generated.targets.ReferenceTarget;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import java.util.*;

/** Disposable host only: genuine standard fields/dialogs/metadata/canonical aggregate save. */
@Route("") @PermitAll
@SuppressWarnings({"rawtypes","unchecked"})
public class BrowserView extends VerticalLayout {
    public static final List<String> TYPES=List.of("ScalarDocument","ReferenceCatalog","ReferenceDocument","OwnedCatalog","CatalogSections2","DocumentSections2","OwnedCatalogSections2");
    final EntityDataAccess data;final MetadataResolver metadata;final FieldFactory fields;final TableSectionFactory sections;final MetadataDrivenItemFormSaveAdapter adapter;
    final ComboBox<String> types=new ComboBox<>("Сценарий");final ComboBox<ReferenceTarget> owner=new ComboBox<>("Контекст владельца");
    final VerticalLayout body=new VerticalLayout();final Span status=new Span();final Grid<IdentifiableEntity> grid=new Grid<>();ItemForm form;Class type;Long lastId;
    public BrowserView(EntityDataAccess data,MetadataResolver metadata,FieldFactory fields,TableSectionFactory sections,MetadataDrivenItemFormSaveAdapter adapter){
        this.data=data;this.metadata=metadata;this.fields=fields;this.sections=sections;this.adapter=adapter;
        setSizeFull();setSpacing(false);add(new H3("E5.B · изолированный H2 consumer · e5-admin"),new Anchor("/draft","Редактор P8"));
        types.setItems(TYPES);types.addValueChangeListener(e->{type=load(e.getValue());lastId=null;body.removeAll();reload();});
        owner.setItemLabelGenerator(ReferenceTarget::getName);owner.setItems(data.list(ReferenceTarget.class,null,PageRequest.of(0,50)).getContent());owner.setClearButtonVisible(true);owner.addValueChangeListener(e->reload());
        add(new HorizontalLayout(types,owner,new Button("Создать",e->open(null)),new Button("Перечитать список",e->reload())));
        grid.addColumn(IdentifiableEntity::getId).setHeader("ID");grid.addColumn(x->caption(x)).setHeader("Представление");grid.addItemDoubleClickListener(e->open((Long)e.getItem().getId()));grid.setHeight("130px");
        add(grid,new HorizontalLayout(new Button("Открыть выбранную",e->{var selected=grid.asSingleSelect().getValue();if(selected!=null)open((Long)selected.getId());}),new Button("Повторно открыть",e->{if(lastId!=null)open(lastId);}),new Button("Удалить выбранную",e->delete()),new Button("Удалить текущего владельца",e->deleteOwner())));
        status.setId("outcome");add(status,body);types.setValue(TYPES.getFirst());
    }
    Class load(String simple){try{return Class.forName("com.example.generated."+simple);}catch(Exception e){throw new IllegalStateException(e);}}
    String caption(Object o){try{return String.valueOf(o.getClass().getMethod(type.getSimpleName().contains("Document")?"getNumber":"getName").invoke(o));}catch(Exception e){return String.valueOf(o);}}
    void reload(){if(type==null)return;Specification filter=null;if(metadata.resolve(type).getFieldByName("counterparty")!=null&&owner.getValue()!=null){Long id=owner.getValue().getId();filter=(r,q,c)->c.equal(r.get("counterparty").get("id"),id);}
        grid.setItems(data.list(type,filter,PageRequest.of(0,100)).getContent());}
    void open(Long id){
        body.removeAll();form=new ItemForm(metadata.resolve(type),fields);sections.attachTableSections(form,type);form.setSaveHandler(adapter::save);
        if(id==null){Map<String,Object> initial=new HashMap<>();if(metadata.resolve(type).getFieldByName("date")!=null)initial.put("date",java.time.LocalDate.of(2026,10,31));
            if(metadata.resolve(type).getFieldByName("counterparty")!=null&&owner.getValue()!=null)initial.put("counterparty",owner.getValue());form.initializeNewEntity(initial);
        }else{form.setEntity((IdentifiableEntity)data.detail(type,id).orElseThrow());lastId=id;}
        body.add(new Button("Сохранить карточку",e->{var result=form.save();if(result instanceof FormSaveResult.Success success){lastId=(Long)((IdentifiableEntity)success.saved()).getId();status.setText("Сохранено ID="+lastId);reload();}else if(result instanceof FormSaveResult.Failure f){status.setText("Ошибка: "+String.join("; ",(List<String>)f.messages()));}else status.setText("Конфликт: "+result);}),form);
        status.setText(id==null?"Новая карточка":"Повторно открыто ID="+id);
    }
    void delete(){var selected=grid.asSingleSelect().getValue();if(selected==null)return;try{data.delete(type,selected.getId());status.setText("Удалено");reload();}catch(RuntimeException e){status.setText("Отказ удаления: "+e.getMessage());}}
    void deleteOwner(){if(owner.getValue()==null)return;try{data.delete(ReferenceTarget.class,owner.getValue().getId());status.setText("Владелец удалён");}catch(RuntimeException e){status.setText("Отказ удаления используемого владельца: "+e.getMessage());}}
}
