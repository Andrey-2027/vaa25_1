package org.ip.views.forms;

import org.ip.model.PrdSpec;
import org.ipro.metadata.annotation.FormConfigurationFor;
import org.ipro.metadata.annotation.SelectionColumns;
import org.springframework.stereotype.Component;

/** Static selection columns; item layout and behavior belong to PrdSpecItemForm. */
@FormConfigurationFor(PrdSpec.class)
@SelectionColumns({"codeSpec", "nomenclature.name"})
@Component
public class PrdSpecFormConfig { }
