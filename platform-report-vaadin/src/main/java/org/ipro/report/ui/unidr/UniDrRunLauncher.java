package org.ipro.report.ui.unidr;

import org.ipro.query.ReportContext;
import org.ipro.unidr.model.UniDrDefinition;

/**
 * Optional launch seam for saved UniDR definitions.
 *
 * <p>The report UI passes an already loaded definition and the caller's context; the
 * application adapter decides how the parameter dialog is built and which run service
 * is active. Without the adapter the UniDR launch actions are hidden.</p>
 */
public interface UniDrRunLauncher {

    void open(UniDrDefinition definition);

    void open(UniDrDefinition definition, ReportContext context);
}
