package org.ipro.unidr.query;

import org.ipro.query.ReportContext;
import org.ipro.query.ReportQueryAssembler;
import org.ipro.unidr.model.UniDrDefinition;
import org.ipro.unidr.model.UniDrDefinition.Literal;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Application-owned execution seam for a native UniDR source.
 *
 * <p>The application owns the JPQL/joins and verifies the returned schema; the module owns the
 * detached read transaction, parameter resolution and rendering hand-off.</p>
 */
public interface UniDrRunQueryAdapter {

    /** Application-owned detached schema the native source must return. */
    Map<String, Class<?>> expectedSchema();

    ReportQueryAssembler assemble(UniDrDefinition definition, Map<String, Literal> resolved,
                                  ReportContext context);

    ReportQueryAssembler applyFilters(ReportQueryAssembler query, UniDrDefinition definition,
                                      Map<String, Object> values,
                                      BiConsumer<String, Long> checkReference);
}
