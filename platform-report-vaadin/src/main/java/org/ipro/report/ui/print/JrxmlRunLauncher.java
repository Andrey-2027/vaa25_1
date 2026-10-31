package org.ipro.report.ui.print;

import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.query.ReportContext;

/**
 * Launch seam for JRXML parameter dialogs.
 *
 * <p>The application owns the concrete Vaadin dialog and its task executor; the report
 * module only knows that a template can be launched, with or without a registry context.</p>
 */
public interface JrxmlRunLauncher {

    void open(JrxmlTemplate template);

    void open(JrxmlTemplate template, ReportContext context);
}
