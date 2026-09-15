package org.ipro.data;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ipro.crud.BaseService;
import org.ipro.fetch.instance.InstanceNameBridge;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4.8 (план п.6, §7.8): detached render уже полученного list/detail/lookup не выполняет
 * скрытых запросов.
 *
 * <p>Это cost-проверка, а не функциональная: важно не «вернулось ли имя», а что рендер
 * отдаёт его без обращения к БД. Раньше UI-управляемые пути (per-reference reload,
 * неинициализированные прокси) давали N+1, незаметный по результату.</p>
 *
 * <p>Имя читается через proxy-safe мост ({@code InstanceNameBridge}) — тем самым проверяется
 * рендер без инициализации ссылок, а счётчики Hibernate подтверждают отсутствие запросов.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
@Transactional
class DetachedRenderCostIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private EntityDataAccessResolver resolver;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private UnitOfMeasurementRepository unitOfMeasurementRepository;

    @Autowired
    private NomenclatureRepository nomenclatureRepository;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void loginAsSuperuser() {
        String username = "detached-render-" + UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));

        AccessGrant wildcard = new AccessGrant();
        wildcard.setSubjectType(AccessGrant.SubjectType.USER);
        wildcard.setSubjectKey(username);
        wildcard.setDimension("*");
        wildcard.setCanRead(true);
        wildcard.setCanUpdate(true);
        wildcard.setCanDelete(true);
        accessGrantRepository.saveAndFlush(wildcard);
    }

    @Test
    @SuppressWarnings("unchecked")
    void detachedRenderAfterListTriggersNoHiddenQueries() {
        String suffix = uniqueSuffix();
        String unitCode = "U" + suffix;
        String nomCode = "N" + suffix;
        UnitOfMeasurement unit = unitOfMeasurementRepository
            .save(new UnitOfMeasurement(unitCode, "Штука", unitCode));
        nomenclatureRepository.save(new Nomenclature(nomCode, "Деталь", unit));
        entityManager.flush();
        entityManager.clear();

        BaseService<Nomenclature, Long> service = resolver
            .<Nomenclature, Long>findService(Nomenclature.class)
            .orElseThrow(() -> new AssertionError("Nomenclature должен иметь canonical handle"));
        List<Nomenclature> rendered = service.findAll();
        // База тестового прогона общая: выбираем ровно свою строку, а не «единственную».
        Nomenclature mine = rendered.stream()
            .filter(n -> nomCode.equals(n.getCode()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("созданная номенклатура должна быть в выдаче"));

        // Отсоединяем результат: рендер обязан обойтись уже загруженным состоянием.
        entityManager.clear();

        Statistics statistics = statistics();
        statistics.clear();

        String label = InstanceNameBridge.displayName(mine);

        assertThat(label).isNotBlank();
        assertThat(statistics.getQueryExecutionCount())
            .as("рендер отсоединённого результата не должен читать БД")
            .isZero();
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void detachedDetailRenderTriggersNoHiddenQueries() {
        String suffix = uniqueSuffix();
        String unitCode = "U" + suffix;
        UnitOfMeasurement unit = unitOfMeasurementRepository
            .save(new UnitOfMeasurement(unitCode, "Штука", unitCode));
        Nomenclature saved =
            nomenclatureRepository.save(new Nomenclature("N" + suffix, "Узел", unit));
        entityManager.flush();
        entityManager.clear();

        // detail идёт через canonical facade, а не через BaseService.findById — иначе
        // Long-ID пересекается с CrudService.findById(Long) и вызов неоднозначен.
        java.util.Optional<Nomenclature> found = resolver.resolve(Nomenclature.class)
            .detail(Nomenclature.class, saved.getId());
        assertThat(found).isPresent();
        entityManager.clear();

        Statistics statistics = statistics();
        statistics.clear();

        String label = InstanceNameBridge.displayName(found.get());

        assertThat(label).isNotBlank();
        assertThat(statistics.getQueryExecutionCount()).isZero();
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    /**
     * Короткий уникальный суффикс: база тестового прогона общая, а у {@code code} и
     * {@code shortCode} наших справочников есть {@code @Size(max = 10)}.
     */
    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }
}
