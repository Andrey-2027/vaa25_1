package org.ip.application.access;

import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

/** Shared isolated context only; deliberately contains no inherited test scenarios. */
@SpringBootTest(properties = {
    "spring.config.location=classpath:/application.properties",
    "spring.datasource.url=jdbc:h2:mem:permissions_p0;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.demo-data.enabled=false", "app.security-baseline.enabled=false",
    "platform.rest.read.enabled=true", "platform.rest.web.enabled=true"
})
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PermissionIntegrationSupport {
    private UI previousUi;
    @BeforeEach void bindUi() { previousUi = UI.getCurrent(); UI.setCurrent(new UI()); }
    @AfterEach void unbindUi() { UI.setCurrent(previousUi); }
}
