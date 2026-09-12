package org.ip.application.document;

import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.AggregateSaveContext;
import org.ipro.lifecycle.EntityLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Lifecycle-правила агрегата {@link PrdSpec}. */
@Component
public class PrdSpecLifecycle implements EntityLifecycle<PrdSpec> {

    @Override
    public Class<PrdSpec> entityType() {
        return PrdSpec.class;
    }

    /** Компонент спецификации не может быть самой собираемой единицей. */
    @Override
    public void beforeAggregateSave(AggregateSaveContext<PrdSpec> context) {
        PrdSpec header = context.aggregate();
        Nomenclature assembly = header.getNomenclature();
        if (assembly == null || assembly.getId() == null) {
            return;
        }

        List<String> errors = new ArrayList<>();
        int lineNumber = 0;
        for (PrdSpecMtr material : context.section(PrdSpecMtr.class)) {
            lineNumber++;
            if (material == null) {
                continue;
            }
            Nomenclature component = material.getNomenclature();
            if (component != null && assembly.getId().equals(component.getId())) {
                errors.add("Строка " + lineNumber
                    + ": компонент не может быть самой собираемой единицей "
                    + "(номенклатура id=" + assembly.getId() + ")");
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(String.join(System.lineSeparator(), errors));
        }
    }
}
