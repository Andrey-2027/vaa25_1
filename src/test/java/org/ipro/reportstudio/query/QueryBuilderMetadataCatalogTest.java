package org.ipro.reportstudio.query;

import org.ip.Application;
import org.ip.model.PrdSpec;
import org.ip.repository.UserRepository;
import org.ip.security.UserRepositoryRlsRoleResolver;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.RlsDimensionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@EnableJpaRepositories(basePackages = {"org.ip", "org.ipro.rls"})
@ContextConfiguration(classes = Application.class)
class QueryBuilderMetadataCatalogTest {
    @Autowired private jakarta.persistence.EntityManagerFactory emf;
    @Autowired private UserRepository users;
    @Autowired private AccessGrantRepository grants;
    private QueryBuilderMetadataCatalog catalog;

    @BeforeEach
    void setUp() {
        var registry = new RlsDimensionRegistry("org.ip");
        registry.rebuild();
        var access = new AccessService(grants, new UserRepositoryRlsRoleResolver(users), registry);
        catalog = new QueryBuilderMetadataCatalog(emf, new org.ipro.metadata.MetadataResolver(),
                new RlsReadGate(access, registry), () -> "admin");
    }

    @Test
    void rootsExposeAnnotatedEntitiesAndPrdSpecAssociations() {
        var root = catalog.root(PrdSpec.class.getSimpleName());
        assertThat(root.fields()).extracting(QueryBuilderMetadataCatalog.Field::name)
                .contains("codeSpec");
        assertThat(root.associations()).extracting(QueryBuilderMetadataCatalog.Association::name)
                .contains("journal", "nomenclature");
        assertThat(root.associations()).allMatch(a -> a.targetType() != null);
    }

    @Test
    void unknownEntityIsNotAllowed() {
        assertThatThrownBy(() -> catalog.root("UnknownEntity"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не разрешена");
    }
}
