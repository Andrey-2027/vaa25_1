package org.ip.views.forms;

import org.ip.application.register.AccountingObjectRegisterService;
import org.ip.model.*;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
import org.ipro.filtergrid.inmemory.InMemoryFilterGrid;
import org.ipro.form.BindingDescriptor;
import org.ipro.form.EntityField;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionForm;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.TableSectionCustomization;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ItemTable;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.form.registry.FormContext;
import org.ipro.metadata.HasDisplayName;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Consumer;

@Component
public class AccountingObjectCorrectionRowConfig implements ItemFormCustomization,
        TableSectionCustomization<AccountingObjectCorrectionItem> {
    public static final String VARIANT = "accounting-correction-row";
    private final AccountingObjectRegisterService reader;
    private final EntityDataAccess access;
    private final SelectionFormAssembler selections;
    public AccountingObjectCorrectionRowConfig(AccountingObjectRegisterService reader,
            EntityDataAccess access, SelectionFormAssembler selections) {
        this.reader = reader; this.access = access; this.selections = selections;
    }
    @Override public Class<?> entityClass() { return AccountingObjectCorrectionItem.class; }
    @Override public Class<AccountingObjectCorrectionItem> rowClass() { return AccountingObjectCorrectionItem.class; }
    @Override public List<String> declaredRowVariants() { return List.of(VARIANT); }
    @Override public void configure(ItemTable<AccountingObjectCorrectionItem, ?> table) {
        table.setRowVariantSelector(row -> VARIANT);
    }
    @Override public void configure(ItemFormVariants variants) {
        variants.add(VARIANT, ctx -> new RowForm(ctx, reader, access, selections));
    }

    public static class RowForm extends ItemForm<AccountingObjectCorrectionItem> {
        private final AccountingObjectRegisterService reader;
        private final EntityField<Nomenclature> nomenclature;
        private final EntityField<AccountingType> newType;
        private boolean loading;
        public RowForm(FormContext ctx, AccountingObjectRegisterService reader,
                EntityDataAccess access, SelectionFormAssembler selections) {
            super(AccountingObjectCorrectionItem.class, ctx.metadataResolver()
                .resolveRowMetadata(AccountingObjectCorrectionItem.class).getFormFields().stream()
                .filter(field -> field.getName().equals("currentAccountingType")).toList(), ctx.fieldFactory());
            this.reader = reader;
            nomenclature = new EntityField<>("Номенклатура", term -> candidates().stream()
                .map(NomenclatureAccountingObject::getNomenclature)
                .filter(n -> matches(n, term)).toList());
            nomenclature.setSelectionFormFactory(onSelect -> selection(onSelect));
            bindExternal(new BindingDescriptor("nomenclature", "Номенклатура", true), nomenclature,
                AccountingObjectCorrectionItem::getNomenclature, AccountingObjectCorrectionItem::setNomenclature,
                nomenclature::getValue, nomenclature::setValue, Objects::isNull, nomenclature::setReadOnly);
            newType = new EntityField<>("Новый тип учёта", term -> access.lookup(AccountingType.class, term, 30));
            newType.setSelectionFormFactory(onSelect -> selections.assemble(AccountingType.class, onSelect, Map.of()));
            bindExternal(new BindingDescriptor("accountingType", "Новый тип учёта", true), newType,
                AccountingObjectCorrectionItem::getAccountingType, AccountingObjectCorrectionItem::setAccountingType,
                newType::getValue, newType::setValue, Objects::isNull, newType::setReadOnly);
            addComponentAtIndex(1, nomenclature);
            addComponentAtIndex(3, newType);
            nomenclature.addValueChangeListener(value -> {
                if (!loading) { refreshCurrent(value); newType.setValue(null); }
            });
        }
        @Override public void setEntity(AccountingObjectCorrectionItem row) {
            loading = true;
            try { super.setEntity(row); } finally { loading = false; }
            refreshCurrent(row.getNomenclature());
        }
        private List<NomenclatureAccountingObject> candidates() {
            try { return reader.selection(peekEntity().getDocument()); }
            catch (ValidationException | org.springframework.security.access.AccessDeniedException refusal) {
                com.vaadin.flow.component.notification.Notification.show(refusal.getMessage(), 5_000,
                    com.vaadin.flow.component.notification.Notification.Position.MIDDLE);
                return List.of();
            }
        }
        private SelectionForm<Nomenclature> selection(Consumer<Nomenclature> onSelect) {
            var rows = candidates();
            Map<Long, AccountingType> types = new HashMap<>();
            rows.forEach(row -> types.put(row.getNomenclature().getId(), row.getAccountingType()));
            InMemoryFilterGrid<Nomenclature> grid = new InMemoryFilterGrid<>(Nomenclature.class,
                rows.stream().map(NomenclatureAccountingObject::getNomenclature).toList());
            grid.addColumn("code", "Код", Nomenclature::getCode);
            grid.addColumn("name", "Номенклатура", Nomenclature::getName);
            grid.addColumn("current", "Текущий тип учёта", row -> label(types.get(row.getId())));
            return new SelectionForm<>("Объекты учёта цеха на дату корректировки", grid, onSelect);
        }
        private void refreshCurrent(Nomenclature value) {
            if (peekEntity() == null) return;
            AccountingType current = value == null ? null : reader.currentType(peekEntity().getDocument(), value.getId())
                .orElse(null);
            peekEntity().setCurrentAccountingType(current);
            this.<AccountingType>entityField("currentAccountingType").setValue(current);
        }
        private static boolean matches(Nomenclature n, String term) {
            String search = term.toLowerCase(Locale.ROOT);
            return (n.getCode() + " " + n.getName()).toLowerCase(Locale.ROOT).contains(search);
        }
        private static String label(HasDisplayName value) { return value == null ? "—" : value.getDisplayName(); }
    }
}

