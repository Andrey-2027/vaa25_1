package com.example.materialrequests.ui;

import com.example.materialrequests.model.MaterialRequest;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.form.builtin.ItemForm;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class RequestFormCustomization implements ItemFormCustomization {
    private final org.ipro.rls.RlsCurrentUser user;
    public RequestFormCustomization(org.ipro.rls.RlsCurrentUser user) { this.user = user; }
    @Override public Class<?> entityClass() { return MaterialRequest.class; }
    @Override public void configure(ItemFormVariants variants) {
        variants.addDefault(ctx -> {
            var form = new ItemForm<>(MaterialRequest.class,
                ctx.metadataResolver().resolveRowMetadata(MaterialRequest.class).getFormFields().stream()
                        .filter(field -> List.of("number", "date", "departmentId", "status", "comment")
                                .contains(field.getName())).toList(), ctx.fieldFactory());
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
