package org.ip.views.admin;

import com.vaadin.flow.component.*;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.server.*;
import com.vaadin.flow.server.streams.*;
import org.ipro.design.*;
import org.ipro.design.json.*;
import org.junit.jupiter.api.*;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.ip.views.admin.EntityDraftEditorViewTest.*;

class EntityDraftJsonTransferTest {
    @BeforeEach void login() { principal("admin",true); }
    @AfterEach void logout() { UI.setCurrent(null); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    UI immediateUi() {
        UI ui=mock(UI.class);
        doAnswer(i->{ ((Command)i.getArgument(0)).execute(); return null; }).when(ui).accessSynchronously(any()); return ui;
    }
    DownloadEvent download(OutputStream out) {
        UI ui=immediateUi(); var event=mock(DownloadEvent.class); when(event.getUI()).thenReturn(ui);
        when(event.getResponse()).thenReturn(mock(VaadinResponse.class)); when(event.getOutputStream()).thenReturn(out); return event;
    }
    UploadEvent upload(InputStream in) {
        UI ui=immediateUi(); var event=mock(UploadEvent.class); when(event.getUI()).thenReturn(ui); when(event.getInputStream()).thenReturn(in); return event;
    }
    @Test void v1JsonImportPreservesRecipeIdsAndRawInputInTheV2Editor() {
        var operations = new DraftOperations();
        var legacy = operations.addField(operations.create(), null, "DECIMAL");
        UUID fieldId = legacy.fields().getFirst().id();
        legacy = operations.editField(legacy, null, fieldId, f -> new FieldDraft(f.id(), "unfinished-", "Масса", f.type(),
            f.columnName(), false, f.uiRequired(), new ConstraintDraft("", "12x", "-"), null, f.applications()));
        var codec = new DraftJsonCodec();
        var v = view();
        v.acceptImport(codec.decode(codec.encode(legacy)), v.documentEpoch(), v.session().revision());
        assertThat(v.session().draft()).isEqualTo(legacy);
        assertThat(v.session().draft().recipe()).isEqualTo(RecipeCatalog.STANDARD_CATALOG);
        assertThat(v.isDirty()).isFalse();
        assertThat(codec.decode(v.prepareExport().bytes())).isEqualTo(legacy);
        assertThat(v.isDirty()).isFalse();
    }
    @Test void actualHandlerChecksDeliveryAndKeepsLateEditsDirty() throws Exception {
        var v=view(); text(v,"className").setValue("First"); var exported=v.prepareExport();
        assertThat(v.isDirty()).isTrue(); text(v,"className").setValue("Second");
        var bytes=new ByteArrayOutputStream(); v.downloadHandler(exported).handleDownloadRequest(download(bytes));
        assertThat(new DraftJsonCodec().decode(bytes.toByteArray()).entity().className()).isEqualTo("First");
        assertThat(v.isDirty()).isTrue();
        var latest=v.prepareExport(); v.downloadHandler(latest).handleDownloadRequest(download(new ByteArrayOutputStream()));
        assertThat(v.isDirty()).isFalse(); v.exportDelivered(exported); assertThat(v.isDirty()).isFalse();
    }
    @Test void failedOutputAndPreparationNeverClearDirty() throws Exception {
        var v=view(); text(v,"label").setValue("Работа с ошибками"); var exported=v.prepareExport();
        assertThat(v.session().validation().orElseThrow().hasErrors()).isTrue();
        OutputStream failed=new OutputStream(){ @Override public void write(int b) throws IOException { throw new IOException("disconnected"); } };
        assertThatThrownBy(()->v.downloadHandler(exported).handleDownloadRequest(download(failed))).isInstanceOf(IOException.class);
        assertThat(v.isDirty()).isTrue();
    }
    @Test void invalidUploadDoesNotReplaceOrPromptAndReadsAreBounded() throws Exception {
        var v=view(); text(v,"className").setValue("KeepMe"); EntityDraft before=v.session().draft();
        var handler=v.uploadHandler(v.documentEpoch()); var bad=upload(new ByteArrayInputStream("{broken".getBytes()));
        handler.handleUploadRequest(bad); assertThat(v.session().draft()).isSameAs(before); assertThat(v.isDirty()).isTrue(); verify(bad).reject(anyString());
        var huge=new ByteArrayInputStream(new byte[DraftJsonCodec.MAX_BYTES+100]); handler.handleUploadRequest(upload(huge));
        assertThat(huge.available()).isEqualTo(99); assertThat(v.session().draft()).isSameAs(before);
    }
    @Test void importedUnknownValuesAreEditableAndSameIdGetsANewUiEpoch() throws Exception {
        var v=view(); var original=v.session().draft();
        var entity=original.entity();
        var imported=original.withEntity(new EntityProperties("Bad-",entity.packageName(),"Незавершено",entity.titles(),entity.tableName(),"lost.Subsystem","FUTURE_ICON"));
        var old=text(v,"className"); var epoch=v.documentEpoch(); var oldExport=v.prepareExport();
        v.acceptImport(imported,epoch,v.session().revision());
        assertThat(v.documentEpoch()).isNotEqualTo(epoch); assertThat(v.session().draft().draftId()).isEqualTo(original.draftId());
        assertThat(v.isDirty()).isFalse(); assertThat(v.session().validation().orElseThrow().hasErrors()).isTrue();
        old.setValue("late-old-control"); assertThat(v.session().draft()).isEqualTo(imported);
        text(v,"className").setValue("Edited"); v.exportDelivered(oldExport); assertThat(v.isDirty()).isTrue();
    }
    @Test void staleOrUnauthorizedTransfersDoNotReadOrDeliverOldWork() throws Exception {
        var v=view(); text(v,"className").setValue("PrivateDraft"); var exported=v.prepareExport(); var handler=v.uploadHandler(v.documentEpoch());
        principal("other",true); var event=download(new ByteArrayOutputStream()); v.downloadHandler(exported).handleDownloadRequest(event);
        verify(event.getResponse()).setStatus(403); verify(event,never()).getOutputStream();
        var in=mock(InputStream.class); var uploading=upload(in); handler.handleUploadRequest(uploading); verifyNoInteractions(in);
        assertThat(v.session().draft().entity().className()).isEmpty();
        principal("other",false); assertThat(v.prepareExport()).isNull();
    }
    @Test void dirtyImportHasOneGuardAndCancelPreservesDraft() {
        UI ui=new UI(); UI.setCurrent(ui); var v=view(); ui.add(v); text(v,"className").setValue("KeepMe");
        var before=v.session().draft(); var candidate=new DraftOperations().create(); var epoch=v.documentEpoch();
        var dialog=v.acceptImport(candidate,epoch,v.session().revision()); assertThat(dialog).isNotNull();
        assertThat(v.acceptImport(candidate,epoch,v.session().revision())).isNull();
        assertThat(v.session().draft()).isSameAs(before); dialog.close();
        assertThat(v.session().draft()).isSameAs(before); assertThat(v.isDirty()).isTrue();
    }
    @Test void dirtyImportConfirmReplacesOnlyTheExpectedRevision() {
        UI ui=new UI(); UI.setCurrent(ui); var v=view(); ui.add(v); text(v,"className").setValue("Old");
        var candidate=new DraftOperations().create(); var dialog=v.acceptImport(candidate,v.documentEpoch(),v.session().revision());
        ComponentUtil.fireEvent(dialog,new ConfirmDialog.ConfirmEvent(dialog,true));
        assertThat(v.session().draft()).isEqualTo(candidate); assertThat(v.isDirty()).isFalse();
    }
    @Test void changesWhileUploadWasReadingDoNotGetOverwritten() {
        var v=view(); UUID epoch=v.documentEpoch(); long revision=v.session().revision(); text(v,"className").setValue("Newer");
        v.acceptImport(new DraftOperations().create(),epoch,revision);
        assertThat(v.session().draft().entity().className()).isEqualTo("Newer"); assertThat(v.isDirty()).isTrue();
    }
}
