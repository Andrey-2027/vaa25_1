package org.ip.views.forms;

import org.ip.model.GroupNom;
import org.ipro.metadata.annotation.FormConfigurationFor;
import org.ipro.metadata.annotation.SelectionColumns;
import org.springframework.context.annotation.Configuration;

/** Default column composition; column sizing follows the shared AF3.4 policy. */
@Configuration(proxyBeanMethods = false)
@FormConfigurationFor(GroupNom.class)
@SelectionColumns({"code", "name"})
public class GroupNomColumnsConfiguration {
}
