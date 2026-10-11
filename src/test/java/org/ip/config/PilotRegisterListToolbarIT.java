package org.ip.config;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.application.register.AccountingObjectSaveService;
import org.ip.model.NomenclatureAccountingObject;
import org.ip.model.NomenclaturePrice;
import org.ip.repository.RoleRepository;
import org.ip.repository.UserRepository;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.data.EntityDataAccess;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.registry.FormResolver;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Права самой пилотной фикстуры глазами тулбара списка: серая или отсутствующая «Создать» у
 * регистра — это отсутствие права, а не свойство регистра.
 *
 * <p><b>Что проверяется.</b> Фикстура заводит данные и права одним телом
 * ({@link AccountingObjectPilotInitializer#seedFixture()}), и до сих пор никто не проверял, что
 * выданного ею набора достаточно для кнопок. Между тем именно здесь возможен разрыв: фикстура
 * выдаёт права только на первой подготовке базы, поэтому база, подготовленная раньше, чем
 * фикстура объявила ключ операций, остаётся без него — и решение по действию отказывает, хотя тип
 * операцию поддерживает.</p>
 *
 * <p>Проверяются два факта на настоящих правах пилота, а не на синтетических грантах:</p>
 * <ul>
 *   <li>у самостоятельного регистра «Цены номенклатуры» «Создать» видна и доступна — выданного
 *       фикстурой права на операцию хватает решению;</li>
 *   <li>у подчинённого регистра «Объекты учёта номенклатуры» список открыт только для просмотра, и
 *       кнопок записи в нём нет: строка принадлежит регистратору, а не списку. Это решение формы о
 *       просмотре, а не отказ в правах, — право на создание у типа при этом есть.</li>
 * </ul>
 *
 * <p><b>Почему наполнение откатывается.</b> Тело фикстуры вызывается напрямую (её {@code run()}
 * защищён требованием отдельной базы {@code accounting_object_pilot_*}), а транзакция помечается
 * {@code rollbackOnly}: проверка прав не оставляет после себя пилотных строк, но оценивает то же
 * состояние, что и пилотный запуск.</p>
 */
@SpringBootTest(classes = Application.class)
class PilotRegisterListToolbarIT {

    @MockitoBean
    private DataInitializer demoSeeding;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityDataAccess access;

    @Autowired
    private MetadataDrivenAggregateSaveService aggregates;

    @Autowired
    private AccountingObjectSaveService documents;

    @Autowired
    private AccessGrantRepository grants;

    @Autowired
    private UserRepository users;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private FormResolver formResolver;

    @Autowired
    private ActionRegistry actionRegistry;

    @Autowired
    private ActionContextProvider actionContextProvider;

    @Autowired
    private ActionHandlerRegistry actionHandlerRegistry;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Тулбар на правах фикстуры: «Создать» есть у самостоятельного регистра и нет у подчинённого. */
    @Test
    void thePilotRightsOfferCreateOnlyForTheIndependentRegister() {
        transactions.executeWithoutResult(status -> {
            initializer().seedFixture();
            asPilot(() -> {
                ListForm<NomenclaturePrice, Long> price =
                    formResolver.resolveListForm(NomenclaturePrice.class, null, null);
                assertThat(Boolean.valueOf(price.isReadOnly())).isFalse();
                assertThat(Boolean.valueOf(price.getAddButton().isVisible())).isTrue();
                assertThat(Boolean.valueOf(price.getAddButton().isEnabled())).isTrue();

                ListForm<NomenclatureAccountingObject, Long> recorderOwned =
                    formResolver.resolveListForm(NomenclatureAccountingObject.class, null, null);
                assertThat(Boolean.valueOf(recorderOwned.isReadOnly())).isTrue();
                assertThat(Boolean.valueOf(recorderOwned.getAddButton().isVisible())).isFalse();
            });
            status.setRollbackOnly();
        });
    }

    /**
     * Решение по созданию на правах фикстуры положительное у обоих регистров: причина отсутствия
     * кнопки у подчинённого — просмотр, а не права. Если это перестанет быть так, серая «Создать»
     * у самостоятельного регистра будет означать «фикстура не выдала ключ операций».
     */
    @Test
    void thePilotFixtureGrantsTheOperationsTheToolbarAsksFor() {
        transactions.executeWithoutResult(status -> {
            initializer().seedFixture();
            asPilot(() -> {
                assertThat(createDecision(NomenclaturePrice.class).actionable()).isTrue();
                assertThat(createDecision(NomenclatureAccountingObject.class).actionable()).isTrue();
            });
            status.setRollbackOnly();
        });
    }

    /**
     * Стартовая проверка прав на правах фикстуры: объявленные строки выданы — значит, недоступная
     * «Создать» в пилоте не может объясняться нехваткой прав. Проверка не вакуумна: снятая строка
     * права на операцию названа поимённо, иначе «права на месте» печаталось бы и при пустой выдаче.
     */
    @Test
    void theStartupReportNamesAMissingDeclaredRightByItsKey() {
        transactions.executeWithoutResult(status -> {
            initializer().seedFixture();
            assertThat(initializer().missingDeclaredRights()).isEmpty();

            String operationsKey = C5PermissionKeys.entityOperations(NomenclaturePrice.class);
            entityManager.createQuery("delete from AccessGrant g where g.subjectKey = :user"
                    + " and g.dimension = :dimension")
                .setParameter("user", AccountingObjectPilotInitializer.PILOT_SUBJECT)
                .setParameter("dimension", operationsKey)
                .executeUpdate();

            assertThat(initializer().missingDeclaredRights()).containsExactly(operationsKey);
            status.setRollbackOnly();
        });
    }

    private ActionDecision createDecision(Class<?> type) {
        return new ActionResolver(actionRegistry, actionContextProvider, actionHandlerRegistry,
            ActionSurface.LIST_TOOLBAR, type, null).decide(CrudAction.CREATE, null, true);
    }

    private AccountingObjectPilotInitializer initializer() {
        return new AccountingObjectPilotInitializer(entityManager, access, aggregates, documents,
            grants, users, roles, passwords, "pilot-fixture-password");
    }

    private void asPilot(Runnable action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext subject = SecurityContextHolder.createEmptyContext();
        subject.setAuthentication(new UsernamePasswordAuthenticationToken(
            AccountingObjectPilotInitializer.PILOT_SUBJECT, "", List.of()));
        SecurityContextHolder.setContext(subject);
        try {
            action.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
