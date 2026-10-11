package org.ip.views.unidr;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import org.ip.unidr.model.*;
import org.ip.unidr.query.UniDrReferenceService;
import org.ip.unidr.run.*;
import org.ip.unidr.source.*;
import org.ip.unidr.store.UniDrFileStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrFileUiTest {
    @TempDir Path directory;
    UI ui;
    UniDrFileStore store;
    UniDrReferenceService references;
    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice",null,List.of()));
        var page=mock(com.vaadin.flow.component.page.Page.class);
        when(page.getHistory()).thenReturn(mock(com.vaadin.flow.component.page.History.class));
        ui=new UI() { @Override public com.vaadin.flow.component.page.Page getPage() { return page; } };
        UI.setCurrent(ui);references=mock(UniDrReferenceService.class);
        var session=mock(com.vaadin.flow.server.VaadinSession.class);
        when(session.hasLock()).thenReturn(true);
        ui.getInternals().setSession(session);
        var access=new UniDrAccess(()->true,()->SecurityContextHolder.getContext().getAuthentication().getName());
        store=new UniDrFileStore(directory,new UniDrDefinitionJsonCodec(new UniDrDefinitionValidator(UniDrSourceCatalog.pilot())),access);
    }
    @AfterEach void cleanup() {UI.setCurrent(null);ui=null;SecurityContextHolder.clearContext();}
    UniDrView view() {var view=new UniDrView(mock(UniDrRunService.class),references,Clock.systemUTC(),null,store);ui.add(view);return view;}
    @Test void saveClearsDirtyAndReopeningRestoresSettings() {
        var view=view();var editor=UniDrEditorTest.all(view,UniDrDefinitionEditor.class).getFirst();editor.title.setValue("Мой сохранённый отчёт");
        assertThat(view.getElement().hasAttribute("data-dirty")).isTrue();
        var saved=view.saveDefinition(false,view.definition().title(),"Описание");
        assertThat(view.getElement().hasAttribute("data-dirty")).isFalse();
        view.reset();view.openSaved(saved.id());
        assertThat(view.definition()).isEqualTo(saved.definition());
        assertThat(UniDrEditorTest.all(view,com.vaadin.flow.component.html.Span.class)).anyMatch(span->span.getText().contains("Сохранён: Мой сохранённый отчёт"));
        assertThat(view.getElement().hasAttribute("data-dirty")).isFalse();
    }
    @Test void saveAsDoesNotModifyOriginalAndFollowingSaveUpdatesTheCopy() {
        var view=view();var first=view.saveDefinition(false,"Первый","");
        var copy=view.saveDefinition(true,"Второй","Копия");
        var changed=view.saveDefinition(false,"Второй обновлённый","Копия");
        assertThat(changed.id()).isEqualTo(copy.id()).isNotEqualTo(first.id());
        assertThat(store.load(first.id()).definition().title()).isEqualTo("Первый");
        assertThat(store.load(copy.id()).definition().title()).isEqualTo("Второй обновлённый");
    }
    @Test void failedSaveKeepsDirtyStateAndUserChanges() {
        var view=view();var first=view.saveDefinition(false,"Отчёт","");
        store.update(first.id(),first.revision(),first.definition().withTitle("Из другой вкладки"),"");
        UniDrEditorTest.all(view,UniDrDefinitionEditor.class).getFirst().title.setValue("Мои изменения");
        UniDrEditorTest.button(view,"Сохранить").click();
        assertThat(view.definition().title()).isEqualTo("Мои изменения");assertThat(view.getElement().hasAttribute("data-dirty")).isTrue();
        assertThat(UniDrEditorTest.all(view,Div.class)).anyMatch(div->div.getText().contains("изменён в другой вкладке"));
    }
    @Test void failedOpenDoesNotReplaceCurrentRecipe() {
        var view=view();var previous=view.definition();
        assertThatThrownBy(()->view.openSaved(123)).isInstanceOf(UniDrFileStore.StoreException.class);
        assertThat(view.definition()).isEqualTo(previous);
    }
    @Test void saveDialogUsesEnteredNameAndDescription() {
        var view=view();var dialog=view.saveDialog(false);
        UniDrEditorTest.all(dialog,com.vaadin.flow.component.textfield.TextField.class).getFirst().setValue("Через диалог");
        UniDrEditorTest.all(dialog,com.vaadin.flow.component.textfield.TextArea.class).getFirst().setValue("Примечание");
        UniDrEditorTest.footerButton(dialog,"Сохранить").click();
        assertThat(dialog.isOpened()).isFalse();assertThat(store.list(null)).singleElement().satisfies(entry->{
            assertThat(entry.name()).isEqualTo("Через диалог");assertThat(entry.description()).isEqualTo("Примечание");
        });
    }
}
