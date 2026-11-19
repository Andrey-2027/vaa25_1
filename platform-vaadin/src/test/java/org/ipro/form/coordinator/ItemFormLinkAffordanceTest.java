package org.ipro.form.coordinator;

import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.contextmenu.MenuItem;
import org.ipro.crud.BaseEntity;
import org.ipro.form.CommandBar;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.CrudAction;
import org.ipro.form.link.FormLinkResult;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.link.NotLinkableReason;
import org.ipro.identity.IdentifiableEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** «Скопировать ссылку» на панели карточки: адрес читается лениво, состояние — из решения. */
class ItemFormLinkAffordanceTest {
    static class Document extends BaseEntity { }
    static class StringIdentity implements IdentifiableEntity<String> {
        public String getId() { return "42"; }
        public void setId(String id) { }
    }

    private final CommandBar bar = new CommandBar();
    private final ActionResolver resolver = mock(ActionResolver.class);
    private final List<ActionDecision> refused = new ArrayList<>();

    @Test
    void recordAddressReadsNewLongIdAfterSaveWithoutReattaching() {
        Document entity = new Document();
        FormLinkService links = links();
        attach(new AtomicReference<>(entity), links, true).run();

        click();
        verify(links).linkToRecord(Document.class, null, "variant");

        entity.setId(42L);
        click();
        verify(links).linkToRecord(Document.class, 42L, "variant");
    }

    @Test
    void runtimeBoundaryDoesNotConvertStringIdentityToLongRecordLink() {
        FormLinkService links = links();
        attach(new AtomicReference<>(new StringIdentity()), links, true).run();

        click();

        verify(links).linkToRecord(Document.class, null, "variant");
    }

    @Test
    void commandLivesInServiceSubmenuAndIsHiddenUntilTheFormIsLive() {
        Runnable refresh = attach(new AtomicReference<>(new Document()), links(), false);
        var command = bar.command(CrudAction.COPY_LINK.id());

        assertThat(bar.serviceMenu().getItems()).extracting(MenuItem::getText).contains("Скопировать ссылку");
        assertThat(command.button()).isNull();
        assertThat(command.isVisible()).isFalse();

        refresh.run();
        assertThat(command.isVisible()).isFalse();
    }

    @Test
    void refreshAppliesDecisionToVisibilityAvailabilityAndTooltip() {
        AtomicReference<Object> current = new AtomicReference<>(null);
        FormLinkService links = links();
        when(resolver.decide(eq(CrudAction.COPY_LINK), any(), anyBoolean()))
            .thenReturn(ActionDecision.allowed());
        Runnable refresh = attach(current, links, true);
        var command = bar.command(CrudAction.COPY_LINK.id());

        // Записи ещё нет — адресовать нечего: команда скрыта и решение у решателя не спрашивается.
        refresh.run();
        assertThat(command.isVisible()).isFalse();

        current.set(new Document());
        refresh.run();
        assertThat(command.isVisible()).isTrue();
        assertThat(command.isEnabled()).isTrue();

        when(resolver.decide(eq(CrudAction.COPY_LINK), any(), anyBoolean()))
            .thenReturn(ActionDecision.blocked(ActionDecision.Reason.NOT_LINKABLE, "Нет адреса"));
        refresh.run();
        assertThat(command.isVisible()).isTrue();
        assertThat(command.isEnabled()).isFalse();
    }

    private Runnable attach(AtomicReference<Object> current, FormLinkService links, boolean live) {
        when(resolver.decide(eq(CrudAction.COPY_LINK), any(), anyBoolean()))
            .thenReturn(ActionDecision.allowed());
        return ItemFormLinkAffordance.attach(bar, current::get, () -> live, links, resolver,
            Document.class, "variant", refused::add);
    }

    private void click() {
        MenuItem item = bar.command(CrudAction.COPY_LINK.id()).menuItem();
        ComponentUtil.fireEvent(item, new ClickEvent<>(item, true, 0, 0, 0, 0, 1, 0,
            false, false, false, false));
    }

    private static FormLinkService links() {
        FormLinkService links = mock(FormLinkService.class);
        when(links.linkToRecord(eq(Document.class), any(), eq("variant")))
            .thenReturn(FormLinkResult.notLinkable(NotLinkableReason.MISSING_ID));
        return links;
    }
}
