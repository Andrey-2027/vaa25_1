package org.ip.views.forms;

import org.ip.model.NomenclaturePrice;
import org.ipro.metadata.annotation.FormConfigurationFor;
import org.ipro.metadata.annotation.SelectionColumns;
import org.springframework.context.annotation.Configuration;

/** Default column composition; column sizing follows the shared AF3.4 policy. */
@Configuration(proxyBeanMethods = false)
@FormConfigurationFor(NomenclaturePrice.class)
@SelectionColumns({"nomenclature", "period", "price"})
public class NomenclaturePriceColumnsConfiguration {
}
