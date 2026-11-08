package org.ip.views.forms;

import org.ipro.metadata.annotation.FormConfigurationFor;
import org.ipro.metadata.annotation.ListColumns;
import org.ipro.metadata.annotation.SelectionColumns;

import org.ip.application.register.AccountingObjectRegisterService;
import org.ip.model.AccountingObjectCorrection;
import org.ip.model.AccountingObjectCorrectionItem;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.FormBehavior;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.springframework.stereotype.Component;

@FormConfigurationFor(AccountingObjectCorrection.class)
@ListColumns({"number", "date", "workshop.code", "workshop.name"})
@SelectionColumns({"number", "date", "workshop.code", "workshop.name"})
@Component
public class AccountingObjectCorrectionFormConfig implements ItemFormCustomization {
    private final AccountingObjectRegisterService reader;
    public AccountingObjectCorrectionFormConfig(AccountingObjectRegisterService reader) { this.reader = reader; }
    @Override public Class<?> entityClass() { return AccountingObjectCorrection.class; }
    @Override public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> new CorrectionForm(reader));
    }

    public static class CorrectionForm extends ItemForm<AccountingObjectCorrection> {
        private final AccountingObjectRegisterService reader;
        private boolean initialComparisonDone;
        public CorrectionForm(AccountingObjectRegisterService reader) {
            this.reader = reader;
        }
        @Override protected void behavior(FormBehavior b) {
            b.onChange("date", change -> refreshComparison());
            b.onChange("workshop", change -> refreshComparison());
            b.onLoaded(() -> {
                if (initialComparisonDone) return;
                initialComparisonDone = true;
                refreshComparison();
            });
        }
        private void refreshComparison() {
            if (peekEntity() == null || !sectionPayload(AccountingObjectCorrectionItem.class).isAttached()) return;
            var table = tableSection(AccountingObjectCorrectionItem.class);
            reader.refreshCurrentTypes(getEntity(), table.getRows());
            table.refreshRows();
        }
    }
}
