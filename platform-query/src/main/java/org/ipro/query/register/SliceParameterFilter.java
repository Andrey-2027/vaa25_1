package org.ipro.query.register;

import org.ipro.register.SliceSpec;
import org.ipro.query.VisualQueryParameter;

/** A report operand, never a literal in the canonical register reader's SliceSpec. */
public record SliceParameterFilter(String field, SliceSpec.Operator operator, String parameter, boolean afterSlice) {
    public SliceParameterFilter {
        if (field == null || !field.matches("[A-Za-z_][A-Za-z0-9_]*") || operator == null)
            throw new IllegalArgumentException("Некорректный отбор среза");
        new VisualQueryParameter(parameter);
        if (parameter.startsWith("sliceId_")) throw new IllegalArgumentException("Имя sliceId_ зарезервировано для внутренних параметров");
    }
}
