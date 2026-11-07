package org.ip.views.forms;

import org.ip.model.NomenclatureAccountingObject;
import org.ipro.metadata.annotation.FormConfigurationFor;
import org.ipro.metadata.annotation.ListColumns;
import org.ipro.metadata.annotation.SelectionColumns;
import org.springframework.context.annotation.Configuration;

/** Default column composition; column sizing follows the shared AF3.4 policy. */
@Configuration(proxyBeanMethods = false)
@FormConfigurationFor(NomenclatureAccountingObject.class)
@ListColumns({"workshop.code", "nomenclature.code", "nomenclature.name", "period", "accountingType.name"})
@SelectionColumns({"workshop.code", "nomenclature.code", "nomenclature.name", "period", "accountingType.name"})
public class NomenclatureAccountingObjectColumnsConfiguration {
}
