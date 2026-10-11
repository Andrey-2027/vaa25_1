package org.ip.application.register;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.NomenclatureAccountingObject;
import org.ip.model.NomenclaturePrice;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.registry.FormResolver;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регистры сведений на настоящем решении действий (те же бины, что ставит координатор) и на
 * настоящем каноническом чтении: два факта двух поверхностей.
 *
 * <p>Первый — состав тулбара списка. Подтверждается, что «Создать» у самостоятельного регистра
 * есть и разрешена решением, у регистра с регистраторами список только для просмотра (строка
 * принадлежит регистратору), а без права на операцию кнопка остаётся видимой и недоступной с
 * названной причиной.</p>
 *
 * <p>Второй — встроенный фильтр периода: у периодического регистра его нет ни в списке, ни в форме
 * выбора, но колонка остаётся и условие по периоду по-прежнему разрешается
 * ({@code @FieldMetadata(inlineFilter = false)} снимает только контрол в шапке, а не сам отбор).</p>
 */
@SpringBootTest(classes = Application.class)
class RegisterListFormToolbarTest {

    @MockitoBean private DataInitializer dataInitializer;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FormResolver formResolver;
    @Autowired private ActionRegistry actionRegistry;
    @Autowired private ActionHandlerRegistry actionHandlerRegistry;
    @Autowired private ActionContextProvider actionContextProvider;

    private String username;

    @BeforeEach
    void setUp() {
        username = "rlt" + UUID.randomUUID().toString().substring(0, 8);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (Class<?> type : List.of(NomenclaturePrice.class, NomenclatureAccountingObject.class)) {
                AccessGrant read = new AccessGrant();
                read.setSubjectType(AccessGrant.SubjectType.USER);
                read.setSubjectKey(username);
                read.setDimension("ENTITY:" + type.getName());
                read.setCanRead(true);
                entityManager.persist(read);

                AccessGrant operations = new AccessGrant();
                operations.setSubjectType(AccessGrant.SubjectType.USER);
                operations.setSubjectKey(username);
                operations.setDimension(C5PermissionKeys.entityOperations(type));
                operations.setCanUpdate(true);
                operations.setCanDelete(true);
                entityManager.persist(operations);
            }
        });
    }

    @AfterEach
    void cleanUp() {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createQuery("delete from AccessGrant g where g.subjectKey = :user")
                    .setParameter("user", username).executeUpdate());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void standaloneRegisterOffersCreateAndHasNoInlinePeriodFilter() {
        ListForm<NomenclaturePrice, Long> form = formResolver.resolveListForm(NomenclaturePrice.class, null, null);
        ActionDecision create = forceResolver(form, NomenclaturePrice.class)
            .decide(CrudAction.CREATE, null, true);

        assertThat(create.actionable()).isTrue();
        assertThat(create.reason()).isEqualTo(ActionDecision.Reason.NONE);
        assertThat(Boolean.valueOf(form.isReadOnly())).isFalse();
        assertThat(Boolean.valueOf(form.getAddButton().isVisible())).isTrue();
        assertThat(Boolean.valueOf(form.getAddButton().isEnabled())).isTrue();

        // Период: колонка есть, встроенного фильтра нет; у ресурса фильтр остаётся.
        assertThat(form.getActiveColumns()).extracting(column -> column.getKey()).contains("period", "price");
        assertThat(Object.class.cast(form.getFilterGrid().getColumnFilter("period"))).isNull();
        assertThat(Object.class.cast(form.getFilterGrid().getColumnFilter("price"))).isNotNull();

        // Условие по периоду по-прежнему разрешается: снят только быстрый фильтр колонки.
        assertThat(form.compileFilter(org.ipro.filtergrid.filter.FilterConditionNode.of(
            new org.ipro.filtergrid.filter.FilterCondition("period",
                org.ipro.filtergrid.filter.FilterOperator.LE, "2026-10-01T00:00:00", null,
                org.ipro.filtergrid.filter.FilterDataType.DATE)))).isNotNull();
    }

    @Test
    void selectionFormKeepsThePeriodColumnWithoutAnInlineFilter() {
        var selection = formResolver.resolveSelectionForm(NomenclaturePrice.class, value -> { });
        var grid = selection.getFilterGrid();

        assertThat(grid.getGrid().getColumns())
            .anyMatch(column -> "period".equals(column.getKey()));
        assertThat(Object.class.cast(grid.getColumnFilter("period"))).isNull();
        assertThat(Object.class.cast(grid.getColumnFilter("price"))).isNotNull();
    }

    @Test
    void withoutOperationGrantCreateStaysVisibleAndExplainsItself() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createQuery("delete from AccessGrant g where g.subjectKey = :user")
                .setParameter("user", username).executeUpdate());
        ActionDecision create = new ActionResolver(actionRegistry, actionContextProvider,
            actionHandlerRegistry, ActionSurface.LIST_TOOLBAR, NomenclaturePrice.class, null)
            .decide(CrudAction.CREATE, null, true);

        assertThat(Boolean.valueOf(create.visible())).isTrue();
        assertThat(Boolean.valueOf(create.enabled())).isFalse();
        assertThat(create.reason()).isEqualTo(ActionDecision.Reason.ACCESS_DENIED);
        assertThat(create.message()).contains("Нет прав на создание");
    }

    @Test
    void recorderOwnedRegisterListIsReadOnlyAndHidesCreate() {
        ListForm<NomenclatureAccountingObject, Long> form =
            formResolver.resolveListForm(NomenclatureAccountingObject.class, null, null);
        ActionDecision create = forceResolver(form, NomenclatureAccountingObject.class)
            .decide(CrudAction.CREATE, null, true);

        // Строка принадлежит регистратору: решение формы — только просмотр, поэтому кнопок записи нет.
        assertThat(Boolean.valueOf(form.isReadOnly())).isTrue();
        assertThat(Boolean.valueOf(form.getAddButton().isVisible())).isFalse();
        assertThat(create.actionable()).isTrue();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ActionResolver forceResolver(ListForm<?, ?> form, Class<?> type) {
        ActionResolver resolver = new ActionResolver(actionRegistry, actionContextProvider,
            actionHandlerRegistry, ActionSurface.LIST_TOOLBAR, type, null);
        ((ListForm) form).setActionResolver(resolver);
        return resolver;
    }
}
