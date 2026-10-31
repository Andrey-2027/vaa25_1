package org.ipro.unidr.model;

import org.ipro.query.document.ReportComputedValue;
import org.ipro.query.document.ReportParamKind;
import org.ipro.query.document.ReportParamSource;
import org.ipro.query.document.ReportQuerySource;

import java.util.List;

/**
 * Immutable query/parameter recipe of a UniDR report. No UDR template is stored,
 * cloned or exposed: the editor receives it as a neutral query draft.
 *
 * <p>Parameter enum names stay on the reportstudio enum contract because they are
 * part of the persisted v1/v2 document; mapping to the neutral query model lives in
 * {@code UniDrQueryDefinitions}.</p>
 */
public record UniDrQuerySource(ReportQuerySource kind, String jpql, String visualQueryJson,
                               List<Param> parameters, String tableEntity) {
    public static final String ID = "query";

    public UniDrQuerySource {
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
    }

    public UniDrQuerySource(ReportQuerySource kind, String jpql, String visualQueryJson, List<Param> parameters) {
        this(kind, jpql, visualQueryJson, parameters, null);
    }

    public record Param(String name, String caption, ReportParamKind kind, String entityClass,
                        ReportParamSource valueSource, boolean required, boolean showOnForm,
                        String defaultValue, ReportComputedValue computed, int position) {
    }
}
