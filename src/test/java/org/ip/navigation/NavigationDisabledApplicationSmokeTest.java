package org.ip.navigation;

import jakarta.persistence.EntityManagerFactory;
import org.ip.Application;
import org.ipro.navigation.NavigationAdministrationService;
import org.ipro.navigation.NavigationProfileReader;
import org.ipro.navigation.NavigationUserDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = Application.class, properties = {
    "platform.navigation.enabled=false", "app.demo-data.enabled=false", "rls.write-boundary.enforced=true",
    "spring.datasource.url=jdbc:h2:mem:navigation-ki2-disabled;DB_CLOSE_DELAY=-1",
    "vaadin.allowed-packages=org.ip.views.login,com.vaadin", "vaadin.productionMode=true"})
@ContextConfiguration(initializers = NavigationSmokeIsolation.class)
class NavigationDisabledApplicationSmokeTest {
    @Autowired ApplicationContext context;
    @Autowired EntityManagerFactory entities;
    @Autowired DataSource dataSource;

    @Test
    void disabledFeatureDoesNotRegisterServicesEntitiesOrCreateItsTables() {
        assertThat(context.getBeanNamesForType(org.ip.views.admin.NavigationProfilesAdminTab.class)).isEmpty();
        assertThat(context.getBeansOfType(NavigationAdministrationService.class)).isEmpty();
        assertThat(context.getBeansOfType(NavigationProfileReader.class)).isEmpty();
        assertThat(context.getBeansOfType(NavigationUserDirectory.class)).isEmpty();
        assertThat(entities.getMetamodel().getEntities()).noneMatch(type -> type.getJavaType().getPackageName()
            .startsWith("org.ipro.navigation.store"));
        assertThat(new JdbcTemplate(dataSource).queryForObject(
            "select count(*) from information_schema.tables where lower(table_name) like 'navigation_%'", Integer.class)).isZero();
    }
}
