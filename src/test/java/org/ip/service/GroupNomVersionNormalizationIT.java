package org.ip.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.ip.model.GroupNom;
import org.ipro.data.CanonicalReadExecutor;
import org.ipro.data.CanonicalWriteExecutor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.ReadTelemetry;
import org.ipro.data.ScenarioFetchGraphResolver;
import org.ipro.fetch.plan.FetchPlanRegistry;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Регрессия: сохранение сущности с {@code version = null} при {@code id != null}.
 *
 * <p>Колонка version появилась в таблицах через {@code ddl-auto=update} уже после
 * заполнения данных — «старые» строки имеют {@code version = NULL}. При сохранении
 * такой (detached) сущности Hibernate в {@code isTransient()} видит противоречие
 * «версия говорит „новая“, id говорит „сохранённая“» и бросает
 * {@code PropertyValueException: Detached entity with generated id '1' has an
 * uninitialized version value 'null'}.</p>
 *
 * <p>C4.6: типизированного {@code GroupNomService} больше нет, а нормализация версии
 * живёт в canonical write pipeline. Тест собирает эту pipeline из тех же platform-
 * компонентов, что и production, и проверяет регрессию на целевом пути, а не на
 * удалённом compatibility-классе. RLS-граница замокана: её предмет проверяют отдельные
 * RLS-тесты, здесь важен порядок write-шагов.</p>
 */
@DataJpaTest
class GroupNomVersionNormalizationIT {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private CanonicalWriteExecutor writeExecutor;

    @BeforeEach
    void buildCanonicalWritePipeline() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        MetadataResolver metadataResolver = new MetadataResolver();
        ManagedEntityCatalog managed = new ManagedEntityCatalog(entityManagerFactory);
        SectionMetadataRegistry sections =
            new SectionMetadataRegistry("org.ip.model", metadataResolver);
        sections.afterPropertiesSet();

        EntityDescriptorCatalog catalog = new EntityDescriptorCatalog(managed, sections,
            metadataResolver, List.of(), List.of());
        ScenarioFetchGraphResolver graphResolver =
            new ScenarioFetchGraphResolver(metadataResolver, null, null);
        RlsFilterActivator rlsFilterActivator = mock(RlsFilterActivator.class);
        RlsReadGate readGate = mock(RlsReadGate.class);
        when(readGate.canRead(any(), any())).thenReturn(true);

        CanonicalReadExecutor readExecutor = new CanonicalReadExecutor(catalog, graphResolver,
            metadataResolver, rlsFilterActivator, readGate, null, ReadTelemetry.noop(),
            () -> "test-user");
        ReflectionTestUtils.setField(readExecutor, "entityManager", entityManager);

        writeExecutor = new CanonicalWriteExecutor(catalog, readExecutor, entityManager,
            validator, null, null, null, null, null, null, sections);
    }

    @Test
    void saveDetachedEntityWithNullVersionSucceeds() {
        // Обычный путь: новая запись создаётся и получает version = 0.
        GroupNom created = writeExecutor.save(GroupNom.class, new GroupNom("T-VER", "Тест версии"));
        entityManager.flush();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getVersion()).isEqualTo(0L);

        // Имитация «legacy»-строки: version = null при id != null, сущность detached.
        entityManager.clear();
        GroupNom legacy = entityManager.find(GroupNom.class, created.getId());
        legacy.setVersion(null);
        legacy.setName("Тест версии (изменено)");
        entityManager.detach(legacy);

        // До нормализации null → 0 здесь падало PropertyValueException
        // «Detached entity ... uninitialized version value».
        GroupNom saved = writeExecutor.save(GroupNom.class, legacy);

        // C4.8: pipeline выполняет flush внутри операции, поэтому наблюдаемая версия — уже
        // результат применённого UPDATE (optimistic locking: нормализованный 0 → 1), а не
        // нормализованное значение до записи. Предмет регрессии — не число, а то, что строка
        // обновилась без PropertyValueException.
        assertThat(saved.getVersion()).isEqualTo(1L);
        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.find(GroupNom.class, saved.getId()).getName())
            .isEqualTo("Тест версии (изменено)");
    }
}
