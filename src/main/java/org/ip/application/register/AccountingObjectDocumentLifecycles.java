package org.ip.application.register;

import org.ip.model.AccountingObjectCorrection;
import org.ip.model.AccountingObjectCorrectionItem;
import org.ip.model.AccountingObjectInstallation;
import org.ip.model.AccountingObjectInstallationItem;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntitySaveContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Lifecycle документа объекта учёта: вето «только командой, объявившей позиции» в {@code beforeSave}
 * и генерация строк регистра в {@code onSave}. Позиции приходят из объявленного командой payload'а
 * (ADR-0026), а не из прикладного ThreadLocal'а.
 */
@Configuration(proxyBeanMethods = false)
public class AccountingObjectDocumentLifecycles {
    @Bean
    EntityLifecycle<AccountingObjectInstallation> accountingObjectInstallationLifecycle(ObjectProvider<AccountingObjectRegisterGeneration> generation) {
        return new EntityLifecycle<>() {
            @Override public Class<AccountingObjectInstallation> entityType() { return AccountingObjectInstallation.class; }
            @Override public void beforeSave(EntitySaveContext<AccountingObjectInstallation> context) {
                AccountingObjectPositions.require(context, AccountingObjectInstallationItem.class);
            }
            @Override public void onSave(EntitySaveContext<AccountingObjectInstallation> context) { generation.getObject().onSave(context); }
        };
    }
    @Bean
    EntityLifecycle<AccountingObjectCorrection> accountingObjectCorrectionLifecycle(ObjectProvider<AccountingObjectRegisterGeneration> generation) {
        return new EntityLifecycle<>() {
            @Override public Class<AccountingObjectCorrection> entityType() { return AccountingObjectCorrection.class; }
            @Override public void beforeSave(EntitySaveContext<AccountingObjectCorrection> context) {
                AccountingObjectPositions.require(context, AccountingObjectCorrectionItem.class);
            }
            @Override public void onSave(EntitySaveContext<AccountingObjectCorrection> context) { generation.getObject().onSave(context); }
        };
    }
}
