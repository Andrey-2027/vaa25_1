package org.ip.views.forms;

import org.ip.application.register.AccountingObjectRegisterService;
import org.ip.model.*;
import org.ipro.crud.ValidationException;
import org.ipro.data.EntityDataAccess;
import org.ipro.filtergrid.inmemory.InMemoryFilterGrid;
import org.ipro.form.EntityField;
import org.ipro.form.SelectionForm;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.TableSectionCustomization;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ItemTable;
import org.ipro.form.builder.FormBehavior;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.form.builder.layout.FormAdjust;
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
        variants.add(VARIANT, ctx -> new RowForm(reader, access, selections));
    }

    public static class RowForm extends ItemForm<AccountingObjectCorrectionItem> {
        private final AccountingObjectRegisterService reader;
        private final EntityDataAccess access;
        private final SelectionFormAssembler selections;
        public RowForm(AccountingObjectRegisterService reader, EntityDataAccess access,
                SelectionFormAssembler selections) {
            this.reader = reader; this.access = access; this.selections = selections;
        }
        @Override protected void adjust(FormAdjust a) {
            a.element("currentAccountingType").setReadOnly(true);
            a.element("nomenclature").setEditor(this::nomenclatureEditor);
            a.element("accountingType").setEditor(this::accountingTypeEditor);
        }
        @Override protected void behavior(FormBehavior b) {
            b.onChange("nomenclature", change -> {
                refreshCurrent((Nomenclature) change.value());
                this.<AccountingType>entityField("accountingType").clear();
            });
            b.onLoaded(() -> {
                var row = peekEntity();
                if (row == null) return;
                refreshCurrent(row.getNomenclature());
                getBindingRegistry().getBinding("currentAccountingType").orElseThrow().markClean();
            });
        }
        private EntityField<Nomenclature> nomenclatureEditor() {
            var field = new EntityField<Nomenclature>("Номенклатура", term -> candidates().stream()
                .map(NomenclatureAccountingObject::getNomenclature)
                .filter(n -> matches(n, term)).toList());
            field.setSelectionFormFactory(onSelect -> selection(onSelect));
            return field;
        }
        private EntityField<AccountingType> accountingTypeEditor() {
            var field = new EntityField<AccountingType>("Новый тип учёта",
                term -> access.lookup(AccountingType.class, term, 30));
            field.setSelectionFormFactory(onSelect -> selections.assemble(AccountingType.class, onSelect, Map.of()));
            return field;
        }
        private List<NomenclatureAccountingObject> candidates() {
            var row = peekEntity();
            if (row == null || row.getDocument() == null) return List.of();
            try { return reader.selection(row.getDocument()); }
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
            var row = peekEntity();
            if (row == null || row.getDocument() == null) return;
            AccountingType current = value == null ? null : reader.currentType(row.getDocument(), value.getId())
                .orElse(null);
            row.setCurrentAccountingType(current);
            this.<AccountingType>entityField("currentAccountingType").setValue(current);
        }
        private static boolean matches(Nomenclature n, String term) {
            String search = term.toLowerCase(Locale.ROOT);
            return (n.getCode() + " " + n.getName()).toLowerCase(Locale.ROOT).contains(search);
        }
        private static String label(HasDisplayName value) { return value == null ? "—" : value.getDisplayName(); }
    }

    @Override
    public org.ipro.metadata.FormColumnDeclarations.Columns columns() {
        var columns = org.ipro.metadata.FormColumnDeclarations.auto();
        columns.column("nomenclature").setFlexGrow(1);
        columns.column("accountingType").setWidth("180px").setAutoWidth(true);
        columns.column("currentAccountingType").setWidth("180px").setAutoWidth(true);
        return columns;
    }
}
