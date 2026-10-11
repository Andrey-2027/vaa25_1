package org.ip.views.forms;

import org.ip.application.register.AccountingObjectSaveService;
import org.ip.model.*;
import org.ipro.events.EventSource;
import org.ipro.form.FormSaveResult;
import org.ipro.form.ItemFormSaveHandler;
import org.ipro.form.MetadataDrivenItemFormSaveAdapter;
import org.ipro.form.builtin.ItemForm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AccountingObjectSaveHandlers {
    @Bean
    ItemFormSaveHandler<AccountingObjectInstallation> accountingInstallationFormSaveHandler(
            AccountingObjectSaveService commands, MetadataDrivenItemFormSaveAdapter adapter) {
        return new Handler<>(AccountingObjectInstallation.class, commands, adapter);
    }
    @Bean
    ItemFormSaveHandler<AccountingObjectCorrection> accountingCorrectionFormSaveHandler(
            AccountingObjectSaveService commands, MetadataDrivenItemFormSaveAdapter adapter) {
        return new Handler<>(AccountingObjectCorrection.class, commands, adapter);
    }

    private record Handler<D extends AccountingObjectDocument>(Class<D> supportedEntityClass,
            AccountingObjectSaveService commands, MetadataDrivenItemFormSaveAdapter adapter) implements ItemFormSaveHandler<D> {
        @Override public FormSaveResult<D> save(ItemForm<D> form) {
            var result = commands.save(form.getEntity(), adapter.harvest(form), EventSource.UI);
            adapter.apply(form, result);
            return new FormSaveResult.Success<>(result.aggregate());
        }
    }
}

