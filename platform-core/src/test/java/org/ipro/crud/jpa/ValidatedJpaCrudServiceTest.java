package org.ipro.crud.jpa;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.EntityLifecycle;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.lifecycle.EntitySaveContext;
import org.ipro.lifecycle.EntityUpdateContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест минимальной CRUD-базы (план reportstudio-reverse-deps, 2.3):
 * bean-валидация на save, reference-check на delete, проброс repository.
 */
class ValidatedJpaCrudServiceTest {

    private JpaRepository<NamedEntity, Long> repository;
    private ReferenceCheckService referenceCheckService;
    private NamedEntityService service;

    @BeforeEach
    void setUp() {
        repository = mock(JpaRepository.class);
        referenceCheckService = mock(ReferenceCheckService.class);
        service = new NamedEntityService(repository,
                Validation.buildDefaultValidatorFactory().getValidator(),
                referenceCheckService);
    }

    @Test
    void saveValidatesBeanConstraintsBeforeRepositorySave() {
        NamedEntity invalid = new NamedEntity(" ");

        assertThatThrownBy(() -> service.save(invalid))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name");
        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void savePassesValidEntityToRepository() {
        NamedEntity entity = new NamedEntity("valid");
        when(repository.save(entity)).thenReturn(entity);

        assertThat(service.save(entity)).isSameAs(entity);
        verify(repository).save(entity);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onSaveRunsAfterRepositorySaveInsideTheCrudPipeline() {
        EntityLifecycle<NamedEntity> lifecycle = mock(EntityLifecycle.class);
        when(lifecycle.entityType()).thenReturn(NamedEntity.class);
        ReflectionTestUtils.setField(service, "entityLifecycleRegistry",
            Optional.of(new EntityLifecycleRegistry(List.of(lifecycle))));
        NamedEntity entity = new NamedEntity("valid");
        when(repository.save(entity)).thenReturn(entity);

        service.save(entity);

        var order = inOrder(lifecycle, repository);
        order.verify(lifecycle).beforeSave(org.mockito.ArgumentMatchers.any(EntitySaveContext.class));
        order.verify(repository).save(entity);
        order.verify(lifecycle).onSave(org.mockito.ArgumentMatchers.any(EntitySaveContext.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void updateDispatchesBeforeUpdateWithOriginalAndUpdatedState() {
        EntityLifecycle<NamedEntity> lifecycle = mock(EntityLifecycle.class);
        when(lifecycle.entityType()).thenReturn(NamedEntity.class);
        ReflectionTestUtils.setField(service, "entityLifecycleRegistry",
            Optional.of(new EntityLifecycleRegistry(List.of(lifecycle))));

        NamedEntity original = new NamedEntity("old");
        original.setId(7L);
        NamedEntity updated = new NamedEntity("new");
        updated.setId(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(original));
        when(repository.save(updated)).thenReturn(updated);

        service.update(updated);

        verify(lifecycle).beforeUpdate(org.mockito.ArgumentMatchers.argThat(
            context -> context.changed(NamedEntity::getName)));
    }

    @Test
    void deleteChecksReferencesBeforeDeleting() {
        doThrow(new ValidationException("есть ссылки"))
                .when(referenceCheckService).checkNoReferences(NamedEntity.class, 7L);

        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(ValidationException.class);
        verify(repository, org.mockito.Mockito.never()).deleteById(7L);
    }

    @Test
    void deleteWithoutReferencesDeletesById() {
        service.delete(7L);
        verify(repository).deleteById(7L);
    }

    @Test
    void findByIdAndFindAllDelegateToRepository() {
        NamedEntity entity = new NamedEntity("x");
        when(repository.findById(1L)).thenReturn(Optional.of(entity));
        when(repository.findAll()).thenReturn(List.of(entity));
        Page<NamedEntity> page = new PageImpl<>(List.of(entity));
        when(repository.findAll(any(PageRequest.class))).thenReturn(page);

        assertThat(service.findById(1L)).contains(entity);
        assertThat(service.findAll()).containsExactly(entity);
        assertThat(service.findAll(PageRequest.of(0, 10))).isSameAs(page);
    }

    @Test
    void searchIsNotPartOfTheMinimalBaseContract() {
        assertThatThrownBy(() -> service.search("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> service.search("x", PageRequest.of(0, 10)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    static class NamedEntityService extends ValidatedJpaCrudService<NamedEntity> {
        NamedEntityService(JpaRepository<NamedEntity, Long> repository, Validator validator,
                           ReferenceCheckService referenceCheckService) {
            super(repository, validator, referenceCheckService);
        }
    }

    public static class NamedEntity extends BaseEntity {
        @NotBlank
        private String name;

        public NamedEntity(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}
