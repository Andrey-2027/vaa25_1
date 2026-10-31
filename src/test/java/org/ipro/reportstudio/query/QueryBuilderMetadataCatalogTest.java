package org.ipro.reportstudio.query;

import org.ipro.query.*;
import org.ipro.query.param.*;

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
import org.ipro.rls.config.RlsPersistenceAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
// org.ip объявлен в Application#@EnableJpaRepositories (иначе дублирование бобов репозиториев в срезе)
@ImportAutoConfiguration(RlsPersistenceAutoConfiguration.class)
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
        var types = new org.ipro.metadata.ManagedEntityTypes(emf);
        var metadata = new org.ipro.metadata.MetadataResolver();
        var managed = new org.ipro.metadata.ManagedEntityCatalog(types);
        var registers = new org.ipro.register.RegisterRegistry(managed,
            new org.ipro.register.RecorderTypeRegistry(managed, emf, metadata));
        var descriptors = new org.ipro.data.EntityDescriptorCatalog(managed,
            new org.ipro.metadata.SectionMetadataRegistry(types, metadata), metadata,
            java.util.List.of(), java.util.List.of(), java.util.List.of(new org.ipro.register.RegisterClassificationContributor(registers)));
        catalog = new QueryBuilderMetadataCatalog(types, metadata,
                new RlsReadGate(org.ipro.rls.RlsTestFixture.allowingC5ReadEvaluator(), registry),
                () -> "admin", descriptors);
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
    void existingDictionariesDeclareTheirCatalogCategory() {
        for (String name : java.util.List.of("Workshop", "Journal", "UnitOfMeasurement", "GroupNom", "Branch", "AttributeType", "AttributeValue")) {
            assertThat(catalog.root(name).category()).as(name).isEqualTo("Справочник");
        }
    }

    @Test
    void unknownEntityIsNotAllowed() {
        assertThatThrownBy(() -> catalog.root("UnknownEntity"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не разрешена");
    }
}
