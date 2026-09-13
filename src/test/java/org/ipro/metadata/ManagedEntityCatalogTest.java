package org.ipro.metadata;

import jakarta.persistence.EntityManagerFactory;
import org.ip.model.GroupNom;
import org.ip.model.Nomenclature;
import org.ipro.crud.ValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = org.ip.Application.class)
class ManagedEntityCatalogTest {

    @MockitoBean
    private org.ip.config.DataInitializer dataInitializer;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private ManagedEntityCatalog catalog() {
        return new ManagedEntityCatalog(entityManagerFactory);
    }

    @Test
    void resolvesRegisteredEntityWithRequiredContract() {
        assertThat(catalog().resolve(GroupNom.class.getName(), HasDisplayName.class))
            .isEqualTo(GroupNom.class);
    }

    @Test
    void exposesAllManagedEntityClassesWithoutASecondScan() {
        assertThat(catalog().managedEntityClasses())
            .contains(GroupNom.class, Nomenclature.class, org.ip.model.Journal.class)
            .doesNotContain(String.class);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> catalog().resolve("  ", HasDisplayName.class))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsUnknownClass() {
        assertThatThrownBy(() -> catalog().resolve("org.ip.model.NoSuchEntity", HasDisplayName.class))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("не является зарегистрированной JPA-сущностью");
    }

    @Test
    void rejectsManagedClassWithoutRequiredContract() {
        assertThatThrownBy(() -> catalog().resolve(Nomenclature.class.getName(), Runnable.class))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("не реализует");
    }

    @Test
    void rejectsNonEntityClass() {
        assertThatThrownBy(() -> catalog().resolve(String.class.getName(), HasDisplayName.class))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("не является зарегистрированной JPA-сущностью");
    }
}
