package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import org.ip.model.*;
import org.ip.views.forms.NomenclatureItemForm;
import org.ip.views.forms.NomenclatureItemFormConfig;
import org.ipro.crud.TableSectionService;
import org.ipro.form.BindingDescriptor;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.HasDisplayName;
import org.ipro.metadata.MetadataResolver;
import org.ipro.form.EntityField;
import org.ipro.form.FieldFactory;
import org.ipro.form.FormBindingRegistry;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ItemTable;
import org.ipro.form.registry.FormType;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.vaadin.explorer.EntitySummary;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Local preview of an existing custom layout and the standard document autoform. */
final class ExplorerFormPreview {
    private ExplorerFormPreview() { }

    static String unavailableReason(Class<?> entity, EntitySummary.FormRow registration) {
        if (registration.formType() != FormType.ITEM || !"default".equals(
                registration.variant() == null ? "default" : registration.variant()))
            return "Предпросмотр подключён только для ITEM/default";
        Class<?> expectedForm = entity == Nomenclature.class ? NomenclatureItemForm.class
            : entity == ReceivingDocument.class ? ItemForm.class : null;
        if (entity == ReceivingDocument.class) {
            return registration.platformDefault() && registration.formClass() == ItemForm.class
                && registration.registrationClasses().isEmpty() ? null
                : "Для этой регистрации безопасный путь предпросмотра не подключён";
        }
        Class<?> expectedConfig = NomenclatureItemFormConfig.class;
        if (expectedForm == null || registration.formClass() != expectedForm
                || !registration.registrationClasses().equals(List.of(expectedConfig.getName())))
            return "Для этой регистрации безопасный путь предпросмотра не подключён";
        return null;
    }

    static ItemForm<?> build(Class<?> entity, EntitySummary.FormRow registration, boolean example) {
        String reason = unavailableReason(entity, registration);
        if (reason != null) throw new IllegalArgumentException(reason);
        MetadataResolver metadata = new MetadataResolver();
        if (entity == Nomenclature.class) {
            Nomenclature value = example ? sampleNomenclature("ПРИМЕР-01", "Заготовка стальная — демонстрационный образец")
                : new Nomenclature();
            LocalFields fields = new LocalFields(example
                ? List.of(value.getUnitOfMeasurement(), value.getGroupNom()) : List.of());
            NomenclatureItemForm form = NomenclatureItemForm.preview(metadata.resolve(Nomenclature.class), fields);
            fields.bind(form);
            attach(form, Nomenclature.class, metadata, fields);
            form.setReadOnly(true);
            form.setEntity(value);
            if (example) {
                AttributeType type = new AttributeType("МАТЕРИАЛ", "Материал заготовки", AttributeValueType.STRING);
                AttributeValue attribute = new AttributeValue(type, "Сталь", "Сталь конструкционная", "СТАЛЬ", null);
                form.tableSection(NomAttributeValue.class).applyPersistedRows(value,
                    List.of(new NomAttributeValue(value, type, attribute)));
            }
            return form;
        }
        ReceivingDocument value = new ReceivingDocument();
        Journal journal = new Journal();
        journal.setCode("ПРИМЕР"); journal.setName("Приёмка готовой продукции");
        Workshop receiving = new Workshop("Ц-01", "Склад готовой продукции");
        Workshop transferring = new Workshop("Ц-02", "Механосборочный цех");
        LocalFields fields = new LocalFields(example ? List.of(journal, receiving, transferring) : List.of());
        ItemForm<ReceivingDocument> form = new ItemForm<>(metadata.resolve(ReceivingDocument.class), fields);
        fields.bind(form);
        attach(form, ReceivingDocument.class, metadata, fields);
        if (example) {
            value.setNumber("ПРИМЕР-0001"); value.setDate(LocalDate.of(2026, 10, 20));
            value.setJournal(journal); value.setReceivingWorkshop(receiving); value.setTransferringWorkshop(transferring);
        }
        form.setReadOnly(true);
        form.setEntity(value);
        if (example) {
            ReceivingDocumentItem first = new ReceivingDocumentItem(sampleNomenclature("ПРИМЕР-01",
                "Заготовка стальная — демонстрационный образец"), new BigDecimal("12.500"));
            ReceivingDocumentItem second = new ReceivingDocumentItem(sampleNomenclature("ПРИМЕР-02",
                "Корпус в сборе"), new BigDecimal("2.000"));
            first.setDocument(value); second.setDocument(value);
            form.tableSection(ReceivingDocumentItem.class).applyPersistedRows(value, List.of(first, second));
        }
        return form;
    }

    private static Nomenclature sampleNomenclature(String code, String name) {
        Nomenclature value = new Nomenclature(code, name, new UnitOfMeasurement("шт", "Штука", "796"));
        value.setGroupNom(new GroupNom("МАТ", "Материалы и заготовки"));
        value.setTypeNom("Материал");
        return value;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static <T extends IdentifiableEntity<Long>> void attach(ItemForm<T> form, Class<T> entity,
                                                                   MetadataResolver metadata, FieldFactory fields) {
        for (var section : metadata.resolveTableSections(entity)) {
            ItemTable table = new ItemTable(section, fields, new NoOperations<>(), metadata,
                null, null, null, null);
            table.addClassName("explorer-preview-table");
            table.setReadOnly(true);
            form.addTableSection(section.getTitle(), table);
        }
    }

    /** Fail closed if a later layout change accidentally attempts a data operation. */
    private static final class NoOperations<R extends IdentifiableEntity<Long>, P extends IdentifiableEntity<Long>>
            implements TableSectionService<R, P> {
        private UnsupportedOperationException denied() { return new UnsupportedOperationException("Предпросмотр: операция с данными запрещена"); }
        public List<R> findByParent(P parent) { throw denied(); }
        public R createNew(P parent) { throw denied(); }
        public List<String> validateRows(P parent, List<R> rows) { throw denied(); }
        public void replaceAll(P parent, List<R> rows) { throw denied(); }
    }

    /** Ordinary scalar widgets; reference widgets use only this dialog's local objects. */
    static final class LocalFields extends FieldFactory {
        private final List<HasDisplayName> values;
        private final java.util.Map<FieldMetadataInfo, EntityField<HasDisplayName>> references = new java.util.LinkedHashMap<>();
        LocalFields(List<HasDisplayName> values) {
            super(null, null);
            this.values = List.copyOf(values);
        }
        @Override @SuppressWarnings({"unchecked", "rawtypes"})
        public Component createField(FieldMetadataInfo info, FormBindingRegistry registry) {
            if (info.getResolvedType() != FieldType.ENTITY_REFERENCE || !info.hasLookup())
                return super.createField(info, registry);
            EntityField<HasDisplayName> field = new EntityField<>(info.getLabel(), term -> values.stream()
                .filter(info.getLookupEntity()::isInstance)
                .filter(v -> v.getDisplayName().toLowerCase(java.util.Locale.ROOT)
                    .contains(term == null ? "" : term.toLowerCase(java.util.Locale.ROOT))).toList());
            field.setReadOnly(true);
            references.put(info, field);
            return field;
        }

        <T extends IdentifiableEntity<Long>> void bind(ItemForm<T> form) {
            references.forEach((info, field) -> form.<HasDisplayName>bindExternal(BindingDescriptor.from(info), field,
                entity -> entity == null ? null : (HasDisplayName) info.getValue(entity),
                (entity, value) -> { /* preview never writes back */ },
                field::getValue,
                value -> { if (value == null) field.clear(); else field.setValue((HasDisplayName) value); },
                value -> value == null, field::setReadOnly));
            references.clear();
        }
    }
}
