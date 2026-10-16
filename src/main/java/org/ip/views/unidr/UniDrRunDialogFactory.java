package org.ip.views.unidr;

import org.ipro.reportstudio.param.ReportContext;

import org.ip.unidr.model.UniDrDefinition;
import org.ip.unidr.query.UniDrReferenceService;
import org.ip.unidr.run.UniDrRunService;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.time.Clock;
import java.time.ZoneId;

/** Editor drafts and saved catalogue definitions enter exactly the same run flow. */
@Component
@ConditionalOnProperty(prefix="app.unidr",name="enabled",havingValue="true")
public final class UniDrRunDialogFactory {
    private final UniDrRunService runs;
    private final UniDrReferenceService references;
    private final UniDrQueryUiSupport queries;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public UniDrRunDialogFactory(UniDrRunService runs,UniDrReferenceService references,UniDrQueryUiSupport queries) {
        this(runs,references,queries,Clock.systemUTC());
    }
    UniDrRunDialogFactory(UniDrRunService runs,UniDrReferenceService references,UniDrQueryUiSupport queries,Clock clock) {
        this.runs=runs;this.references=references;this.queries=queries;this.clock=clock;
    }
    public UniDrRunDialog open(UniDrDefinition snapshot) {
        return open(snapshot,ZoneId.systemDefault(),new UniDrRunDialog.Values());
    }
    public UniDrRunDialog open(UniDrDefinition snapshot,ReportContext context) {
        var dialog=new UniDrRunDialog(snapshot,runs,references,queries,clock,ZoneId.systemDefault(),new UniDrRunDialog.Values(),context);
        dialog.open();dialog.startIfNoInputs();return dialog;
    }
    UniDrRunDialog open(UniDrDefinition snapshot,ZoneId zone,UniDrRunDialog.Values values) {
        var dialog=new UniDrRunDialog(snapshot,runs,references,queries,clock,zone,values);dialog.open();dialog.startIfNoInputs();return dialog;
    }
}
