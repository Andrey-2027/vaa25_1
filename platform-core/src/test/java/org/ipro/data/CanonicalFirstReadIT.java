package org.ipro.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.ipro.data.fixture.C4FixtureEntity;
import org.ipro.data.fixture.C4FixtureJpaConfiguration;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadGate;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.rls.c5.C5PermissionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Р9, bounded/first read: {@link EntityDataAccess#first} — одна строка и <b>без</b> count-запроса.
 *
 * <p>Замер идёт Hibernate {@link Statistics} на том же изолированном persistence unit, что у
 * {@link CanonicalWritePathIT}, и сравнивает новый intent с тем способом, которым то же чтение
 * выполнялось раньше (Р8: {@code RegisterReader.getLast} через paged list с размером страницы 1):
 * {@code list(0, 1)} обязан показать два запроса — content и count, — а {@code first} один.
 * Число запросов здесь и есть предмет проверки, поэтому потолок не «примерно один-два», а точное
 * равенство: разница между ними и есть стоимость лишнего count.</p>
 *
 * <p>Parity проверяется на тех же данных: победитель сортировки, возвращённый {@code first},
 * совпадает с первой строкой {@code list}. Отказ gate проверяется отдельно, потому что «пустой
 * результат» не должен означать «запрос не выполнялся» и наоборот.</p>
 */
@DataJpaTest(properties = "spring.sql.init.mode=never")
@ContextConfiguration(classes = C4FixtureJpaConfiguration.class)
@Import({})
class CanonicalFirstReadIT {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private MetadataResolver metadataResolver;
    private EntityDescriptorCatalog catalog;
    private ScenarioFetchGraphResolver graphResolver;
    private RlsFilterActivator rlsFilterActivator;
    private CanonicalReadExecutor readExecutor;
    private CanonicalWriteExecutor writeExecutor;
    private EntityDataAccess access;

    @BeforeEach
    void buildCanonicalStack() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        metadataResolver = new MetadataResolver();
        ManagedEntityCatalog managed = new ManagedEntityCatalog(entityManagerFactory);
        SectionMetadataRegistry sections = new SectionMetadataRegistry(
            "org.ipro.data.fixture", metadataResolver);
        sections.afterPropertiesSet();

        catalog = new EntityDescriptorCatalog(managed, sections, metadataResolver,
            List.of(), List.of());
        graphResolver = new ScenarioFetchGraphResolver(metadataResolver, null, null);
        rlsFilterActivator = mock(RlsFilterActivator.class);
        RlsReadGate readGate = mock(RlsReadGate.class);
        when(readGate.canRead(any(), any())).thenReturn(true);
        readExecutor = new CanonicalReadExecutor(catalog, graphResolver, metadataResolver,
            rlsFilterActivator, readGate, null, ReadTelemetry.noop(), null,
            () -> "test-user", allowAllC5());
        ReflectionTestUtils.setField(readExecutor, "entityManager", entityManager);

        writeExecutor = new CanonicalWriteExecutor(catalog, readExecutor, entityManager,
            validator, null, null, null, null, null, null, null, null, List.of(),
            () -> "test-user", allowAllOperations());
        access = new CanonicalEntityDataAccess(catalog, readExecutor, writeExecutor, null);
    }

    @Test
    void firstReadReturnsTheSortedWinnerWithOneQueryWhileThePagedListPaysForCount() {
        seed("A-1", "Первый");
        seed("A-2", "Второй");
        seed("A-3", "Третий");
        Sort newestFirst = Sort.by(Sort.Order.desc("code"));
        Statistics statistics = statistics();

        statistics.clear();
        Optional<C4FixtureEntity> viaList = access.list(C4FixtureEntity.class, null,
            PageRequest.of(0, 1, newestFirst)).getContent().stream().findFirst();
        long listQueries = statistics.getQueryExecutionCount();

        statistics.clear();
        Optional<C4FixtureEntity> viaFirst = access.first(C4FixtureEntity.class, null, newestFirst);
        long firstQueries = statistics.getQueryExecutionCount();

        assertThat(viaList).isPresent();
        assertThat(viaFirst).isPresent();
        assertThat(viaFirst.get().getId())
            .as("bounded read обязан вернуть ту же строку, что первая строка paged list")
            .isEqualTo(viaList.get().getId());
        assertThat(viaFirst.get().getCode()).isEqualTo("A-3");
        assertThat(listQueries)
            .as("paged list платит за content + count — прежний способ getLast")
            .isEqualTo(2);
        assertThat(firstQueries)
            .as("first не выполняет второй запрос: total не запрашивается")
            .isEqualTo(1);
    }

    @Test
    void firstReadReturnsEmptyForANonMatchingFilterWithinTheSameSingleQuery() {
        seed("A-1", "Первый");
        Statistics statistics = statistics();
        statistics.clear();

        Optional<C4FixtureEntity> none = access.first(C4FixtureEntity.class,
            (root, query, cb) -> cb.equal(root.get("code"), "нет-такого"), Sort.by("code"));

        assertThat(none).isEmpty();
        assertThat(statistics.getQueryExecutionCount())
            .as("пустой результат — тоже одно чтение, без уточняющего count")
            .isEqualTo(1);
    }

    @Test
    void firstReadAsksTheReadGateAndSkipsTheQueryWhenAccessIsDenied() {
        seed("A-1", "Первый");
        Statistics statistics = statistics();
        statistics.clear();

        Optional<C4FixtureEntity> denied = accessWithDenyingRlsGate()
            .first(C4FixtureEntity.class, null, Sort.by("code"));

        assertThat(denied).isEmpty();
        assertThat(statistics.getQueryExecutionCount())
            .as("отказ gate — это невыполненный запрос, а не пустая таблица")
            .isZero();
    }

    @Test
    void firstReadWithoutAnExplicitOrderIsRejectedInsteadOfReturningAnArbitraryRow() {
        seed("A-1", "Первый");
        Statistics statistics = statistics();
        statistics.clear();

        assertThatThrownBy(() -> access.first(C4FixtureEntity.class, null, Sort.unsorted()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("без порядка");
        assertThatThrownBy(() -> access.first(C4FixtureEntity.class, null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("sort");
        assertThat(statistics.getQueryExecutionCount())
            .as("неопределённый порядок отклоняется до SQL, а не превращается в случайную строку")
            .isZero();
    }

    @Test
    void firstReadCannotBeWidenedIntoAnUnboundedReadByItsCaller() {
        assertThatThrownBy(() -> readExecutor.readFirst(PageRead.of(C4FixtureEntity.class,
            FetchScenario.LIST, null, PageRequest.of(0, 10, Sort.by("code")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("одну строку");
    }

    private void seed(String code, String name) {
        entityManager.persist(new C4FixtureEntity(code, name));
        entityManager.flush();
        entityManager.clear();
    }

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    private EntityDataAccess accessWithDenyingRlsGate() {
        RlsReadGate denying = mock(RlsReadGate.class);
        when(denying.canRead(any(), any())).thenReturn(false);
        CanonicalReadExecutor denyingExecutor = new CanonicalReadExecutor(catalog, graphResolver,
            metadataResolver, rlsFilterActivator, denying, null, ReadTelemetry.noop(), null,
            () -> "test-user", allowAllC5());
        ReflectionTestUtils.setField(denyingExecutor, "entityManager", entityManager);
        return new CanonicalEntityDataAccess(catalog, denyingExecutor, writeExecutor, null);
    }

    private C5PermissionEvaluator allowAllC5() {
        C5PermissionEvaluator evaluator = mock(C5PermissionEvaluator.class);
        when(evaluator.isEntityReadPermitted(any(), any())).thenReturn(true);
        return evaluator;
    }

    /** Явный allow-all double: тест проверяет форму чтения, а не решение о правах на запись. */
    private static C5PermissionDecisionService allowAllOperations() {
        return mock(C5PermissionDecisionService.class);
    }
}
