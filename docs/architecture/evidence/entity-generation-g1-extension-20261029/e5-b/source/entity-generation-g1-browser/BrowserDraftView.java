package org.ipro.e5browser;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.runtime.EntityDesignRuntimeProjection;
import org.ip.views.admin.BrowserDraftFactory;
import java.nio.file.*;
@Route("draft") @RolesAllowed("ADMIN")
public class BrowserDraftView extends VerticalLayout {
    public BrowserDraftView(EntityDesignRuntimeProjection projection)throws Exception{
        setSizeFull();add(new Anchor("/","← Формы E5.B"));
        var context=new ObjectMapper().readValue(Path.of(System.getProperty("e5.input"),"sections/owned-catalog-2/current-context.json").toFile(),ProjectDesignContext.class);
        var editor=BrowserDraftFactory.create(context.withRuntimeSnapshot(projection.snapshot()));
        editor.setSizeFull();add(editor);expand(editor);
    }
}
