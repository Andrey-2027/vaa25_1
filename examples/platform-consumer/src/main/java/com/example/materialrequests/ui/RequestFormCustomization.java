package com.example.materialrequests.ui;

import com.example.materialrequests.model.MaterialRequest;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.layout.Layout;
import org.springframework.stereotype.Component;

@Component
public class RequestFormCustomization implements ItemFormCustomization {
    private final org.ipro.rls.RlsCurrentUser user;
    public RequestFormCustomization(org.ipro.rls.RlsCurrentUser user) { this.user = user; }
    @Override public Class<?> entityClass() { return MaterialRequest.class; }
    @Override public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> {
            var metadata = ctx.metadataResolver().resolve(MaterialRequest.class);
            var layout = new Layout(metadata);
            layout.of(layout.field("number").setReadOnly(true), layout.field("date"),
                    layout.field("departmentId"), layout.field("status"), layout.field("comment"));
            var form = new ItemForm<MaterialRequest>(metadata, ctx.fieldFactory(), layout.compile());
            form.setEntityFactory(() -> {
                var request = new MaterialRequest();
                String username = user.requireAuthenticatedUsername();
                request.setDepartmentId(switch (username) {
                    case "alice" -> 1L;
                    case "bob" -> 2L;
                    default -> throw new IllegalStateException("Не задано подразделение пользователя");
                });
                return request;
            });
            return form;
        });
    }
}
