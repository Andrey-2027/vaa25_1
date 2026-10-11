package org.ipro.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.ipro.data.fixture.C4FixtureEntity;
import org.ipro.data.fixture.C4FixtureJpaConfiguration;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsHibernateWriteGuardInstaller;
import org.ipro.rls.RlsPolicyDescriptor;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C5.6 (ADR-0017 §9): вооружённая финальная граница записи для типа без RLS.
 *
 * <p>Тип {@link C4FixtureEntity} — тот самый случай: canonical write handle есть,
 * измерений RLS нет. Прежде его можно было записать прямым {@code EntityManager}: flush
 * ничего не спрашивал. Здесь в Hibernate-контекст ставится настоящий
 * {@link RlsHibernateWriteGuardInstaller} (тот же класс, что в приложении), поэтому проверка
 * идёт настоящим событием flush'а, а не вызовом слушателя «вручную»: у IDENTITY-стратегии
 * идентификатор присваивается внутри {@code persist}, и только реальное PRE_INSERT видит
 * то же состояние объекта, что и запись авторизации.</p>
 *
 * <p>Контекст намеренно отделён от {@code CanonicalWritePathIT} маркером свойства: вооружённый
 * контекст ставит слушателя в свой {@code EntityManagerFactory}, поэтому не должен делиться с
 * классом, который проверяет обычный pipeline.</p>
 *
 * <p>Реестр измерений принадлежит экземпляру: тот же объект уходит и в слушателя границы, и в
 * {@link RlsPolicyEnforcer}, который фиксирует авторизацию canonical write. Статики здесь
 * больше нет, поэтому тест не передаёт состояние соседним контекстам.</p>
 */
@DataJpaTest(properties = {"spring.sql.init.mode=never", "test.write-boundary.armed=true"})
@ContextConfiguration(classes = C4FixtureJpaConfiguration.class)
@Import({})
class CanonicalWriteFlushBoundaryIT {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private CanonicalEntityService<C4FixtureEntity> service;

    /**
     * Слушатель ставится в SessionFactory один раз: {@code @BeforeEach} вызывается на каждый
     * метод, а повторная регистрация того же слушателя — ошибка Hibernate. Контекст у этого
     * класса свой (маркерное свойство ниже), поэтому статическая защита ничего не переносит в
     * соседние классы.
     */
    private static boolean listenerInstalled;

    @BeforeEach
    void armTheBoundary() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        MetadataResolver metadataResolver = new MetadataResolver();
        ManagedEntityCatalog managed = new ManagedEntityCatalog(entityManagerFactory);
        SectionMetadataRegistry sections = new SectionMetadataRegistry(
            "org.ipro.data.fixture", metadataResolver);
        sections.afterPropertiesSet();
        EntityDescriptorCatalog catalog = new EntityDescriptorCatalog(managed, sections,
            metadataResolver, List.of(), List.of());
        ScenarioFetchGraphResolver graphResolver = new ScenarioFetchGraphResolver(
            metadataResolver, null, null);
        RlsFilterActivator filterActivator = mock(RlsFilterActivator.class);
        RlsReadGate readGate = mock(RlsReadGate.class);
        when(readGate.canRead(any(), any())).thenReturn(true);
        CanonicalReadExecutor readExecutor = new CanonicalReadExecutor(catalog, graphResolver,
            metadataResolver, filterActivator, readGate, null, ReadTelemetry.noop(), null,
            () -> "test-user", allowAllC5());
        ReflectionTestUtils.setField(readExecutor, "entityManager", entityManager);
        RlsDimensionRegistry governedRegistry = governedRegistry();
        RlsPolicyEnforcer enforcer = new RlsPolicyEnforcer(governedRegistry,
            mock(RlsReadGate.class), filterActivator, mock(AccessService.class),
            () -> "test-user");
        CanonicalWriteExecutor writeExecutor = new CanonicalWriteExecutor(catalog, readExecutor,
            entityManager, validator, enforcer, null, null, null, null, null, null, null, List.of(),
            () -> "test-user", mock(C5PermissionDecisionService.class));
        EntityDataAccess access = new CanonicalEntityDataAccess(catalog, readExecutor,
            writeExecutor, null);
        service = new CanonicalEntityService<>(C4FixtureEntity.class, readExecutor, access);

        if (!listenerInstalled) {
            new RlsHibernateWriteGuardInstaller(entityManagerFactory, governedRegistry)
                .afterPropertiesSet();
            listenerInstalled = true;
        }
    }

    /**
     * Разрешённый путь: авторизация зафиксирована canonical write и потреблена настоящим
     * PRE_INSERT. Без неё тот же самый минимальный сценарий отказывает — см. тест ниже.
     */
    @Test
    void canonicalCreateConsumesTheFixedAuthorizationAtFlush() {
        C4FixtureEntity created = service.create(new C4FixtureEntity("FL-ALLOW", "Первый"));

        assertThat(created.getId()).isNotNull();
        entityManager.flush();
        assertThat(entityManager.find(C4FixtureEntity.class, created.getId())).isNotNull();
    }

    /** Прямая запись мимо canonical path: авторизации нет, поэтому отказ приходит на flush. */
    @Test
    void directPersistWithoutCanonicalWriteIsDeniedAtFlush() {
        C4FixtureEntity direct = new C4FixtureEntity("FL-DENY", "Прямой");

        assertThatThrownBy(() -> entityManager.persist(direct))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("canonical write preflight")
            .hasMessageContaining(C4FixtureEntity.class.getName());
    }

    /**
     * Изменение managed-сущности: canonical update фиксирует авторизацию, а последующее
     * изменение того же объекта в той же транзакции — уже нет, и commit-time flush обязан
     * отказать. Это ровно то требование ADR-0017 §9, которое прежняя граница не покрывала
     * для типов без измерений.
     */
    @Test
    void updateThroughTheCanonicalPathIsAllowedAndASecondDirtyChangeIsNot() {
        C4FixtureEntity created = service.create(new C4FixtureEntity("FL-UPD", "Первый"));

        created.setName("Обновлённый");
        service.update(created);
        entityManager.flush();
        assertThat(entityManager.find(C4FixtureEntity.class, created.getId()).getName())
            .isEqualTo("Обновлённый");

        entityManager.find(C4FixtureEntity.class, created.getId()).setName("в обход");
        assertThatThrownBy(() -> entityManager.flush())
            .isInstanceOf(RlsAccessDeniedException.class);
    }

    /** Удаление: авторизация отдельная, но фиксируется тем же canonical path. */
    @Test
    void canonicalDeleteConsumesItsOwnAuthorizationAtFlush() {
        C4FixtureEntity created = service.create(new C4FixtureEntity("FL-DEL", "Первый"));

        service.delete(created.getId());
        entityManager.flush();

        assertThat(entityManager.find(C4FixtureEntity.class, created.getId())).isNull();
    }

    /** Read-решение здесь не предмет проверки: граница — про запись. */
    private static C5PermissionEvaluator allowAllC5() {
        C5PermissionEvaluator evaluator = mock(C5PermissionEvaluator.class);
        when(evaluator.isEntityReadPermitted(any(), any())).thenReturn(true);
        return evaluator;
    }

    private static RlsDimensionRegistry governedRegistry() {
        RlsDimensionRegistry registry = mock(RlsDimensionRegistry.class);
        when(registry.policyOf(any())).thenAnswer(invocation ->
            new RlsPolicyDescriptor(invocation.getArgument(0), true, Map.of(), Map.of()));
        when(registry.isWriteGoverned(C4FixtureEntity.class)).thenReturn(true);
        return registry;
    }
}
