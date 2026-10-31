package org.ipro.unidr.ui;

import org.ipro.query.ReportContext;

import org.ipro.unidr.model.UniDrDefinition;
import org.ipro.unidr.query.UniDrReferenceCatalog;
import org.ipro.unidr.run.UniDrRunService;
import org.ipro.unidr.source.UniDrSourceCatalog;
import java.time.Clock;
import java.time.ZoneId;

/** Editor drafts and saved catalogue definitions enter exactly the same run flow. */
public final class UniDrRunDialogFactory {
    private final UniDrRunService runs;
    private final UniDrReferenceCatalog references;
    private final UniDrQueryUiSupport queries;
    private final UniDrSourceCatalog catalog;
    private final Clock clock;
    public UniDrRunDialogFactory(UniDrRunService runs,UniDrReferenceCatalog references,UniDrQueryUiSupport queries,
                                 UniDrSourceCatalog catalog) {
        this(runs,references,queries,catalog,Clock.systemUTC());
    }
    UniDrRunDialogFactory(UniDrRunService runs,UniDrReferenceCatalog references,UniDrQueryUiSupport queries,
                          UniDrSourceCatalog catalog,Clock clock) {
        this.runs=runs;this.references=references;this.queries=queries;this.catalog=catalog;this.clock=clock;
    }
    public UniDrRunDialog open(UniDrDefinition snapshot) {
        return open(snapshot,ZoneId.systemDefault(),new UniDrRunDialog.Values());
    }
    public UniDrRunDialog open(UniDrDefinition snapshot,ReportContext context) {
        var dialog=new UniDrRunDialog(snapshot,runs,references,queries,catalog,clock,ZoneId.systemDefault(),new UniDrRunDialog.Values(),context);
        dialog.open();dialog.startIfNoInputs();return dialog;
    }
    UniDrRunDialog open(UniDrDefinition snapshot,ZoneId zone,UniDrRunDialog.Values values) {
        var dialog=new UniDrRunDialog(snapshot,runs,references,queries,catalog,clock,zone,values);dialog.open();dialog.startIfNoInputs();return dialog;
    }
}
