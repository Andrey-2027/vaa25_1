package org.ip.views.forms;

import org.ip.model.NomSklAttribute;
import org.ipro.metadata.annotation.FormConfigurationFor;
import org.ipro.metadata.annotation.SelectionColumns;
import org.springframework.context.annotation.Configuration;

/** Default column composition; column sizing follows the shared AF3.4 policy. */
@Configuration(proxyBeanMethods = false)
@FormConfigurationFor(NomSklAttribute.class)
@SelectionColumns({"nomenclature", "attrType", "required"})
public class NomSklAttributeColumnsConfiguration {
}
