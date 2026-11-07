package org.ip.views.forms;

import org.ip.model.AccountingObjectInstallation;
import org.ipro.metadata.annotation.FormConfigurationFor;
import org.ipro.metadata.annotation.ListColumns;
import org.ipro.metadata.annotation.SelectionColumns;
import org.springframework.context.annotation.Configuration;

/** Default column composition; column sizing follows the shared AF3.4 policy. */
@Configuration(proxyBeanMethods = false)
@FormConfigurationFor(AccountingObjectInstallation.class)
@ListColumns({"number", "date", "workshop.code", "workshop.name"})
@SelectionColumns({"number", "date", "workshop.code", "workshop.name"})
public class AccountingObjectInstallationColumnsConfiguration {
}
