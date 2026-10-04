package org.ip.application.register;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.application.form.ItemFormSaveDispatcher;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ip.model.UnitOfMeasurement;
import org.ipro.data.EntityDataAccess;
import org.ipro.form.FormSaveResult;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.registry.FormResolver;
import org.ipro.rls.AccessGrant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Настоящие generic Vaadin bindings, default dispatcher, транзакции и H2; без внешней test-транзакции. */
@SpringBootTest(classes = Application.class)
class NomenclaturePriceFormTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    @MockitoBean private DataInitializer dataInitializer;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FormResolver resolver;
    @Autowired private ItemFormSaveDispatcher dispatcher;
    @Autowired private EntityDataAccess access;

    private String username;
    private UnitOfMeasurement unit;
    private Nomenclature nomenclature;
    private Nomenclature otherNomenclature;

    @BeforeEach
    void setUp() {
        username = "rf" + UUID.randomUUID().toString().substring(0, 8);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (Class<?> type : List.of(NomenclaturePrice.class, Nomenclature.class, UnitOfMeasurement.class)) {
                AccessGrant grant = new AccessGrant();
                grant.setSubjectType(AccessGrant.SubjectType.USER);
                grant.setSubjectKey(username);
                grant.setDimension("ENTITY:" + type.getName());
                grant.setCanRead(true);
                entityManager.persist(grant);
            }
            unit = new UnitOfMeasurement(username, "Тестовая единица", username);
            entityManager.persist(unit);
            nomenclature = new Nomenclature(username, "Тестовая номенклатура", unit);
            otherNomenclature = new Nomenclature(username + "-2", "Другая номенклатура", unit);
            entityManager.persist(nomenclature);
            entityManager.persist(otherNomenclature);
        });
    }

    @AfterEach
    void cleanUp() {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                if (nomenclature != null && nomenclature.getId() != null) {
                    entityManager.createQuery("delete from NomenclaturePrice p where p.nomenclature.id in :ids")
                        .setParameter("ids", List.of(nomenclature.getId(), otherNomenclature.getId())).executeUpdate();
                    entityManager.remove(entityManager.find(Nomenclature.class, nomenclature.getId()));
                    entityManager.remove(entityManager.find(Nomenclature.class, otherNomenclature.getId()));
                    entityManager.remove(entityManager.find(UnitOfMeasurement.class, unit.getId()));
                }
                entityManager.createQuery("delete from AccessGrant g where g.subjectKey = :user")
                    .setParameter("user", username).executeUpdate();
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void genericFormCreatesHistoryAndUpdatesTheResourceAfterReopening() {
        NomenclaturePrice first = saved(newForm(DAY, "100"));
        saved(newForm(DAY.plusDays(1), "150"));

        var history = access.list(NomenclaturePrice.class,
            (root, query, cb) -> cb.equal(root.get("nomenclature"), nomenclature),
            PageRequest.of(0, 10, Sort.by("period")));
        assertThat(history.getTotalElements()).isEqualTo(2);
        assertThat(history.getContent()).extracting(NomenclaturePrice::getPeriod)
            .containsExactly(DAY, DAY.plusDays(1));

        ItemForm<NomenclaturePrice> reopened = editForm(first.getId());
        assertThat(reopened.getEntityField("period", LocalDate.class).getValue()).isEqualTo(DAY);
        reopened.getEntityField("price", BigDecimal.class).setValue(new BigDecimal("90.1234"));
        saved(reopened);

        NomenclaturePrice stored = access.detail(NomenclaturePrice.class, first.getId()).orElseThrow();
        assertThat(stored.getPrice()).isEqualByComparingTo("90.1234");
        assertThat(stored.getPeriod()).isEqualTo(DAY);
        assertThat(stored.getNomenclature().getId()).isEqualTo(nomenclature.getId());
    }

    @Test
    void duplicateKeyReturnsAFriendlyFailureAndDoesNotCommitAnotherRow() {
        saved(newForm(DAY, "100"));
        FormSaveResult<NomenclaturePrice> result = newForm(DAY, "80").save();

        assertThat(result).isInstanceOf(FormSaveResult.Failure.class);
        assertThat(((FormSaveResult.Failure<?>) result).messages()).singleElement().asString()
            .contains("уже существует").doesNotContain("insert into", "23505");
        var history = access.list(NomenclaturePrice.class,
            (root, query, cb) -> cb.equal(root.get("nomenclature"), nomenclature), PageRequest.of(0, 10));
        assertThat(history.getTotalElements()).isEqualTo(1);
        assertThat(history.getContent().getFirst().getPrice()).isEqualByComparingTo("100");
    }

    @Test
    void periodChangeReturnsADomainFailureAndPreservesTheStoredKey() {
        NomenclaturePrice first = saved(newForm(DAY, "100"));
        ItemForm<NomenclaturePrice> form = editForm(first.getId());
        form.getEntityField("period", LocalDate.class).setValue(DAY.plusDays(1));

        FormSaveResult<NomenclaturePrice> result = form.save();

        assertThat(result).isInstanceOf(FormSaveResult.Failure.class);
        assertThat(((FormSaveResult.Failure<?>) result).messages()).singleElement().asString()
            .contains("Период записи регистра изменить нельзя");
        assertThat(access.detail(NomenclaturePrice.class, first.getId()).orElseThrow().getPeriod()).isEqualTo(DAY);
    }

    @Test
    void nomenclatureChangeReturnsADomainFailureAndPreservesTheStoredKey() {
        NomenclaturePrice first = saved(newForm(DAY, "100"));
        ItemForm<NomenclaturePrice> form = editForm(first.getId());
        form.<Nomenclature>entityField("nomenclature").setValue(otherNomenclature);

        FormSaveResult<NomenclaturePrice> result = form.save();

        assertThat(result).isInstanceOf(FormSaveResult.Failure.class);
        assertThat(((FormSaveResult.Failure<?>) result).messages()).singleElement().asString()
            .contains("Номенклатуру записи регистра изменить нельзя");
        assertThat(access.detail(NomenclaturePrice.class, first.getId()).orElseThrow().getNomenclature().getId())
            .isEqualTo(nomenclature.getId());
    }

    private ItemForm<NomenclaturePrice> newForm(LocalDate day, String price) {
        ItemForm<NomenclaturePrice> form = form();
        form.initializeNewEntity(Map.of("nomenclature", nomenclature, "period", day,
            "price", new BigDecimal(price)));
        return form;
    }

    private ItemForm<NomenclaturePrice> editForm(Long id) {
        ItemForm<NomenclaturePrice> form = form();
        form.setEntity(access.detail(NomenclaturePrice.class, id).orElseThrow());
        return form;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ItemForm<NomenclaturePrice> form() {
        ItemForm<NomenclaturePrice> form = resolver.resolveItemForm(NomenclaturePrice.class, null, null, null);
        form.setSaveHandler(value -> (FormSaveResult) dispatcher.save((ItemForm) value));
        return form;
    }

    private static NomenclaturePrice saved(ItemForm<NomenclaturePrice> form) {
        FormSaveResult<NomenclaturePrice> result = form.save();
        assertThat(result).isInstanceOf(FormSaveResult.Success.class);
        return ((FormSaveResult.Success<NomenclaturePrice>) result).saved();
    }
}
