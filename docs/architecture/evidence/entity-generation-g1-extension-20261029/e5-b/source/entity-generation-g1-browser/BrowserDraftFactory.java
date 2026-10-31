package org.ip.views.admin;
import org.ipro.design.ProjectDesignContext;
public final class BrowserDraftFactory {
    public static EntityDraftEditorView create(ProjectDesignContext context){
        var v=new EntityDraftEditorView(new EntityDraftAccess(true,new EntityExplorerAccess()));
        v.setContextSource(()->context);v.initialize(()->{});return v;
    }
}
