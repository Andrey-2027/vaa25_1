package org.ipro.data;

import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import org.ipro.data.fixture.C4FixtureEntity;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioFetchGraphResolverRestTest {

    private EntityManager entityManager;
    private EntityType<C4FixtureEntity> entityType;
    private ScenarioFetchGraphResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        entityManager = mock(EntityManager.class);
        var metamodel = mock(jakarta.persistence.metamodel.Metamodel.class);
        entityType = mock(EntityType.class);

        when(entityManager.getMetamodel()).thenReturn(metamodel);
        when(metamodel.entity(C4FixtureEntity.class)).thenReturn(entityType);

        SingularAttribute<C4FixtureEntity, Long> idAttr = mock(SingularAttribute.class);
        when(idAttr.isId()).thenReturn(true);
        when(idAttr.getName()).thenReturn("id");

        SingularAttribute<C4FixtureEntity, String> codeAttr = mock(SingularAttribute.class);
        when(codeAttr.isId()).thenReturn(false);
        when(codeAttr.getName()).thenReturn("code");

        when(entityType.getSingularAttributes()).thenReturn(Set.of(idAttr, codeAttr));
        when((Attribute) entityType.getAttribute("id")).thenReturn(idAttr);
        when((Attribute) entityType.getAttribute("code")).thenReturn(codeAttr);

        EntityGraph<C4FixtureEntity> graph = mock(EntityGraph.class);
        when(entityManager.createEntityGraph(C4FixtureEntity.class)).thenReturn(graph);

        MetadataResolver metadataResolver = mock(MetadataResolver.class);
        resolver = new ScenarioFetchGraphResolver(metadataResolver, null, null);
    }

    @Test
    void resolvesExplicitGraph_whenValidProfileWithIdentifier() {
        EntityGraph<C4FixtureEntity> graph = resolver.resolveRestFetchGraph(
            entityManager, C4FixtureEntity.class, List.of("id", "code"));

        assertThat(graph).isNotNull();
    }

    @Test
    void rejectsEmptyOrNullProfile() {
        assertThatThrownBy(() -> resolver.resolveRestFetchGraph(entityManager, C4FixtureEntity.class, null))
            .isInstanceOf(RestGraphException.class)
            .hasFieldOrPropertyWithValue("code", RestGraphException.Code.MISSING_PATHS);

        assertThatThrownBy(() -> resolver.resolveRestFetchGraph(entityManager, C4FixtureEntity.class, List.of()))
            .isInstanceOf(RestGraphException.class)
            .hasFieldOrPropertyWithValue("code", RestGraphException.Code.MISSING_PATHS);
    }

    @Test
    void rejectsProfileMissingIdentifier() {
        assertThatThrownBy(() -> resolver.resolveRestFetchGraph(entityManager, C4FixtureEntity.class, List.of("code")))
            .isInstanceOf(RestGraphException.class)
            .hasFieldOrPropertyWithValue("code", RestGraphException.Code.MISSING_IDENTIFIER_PATH);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsBareAssociationWithoutTerminal() {
        SingularAttribute<C4FixtureEntity, Object> targetAttr = mock(SingularAttribute.class);
        when(targetAttr.isAssociation()).thenReturn(true);
        when(targetAttr.getName()).thenReturn("target");
        when((Attribute) entityType.getAttribute("target")).thenReturn(targetAttr);

        assertThatThrownBy(() -> resolver.resolveRestFetchGraph(
            entityManager, C4FixtureEntity.class, List.of("id", "target")))
            .isInstanceOf(RestGraphException.class)
            .hasFieldOrPropertyWithValue("code", RestGraphException.Code.BARE_ASSOCIATION_NOT_ALLOWED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsCollectionPaths() {
        Attribute<C4FixtureEntity, ?> linesAttr = mock(Attribute.class);
        when(linesAttr.isCollection()).thenReturn(true);
        when(linesAttr.getName()).thenReturn("lines");
        when((Attribute) entityType.getAttribute("lines")).thenReturn(linesAttr);

        assertThatThrownBy(() -> resolver.resolveRestFetchGraph(
            entityManager, C4FixtureEntity.class, List.of("id", "lines")))
            .isInstanceOf(RestGraphException.class)
            .hasFieldOrPropertyWithValue("code", RestGraphException.Code.COLLECTION_PATH_NOT_SUPPORTED);
    }
}
