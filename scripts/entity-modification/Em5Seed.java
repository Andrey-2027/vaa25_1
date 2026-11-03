package com.example.entitytypes.em5;

import com.example.entitytypes.config.FixtureData;
import com.example.entitytypes.model.CustomCatalog;
import com.example.entitytypes.model.GeneratedItem;
import java.util.List;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.assertThat;

/** EM5, first JVM: the schema is created from the model as it was and rows are written. */
@SpringBootTest(properties = {"vaadin.productionMode=true", "consumer.demo-password=em5-test-only"})
class Em5Seed {
    @Autowired EntityDataAccess data;
    @Autowired AccessGrantRepository grants;

    @Test void rowsAreWrittenBeforeAnyChange() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "writer", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        FixtureData.grant(grants, "writer", C5PermissionKeys.entity(GeneratedItem.class), null, true);
        FixtureData.grant(grants, "reader", C5PermissionKeys.entity(GeneratedItem.class), null, false);
        grants.flush();

        var catalog = new CustomCatalog();
        catalog.setExternalKey("EM5-OLD");
        catalog.setCaption("Старая подпись");
        assertThat(data.create(CustomCatalog.class, catalog).getId()).isNotNull();

        var item = new GeneratedItem();
        item.setName("Старый элемент");
        item.setTitle("old title");
        assertThat(data.create(GeneratedItem.class, item).getId()).isNotNull();
    }
}
