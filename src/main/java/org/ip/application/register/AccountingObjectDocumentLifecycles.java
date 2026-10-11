package org.ip.application.register;

import org.ip.model.AccountingObjectInstallation;
import org.ip.model.AccountingObjectCorrection;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntitySaveContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

@Configuration(proxyBeanMethods = false)
public class AccountingObjectDocumentLifecycles {
    @Bean
    EntityLifecycle<AccountingObjectInstallation> accountingObjectInstallationLifecycle(ObjectProvider<AccountingObjectRegisterGeneration> generation) {
        return new EntityLifecycle<>() {
            @Override public Class<AccountingObjectInstallation> entityType() { return AccountingObjectInstallation.class; }
            @Override public void beforeSave(EntitySaveContext<AccountingObjectInstallation> context) {
                AccountingObjectSaveScope.require(context.entity());
            }
            @Override public void onSave(EntitySaveContext<AccountingObjectInstallation> context) { generation.getObject().onSave(context); }
        };
    }
    @Bean
    EntityLifecycle<AccountingObjectCorrection> accountingObjectCorrectionLifecycle(ObjectProvider<AccountingObjectRegisterGeneration> generation) {
        return new EntityLifecycle<>() {
            @Override public Class<AccountingObjectCorrection> entityType() { return AccountingObjectCorrection.class; }
            @Override public void beforeSave(EntitySaveContext<AccountingObjectCorrection> context) {
                AccountingObjectSaveScope.require(context.entity());
            }
            @Override public void onSave(EntitySaveContext<AccountingObjectCorrection> context) { generation.getObject().onSave(context); }
        };
    }
}

