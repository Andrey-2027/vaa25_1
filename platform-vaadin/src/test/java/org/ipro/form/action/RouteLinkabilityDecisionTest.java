package org.ipro.form.action;

import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilities;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.action.ActionDecision.Reason;
import org.ipro.form.action.ActionRequirement.Level;
import org.ipro.form.link.NotLinkableReason;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E2.1: адресуемость формы как вход решения — действие, которому нужна публичная ссылка, исчезает
 * ровно там, где ссылки нет, и по названной причине.
 *
 * <p>Проверяется не только «скрылось»: требования к порядку и ленивости — часть контракта. Права
 * проверяются раньше адреса (иначе недоступная по правам кнопка объясняла бы себя отсутствием
 * ссылки, которой никто не отказывал), обязательный контекст — тоже раньше (он описывает
 * состояние экрана, а отсутствие адреса — контракт), а сам адресный вход не спрашивается у
 * действия, которое его не требует: первое обращение к каталогу адресов строит его целиком, и
 * платить за это решением о кнопке «Создать» незачем.</p>
 */
class RouteLinkabilityDecisionTest {

    /** Публикуемый тип с полным CRUD — база для решений о ссылке. */
    static class Published {
    }

    private static EntityCapabilities fullCrud() {
        return new EntityCapabilities(Set.of(FetchScenario.LIST, FetchScenario.DETAIL),
            Set.of(DataOperation.CREATE, DataOperation.UPDATE, DataOperation.DELETE),
            "test fixture");
    }

    private static ActionDefinition definition(ActionRequirement requirement) {
        return new ActionDefinition(new ActionId("crud.copy_link"), ActionSurface.LIST_TOOLBAR,
            "Скопировать ссылку", null, null, null, 10, requirement, true);
    }

    private static ActionContext context(RouteLinkability linkability) {
        return new ActionContext(Published.class, null, fullCrud(), ActionPermission.allowed(),
            false, false, ActionContext.RowState.EXISTING, linkability);
    }

    @Test
    void actionWithAddressDependsOnTheAddressAndNotOnRights() {
        ActionDefinition definition = definition(ActionRequirement.linkable());

        assertThat(ActionPolicy.decide(definition, context(RouteLinkability.linkable())).actionable())
            .as("адрес есть — действие доступно, хотя никакой операции оно не требует")
            .isTrue();
    }

    @Test
    void missingAddressHidesTheActionWithATypedReason() {
        ActionDecision decision = ActionPolicy.decide(definition(ActionRequirement.linkable()),
            context(RouteLinkability.blocked(NotLinkableReason.NOT_PUBLISHED)));

        assertThat(decision.visible())
            .as("серая кнопка обещала бы ссылку, которой у типа не будет ни при каком состоянии"
                + " экрана: действие неприменимо, а не «недоступно сейчас»")
            .isFalse();
        assertThat(decision.reason()).isEqualTo(Reason.NOT_LINKABLE);
        assertThat(decision.message())
            .as("пользователю нужен текст отказа, а решение — типизированную причину")
            .isNotBlank();
    }

    @Test
    void everyAddressReasonExplainsItselfDifferently() {
        Set<String> messages = java.util.Arrays.stream(NotLinkableReason.values())
            .map(reason -> ActionPolicy.decide(definition(ActionRequirement.linkable()),
                context(RouteLinkability.blocked(reason))))
            .peek(decision -> assertThat(decision.reason()).isEqualTo(Reason.NOT_LINKABLE))
            .map(ActionDecision::message)
            .collect(java.util.stream.Collectors.toSet());

        assertThat(messages)
            .as("«нет ссылки, потому что нет ссылки» — не объяснение: у каждой причины свой текст")
            .hasSameSizeAs(NotLinkableReason.values());
        assertThat(messages).allMatch(message -> !message.isBlank());
    }

    @Test
    void addressIsNotAskedUntilAnActionRequiresIt() {
        AtomicInteger asked = new AtomicInteger();
        RouteLinkability counting = () -> {
            asked.incrementAndGet();
            return null;
        };
        ActionContext context = context(counting);

        ActionPolicy.decide(definition(ActionRequirement.none()), context);
        ActionPolicy.decide(definition(ActionRequirement.selectionOnly()), context);

        assertThat(asked)
            .as("каталог адресов строится лениво: спрашивать его о кнопках без ссылки нельзя")
            .hasValue(0);

        ActionPolicy.decide(definition(ActionRequirement.linkable()), context);
        assertThat(asked).hasValue(1);
    }

    @Test
    void rightsAndContextAreDecidedBeforeTheAddress() {
        ActionRequirement both = new ActionRequirement(Level.REQUIRED, Level.ANY, Level.ANY,
            Level.ANY, false, true, false, true);
        ActionPermission denied = ActionPermission.forClass(false, "нет CREATE на тип");

        ActionContext withoutRights = new ActionContext(Published.class, null, fullCrud(), denied,
            false, true, ActionContext.RowState.EXISTING,
            RouteLinkability.blocked(NotLinkableReason.NOT_PUBLISHED));
        ActionDecision rightsFirst = ActionPolicy.decide(definition(both), withoutRights);

        assertThat(rightsFirst.reason())
            .as("права раньше адреса: иначе отказ в правах объяснялся бы отсутствием ссылки")
            .isEqualTo(Reason.ACCESS_DENIED);

        ActionContext withRights = new ActionContext(Published.class, null, fullCrud(),
            ActionPermission.allowed(), false, false, ActionContext.RowState.EXISTING,
            RouteLinkability.blocked(NotLinkableReason.NOT_PUBLISHED));
        ActionDecision contextFirst = ActionPolicy.decide(definition(both), withRights);

        assertThat(contextFirst.reason())
            .as("обязательный контекст раньше адреса: он про состояние экрана, а не про контракт")
            .isEqualTo(Reason.CONTEXT_INCOMPLETE);
    }

    @Test
    void contextWithoutAddressInputFailsWithANamedCompositionError() {
        assertThatThrownBy(() -> ActionPolicy.decide(definition(ActionRequirement.linkable()),
            context(null)))
            .as("отсутствие адресного входа — ошибка композиции, а не «адреса нет»: спрятать"
                + " действие значило бы выдать ненастроенный вход за отсутствие адреса")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("без адресного входа");
    }

    @Test
    void addressRequirementIsDataAndNotAnActionSpecificBranch() {
        ActionRequirement requirement = ActionRequirement.linkable();

        assertThat(requirement.requiresLinkable()).isTrue();
        assertThat(requirement.requiresRequiredContext()).isFalse();
        assertThat(requirement.requiresSelection())
            .as("ссылка на список не требует выделенной строки")
            .isFalse();
        assertThat(requirement.rowStateSelectsOperation()).isFalse();
        assertThat(ActionRequirement.none().requiresLinkable())
            .as("обычное действие адреса не требует")
            .isFalse();
    }
}
