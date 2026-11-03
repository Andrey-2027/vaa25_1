package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.design.*;
import org.ipro.form.Savable;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class EntityDraftEditorViewTest {
    static com.vaadin.flow.component.UI uiWithResources() {
        var ui = new com.vaadin.flow.component.UI();
        var session = org.mockito.Mockito.mock(com.vaadin.flow.server.VaadinSession.class);
        org.mockito.Mockito.when(session.hasLock()).thenReturn(true);
        org.mockito.Mockito.when(session.getLocale()).thenReturn(Locale.forLanguageTag("ru"));
        org.mockito.Mockito.when(session.getResourceRegistry()).thenReturn(new com.vaadin.flow.server.StreamResourceRegistry(session));
        ui.getInternals().setSession(session);
        return ui;
    }
    static void principal(String name, boolean admin) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, "", admin ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN")) : List.of()));
    }
    @BeforeEach void login() { principal("admin", true); }
    @AfterEach void logout() { com.vaadin.flow.component.UI.setCurrent(null); SecurityContextHolder.clearContext(); }
    static Stream<Component> all(Component c) { return Stream.concat(Stream.of(c), c.getChildren().flatMap(EntityDraftEditorViewTest::all)); }
    static TextField text(EntityDraftEditorView view, String key) {
        return all(view).filter(c -> c instanceof TextField && key.equals(c.getElement().getAttribute("data-draft-input"))).map(c -> (TextField)c).findFirst().orElseThrow();
    }
    static Button button(Component view, String caption) {
        return all(view).filter(c -> c instanceof Button b && b.getText().equals(caption)).map(c -> (Button)c).findFirst().orElseThrow();
    }
    static void clickRow(EntityDraftEditorView view, UUID id) {
        var row = all(view).filter(c -> c instanceof NativeButton && id.toString().equals(c.getElement().getAttribute("data-field-id"))).findFirst().orElseThrow();
        com.vaadin.flow.component.ComponentUtil.fireEvent(row, new com.vaadin.flow.component.ClickEvent<>(row));
    }
    static EntityDraftEditorView view() {
        var view = new EntityDraftEditorView(new EntityDraftAccess(true, new EntityExplorerAccess()));
        view.setContextSource(() -> DraftEditorSessionTest.context("v1")); view.initialize(() -> {}); button(view, "Справочник").click(); return view;
    }
    @Test void actualEditorKeepsIncompleteInputAcrossPropertyAndPageSelection() {
        var view = view(); assertThat(view).isNotInstanceOf(Savable.class);
        text(view, "className").setValue("Partial-"); text(view, "label").setValue("Материалы");
        assertThat(text(view, "tableName").getValue()).isEqualTo("partial-");
        text(view, "tableName").setValue("fixed_table"); text(view, "className").setValue("Renamed");
        assertThat(text(view, "tableName").getValue()).isEqualTo("fixed_table");
        button(view, "Данные").click(); button(view, "+ Реквизит").click(); UUID first = view.session().place().fieldId();
        text(view, "name").setValue("weight"); text(view, "field.label").setValue("Масса");
        text(view, "constraints.length").setValue("12x");
        button(view, "+ Реквизит").click(); text(view, "name").setValue("note");
        clickRow(view, first);
        assertThat(text(view, "constraints.length").getValue()).isEqualTo("12x");
        button(view, "Основные").click(); button(view, "Данные").click(); view.activate();
        assertThat(view.session().selectedField().name()).isEqualTo("weight");
        assertThat(text(view, "constraints.length").getValue()).isEqualTo("12x");
        assertThat(view.tabTitle()).isEqualTo("Черновик · Материалы *");
    }
    @Test void typeSwitchShowsConflictingInputAndExplicitCleanupInsteadOfDiscardingIt() {
        var view = view(); button(view, "+ Реквизит").click();
        @SuppressWarnings("unchecked") Select<String> type = (Select<String>) all(view).filter(c -> c instanceof Select<?> && "type".equals(c.getElement().getAttribute("data-draft-input"))).findFirst().orElseThrow();
        type.setValue("DECIMAL");
        assertThat(text(view, "constraints.length").getValue()).isEqualTo("255");
        assertThat(text(view, "constraints.length").isInvalid()).isTrue();
        button(view, "Очистить").click();
        assertThat(view.session().selectedField().constraints().length()).isEmpty();
        assertThat(all(view).filter(c -> "constraints.length".equals(c.getElement().getAttribute("data-draft-input")))) .isEmpty();
    }
    @Test void inheritedRowsExposeNoEditingOrRemovalAndNewStartsClean() {
        var ui = uiWithResources(); com.vaadin.flow.component.UI.setCurrent(ui);
        var view = view(); ui.add(view); button(view, "Данные").click();
        assertThat(view.session().draft().recipe()).isEqualTo(RecipeCatalog.STANDARD_CATALOG_V2);
        var row = all(view).filter(c -> "builtin:code".equals(c.getElement().getAttribute("data-builtin"))).findFirst().orElseThrow();
        com.vaadin.flow.component.ComponentUtil.fireEvent(row, new com.vaadin.flow.component.ClickEvent<>(row));
        assertThat(button(view, "Удалить").isEnabled()).isFalse();
        assertThat(view.session().draft().fields()).isEmpty(); assertThat(view.isDirty()).isFalse();
        UUID original = view.session().draft().draftId(); button(view, "Новый").click();
        var chooser = all(view).filter(c -> c instanceof com.vaadin.flow.component.dialog.Dialog).findFirst().orElseThrow();
        button(chooser, "Справочник").click();
        assertThat(view.session().draft().draftId()).isNotEqualTo(original); assertThat(view.isDirty()).isFalse();
        assertThat(view.session().draft().recipe()).isEqualTo(RecipeCatalog.STANDARD_CATALOG_V2);
    }
    @Test void v2EditorKeepsUnknownRuntimeFactsBlockingAfterCatalogRefresh() {
        var view = view(); text(view, "className").setValue("MaterialCard"); text(view, "label").setValue("Материалы");
        button(view, "Проверить").click();
        assertThat(view.session().validation().orElseThrow().checkedStructure()).isPresent();
        var before = view.session().draft(); long revision = view.session().revision();
        view.setContextSource(() -> DraftEditorSessionTest.context("unknown").withRuntimeSnapshot(RuntimeDesignSnapshot.unknown()));
        button(view, "Обновить каталог").click();
        assertThat(view.session().validation().orElseThrow().checkedStructure()).isEmpty();
        assertThat(view.session().draft()).isSameAs(before);
        assertThat(view.session().revision()).isEqualTo(revision);
        assertThat(view.isDirty()).isTrue();
    }
    @Test void principalOrAccessChangeErasesOldInputButContextRefreshDoesNot() {
        var view = view(); text(view, "label").setValue("Секретный черновик"); UUID id = view.session().draft().draftId();
        view.setContextSource(() -> DraftEditorSessionTest.context("v2")); view.activate();
        assertThat(view.session().draft().draftId()).isEqualTo(id); assertThat(view.isDirty()).isTrue();
        principal("another-admin", true); view.activate();
        assertThat(view.session().draft().draftId()).isNotEqualTo(id); assertThat(text(view, "label").getValue()).isEmpty();
        button(view, "Справочник").click();
        text(view, "label").setValue("Не показывать"); principal("user", false); view.activate();
        assertThat(view.session()).isNull(); assertThat(view.isDirty()).isFalse(); assertThat(all(view).filter(c -> c instanceof TextField)).isEmpty();
    }
    @Test void commandsRecheckAuthorizationAndDisabledPilotNeverCreatesADraft() {
        var view = view(); var add = button(view, "+ Реквизит"); principal("user", false); add.click();
        assertThat(view.session()).isNull();
        principal("admin", true);
        var disabled = new EntityDraftEditorView(new EntityDraftAccess(false, new EntityExplorerAccess())); disabled.initialize(() -> {});
        assertThat(disabled.session()).isNull(); assertThat(all(disabled).filter(c -> c instanceof TextField)).isEmpty();
    }
    @Test void twoActualViewsHaveIndependentDraftsAndInputs() {
        var first = view(); var second = view(); text(first, "label").setValue("Первый"); button(first, "+ Реквизит").click();
        assertThat(text(second, "label").getValue()).isEmpty(); assertThat(second.session().draft().fields()).isEmpty(); assertThat(second.isDirty()).isFalse();
    }
    @Test void explicitAutoResetAndLiveFieldCaptionFollowTheSameDraft() {
        var view = view(); text(view, "className").setValue("FirstCard");
        text(view, "tableName").setValue("fixed_table"); text(view, "className").setValue("SecondCard");
        assertThat(text(view, "tableName").getValue()).isEqualTo("fixed_table");
        var reset = all(view).filter(c -> c instanceof Button && "tableName".equals(c.getElement().getAttribute("data-draft-reset"))).map(c -> (Button)c).findFirst().orElseThrow();
        reset.click(); assertThat(view.session().draft().entity().tableName()).isEqualTo(DraftText.auto());
        assertThat(text(view, "tableName").getValue()).isEqualTo("second_card");
        button(view, "+ Реквизит").click(); text(view, "name").setValue("newName");
        assertThat(all(view).filter(c -> c instanceof com.vaadin.flow.component.html.H4 h && h.getText().equals("Справочник / newName"))).hasSize(1);
    }
    @Test void lateInputFromThePreviousDocumentCannotWriteIntoTheNewOwnersDraft() {
        var view = view(); var previousLabel = text(view, "label");
        previousLabel.setValue("Старый ввод"); principal("other-admin", true); view.activate();
        UUID newId = view.session().draft().draftId(); previousLabel.setValue("Запоздалое событие");
        assertThat(view.session().draft().draftId()).isEqualTo(newId);
        assertThat(view.session().draft().entity().label()).isEmpty(); assertThat(view.isDirty()).isFalse();
    }
}
