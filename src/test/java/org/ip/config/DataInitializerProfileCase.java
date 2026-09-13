package org.ip.config;

import org.ip.Application;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared assertions for the independently discoverable DAC-19 profile tests. */
abstract class DataInitializerProfileCase {

    @Autowired
    protected ApplicationContext context;

    @Autowired(required = false)
    protected DataInitializer initializer;

    @Autowired
    protected UserRepository users;

    @Autowired
    protected RoleRepository roles;

    protected void assertAbsent() {
        assertThat(context.containsBean("dataInitializer")).isFalse();
        assertThat(initializer).isNull();
    }

    protected void assertPresentAndIdempotent() {
        assertThat(context.containsBean("dataInitializer")).isTrue();
        assertThat(initializer).isNotNull();
        assertThat(roles.count()).isEqualTo(3);
        assertThat(users.count()).isEqualTo(3);
        initializer.run();
        assertThat(roles.count()).isEqualTo(3);
        assertThat(users.count()).isEqualTo(3);
    }
}

@SpringBootTest(classes = Application.class,
        properties = {"spring.profiles.active=prod", "app.demo-data.enabled=true"})
class ProdProfileEvenWithPropertyIT extends DataInitializerProfileCase {
    @Test
    void initializerAbsent() {
        assertAbsent();
    }
}

@SpringBootTest(classes = Application.class,
        properties = {"spring.profiles.active=test", "app.demo-data.enabled=false"})
class TestProfileWithoutPropertyIT extends DataInitializerProfileCase {
    @Test
    void initializerAbsent() {
        assertAbsent();
    }
}

@SpringBootTest(classes = Application.class,
        properties = {"spring.profiles.active=staging", "app.demo-data.enabled=true"})
class UnlistedProfileWithPropertyIT extends DataInitializerProfileCase {
    @Test
    void initializerAbsent() {
        assertAbsent();
    }
}

@SpringBootTest(classes = Application.class,
        properties = {"spring.profiles.active=test", "app.demo-data.enabled=true"})
class TestProfileWithPropertyIT extends DataInitializerProfileCase {
    @Test
    void initializerRunsAndRerunIsIdempotent() {
        assertPresentAndIdempotent();
    }
}

@SpringBootTest(classes = Application.class,
        properties = {"spring.profiles.active=dev", "app.demo-data.enabled=true"})
class DevProfileWithPropertyIT extends DataInitializerProfileCase {
    @Test
    void initializerRunsAndRerunIsIdempotent() {
        assertPresentAndIdempotent();
    }
}

@SpringBootTest(classes = Application.class,
        properties = {"spring.profiles.active=demo", "app.demo-data.enabled=true"})
class DemoProfileWithPropertyIT extends DataInitializerProfileCase {
    @Test
    void initializerRunsAndRerunIsIdempotent() {
        assertPresentAndIdempotent();
    }
}
