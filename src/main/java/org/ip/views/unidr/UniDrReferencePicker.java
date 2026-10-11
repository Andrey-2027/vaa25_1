package org.ip.views.unidr;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.TextField;
import org.ip.unidr.query.UniDrReferenceService;
import org.ip.unidr.query.UniDrReferenceService.Option;
import org.ipro.crud.BaseEntity;
import org.ipro.form.EntityField;
import org.ipro.form.SelectionForm;
import org.ipro.form.SelectionFormAssembler;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** The UDR EntityField and canonical selection form; only guarded reference IDs enter the recipe. */
final class UniDrReferencePicker extends EntityField<Option> {
    private final TextField input;
    UniDrReferencePicker(String fieldId,String caption,UniDrReferenceService references,SelectionFormAssembler selection) {
        super(caption,term->{
            try {return references.search(fieldId,term,20);}
            catch(RuntimeException denied) {com.vaadin.flow.component.notification.Notification.show("Справочник недоступен. Проверьте права доступа");return List.of();}
        });
        addClassName("unidr-entity-field");setWidthFull();
        // EntityField exposes its public component tree, but does not yet expose validation/required APIs.
        input=tree(this).filter(TextField.class::isInstance).map(TextField.class::cast).findFirst().orElseThrow();
        input.setWidthFull();input.setClearButtonVisible(true);input.setAriaLabel(caption);
        var browse=tree(this).filter(Button.class::isInstance).map(Button.class::cast).findFirst().orElseThrow();
        browse.setAriaLabel("Выбрать из справочника: "+caption);browse.setEnabled(selection!=null);
        if(selection!=null)setSelectionFormFactory(onSelect->form(fieldId,references,selection,onSelect));
        addValueChangeListener(value->{input.setInvalid(false);});
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private SelectionForm<Option> form(String fieldId,UniDrReferenceService references,SelectionFormAssembler selection,Consumer<Option> selected) {
        Consumer<BaseEntity> accept=entity->{
            try {selected.accept(references.selected(fieldId,entity.getId()));input.setInvalid(false);}
            catch(RuntimeException denied) {invalid("Выбранная ссылка недоступна. Выберите другое значение");}
        };
        var form=selection.assemble((Class)UniDrReferenceService.referenceType(fieldId),(Consumer)accept);
        form.setDialogSize("min(900px, 96vw)","min(650px, 90vh)");
        return (SelectionForm<Option>)(SelectionForm<?>)form;
    }
    void required(boolean required) {input.setRequiredIndicatorVisible(required);}
    void invalid(String message) {input.setInvalid(true);input.setErrorMessage(message);}
    @Override public void setLabel(String label) {super.setLabel(label);if(input!=null)input.setAriaLabel(label);}
    private static Stream<Component> tree(Component root) {return Stream.concat(Stream.of(root),root.getChildren().flatMap(UniDrReferencePicker::tree));}
}
