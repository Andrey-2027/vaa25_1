package org.ip.views.forms;

import org.ip.application.register.AccountingObjectRegisterService;
import org.ip.model.AccountingObjectCorrection;
import org.ip.model.AccountingObjectCorrectionItem;
import org.ipro.form.FieldFactory;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.metadata.EntityMetadataInfo;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.Map;

@Component
public class AccountingObjectCorrectionFormConfig implements ItemFormCustomization {
    private final AccountingObjectRegisterService reader;
    public AccountingObjectCorrectionFormConfig(AccountingObjectRegisterService reader) { this.reader = reader; }
    @Override public Class<?> entityClass() { return AccountingObjectCorrection.class; }
    @Override public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> new CorrectionForm(ctx.metadataResolver().resolve(AccountingObjectCorrection.class),
            ctx.fieldFactory(), reader));
    }

    /** Loading bindings must not apply a partially loaded header back onto the entity. */
    public static class CorrectionForm extends ItemForm<AccountingObjectCorrection> {
        private final AccountingObjectRegisterService reader;
        private boolean loading;
        public CorrectionForm(EntityMetadataInfo metadata, FieldFactory fields, AccountingObjectRegisterService reader) {
            super(metadata, fields);
            this.reader = reader;
            getEntityField("date", LocalDateTime.class).addValueChangeListener(event -> refreshComparison());
            entityField("workshop").addValueChangeListener(value -> refreshComparison());
        }
        @Override public void setEntity(AccountingObjectCorrection document) {
            loading = true;
            try { super.setEntity(document); } finally { loading = false; }
            refreshComparison();
        }
        @Override public AccountingObjectCorrection initializeNewEntity(Map<String, Object> values) {
            loading = true;
            try { return super.initializeNewEntity(values); } finally { loading = false; }
        }
        @Override public void applyPersistedEntity(AccountingObjectCorrection document) {
            loading = true;
            try { super.applyPersistedEntity(document); } finally { loading = false; }
        }
        private void refreshComparison() {
            if (loading || peekEntity() == null || !sectionPayload(AccountingObjectCorrectionItem.class).isAttached()) return;
            var table = tableSection(AccountingObjectCorrectionItem.class);
            reader.refreshCurrentTypes(getEntity(), table.getRows());
            table.refreshRows();
        }
    }
}

