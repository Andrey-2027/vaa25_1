package org.ip.views.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.textfield.TextArea;
import org.ipro.design.*;
import org.ipro.design.generation.*;
import org.ipro.design.json.DraftJsonCodec;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
import static org.ip.views.admin.EntityDraftEditorViewTest.*;
import static org.mockito.Mockito.*;

/** Real E4.A JSON inputs pass through normal P8 import, preview and download actions. */
class EntityDraftSectionDeliveryTest {
    final ObjectMapper json=new ObjectMapper();
    final DraftJsonCodec codec=new DraftJsonCodec();
    final EntityDraftJavaDownloadTest downloads=new EntityDraftJavaDownloadTest();
    UI ui;
    Path fixtures(){return Path.of(System.getProperty("design.sections.fixtures","docs/architecture/evidence/entity-generation-g1-extension-20261029/e4-a"));}
    EntityDraft input(String name)throws Exception{return codec.decode(Files.readAllBytes(fixtures().resolve(name+"/input/current-draft.json")));}
    ProjectDesignContext context(String name)throws Exception{return json.readValue(fixtures().resolve(name+"/input/current-context.json").toFile(),ProjectDesignContext.class);}
    EntityDraftEditorView open(String name)throws Exception{
        var c=context(name);var v=view();v.setContextSource(()->c);ui.add(v);
        v.acceptImport(input(name),v.documentEpoch(),v.session().revision());return v;
    }
    @BeforeEach void login(){principal("admin",true);ui=uiWithResources();UI.setCurrent(ui);}
    @AfterEach void logout(){UI.setCurrent(null);org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @Test void actualZeroOneTwoSectionsForBothKindsAndCombinedOwnerPreviewAndDeliverExactPlans()throws Exception{
        for(var name:List.of("catalog-0","catalog-1","catalog-2","document-0","document-1","document-2","owned-catalog-2")){
            var v=open(name);var before=v.session().draft();var revision=v.session().revision();
            button(v,"Показать Java").click();var token=v.javaExport();assertThat(token).as(name).isNotNull();
            var plan=token.plan();var expected=json.readValue(fixtures().resolve(name+"/input/manifest.json").toFile(),GenerationManifest.class);
            assertThat(GenerationManifest.from(plan,GenerationManifest.EXTENSION_FINGERPRINT)).isEqualTo(expected);
            var dialog=all(v).filter(c->c instanceof Dialog d&&d.isOpened()).map(Dialog.class::cast).findFirst().orElseThrow();
            var source=all(dialog).filter(TextArea.class::isInstance).map(TextArea.class::cast).findFirst().orElseThrow();
            assertThat(dialog.getElement().getTextRecursively()).contains("Файлов: "+plan.files().size());
            for(var f:plan.files()){
                button(dialog,"CREATE · "+f.relativePath()).click();assertThat(source.getValue()).isEqualTo(f.content());
                assertThat(f.content().getBytes(StandardCharsets.UTF_8)).isEqualTo(Files.readAllBytes(fixtures().resolve(name+"/input/"+f.relativePath())));
            }
            var out=new ByteArrayOutputStream();v.javaDownloadHandler(token).handleDownloadRequest(downloads.event(out));
            if(plan.files().size()==1)assertThat(out.toByteArray()).isEqualTo(plan.files().getFirst().content().getBytes(StandardCharsets.UTF_8));
            else{
                var entries=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(out.toByteArray()),StandardCharsets.UTF_8)){
                    ZipEntry entry;while((entry=zip.getNextEntry())!=null){assertThat(entries.put(entry.getName(),zip.readAllBytes())).isNull();}
                }
                assertThat(entries.keySet()).containsExactlyElementsOf(java.util.stream.Stream.concat(java.util.stream.Stream.of("generation-manifest.json"),plan.files().stream().map(GenerationFile::relativePath)).toList());
                var manifest=entries.get("generation-manifest.json");assertThat(new String(manifest,StandardCharsets.UTF_8)).doesNotContain("\r","\uFEFF").endsWith("\n").doesNotEndWith("\n\n");
                assertThat(json.readValue(manifest,GenerationManifest.class)).isEqualTo(expected);
                for(var f:plan.files())assertThat(entries.get(f.relativePath())).isEqualTo(f.content().getBytes(StandardCharsets.UTF_8));
            }
            assertThat(v.session().draft()).isSameAs(before);assertThat(v.session().revision()).isEqualTo(revision);assertThat(v.isDirty()).isFalse();
            var output=System.getProperty("design.sections.delivery.output");if(output!=null){var dir=Path.of(output,name);Files.createDirectories(dir);Files.write(dir.resolve(plan.files().size()==1?before.entity().className()+".java":"delivery.zip"),out.toByteArray());Files.writeString(dir.resolve("manifest.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(expected)+"\n");}
            ui.remove(v);
        }
    }
    void denied(EntityDraftEditorView v,EntityDraftEditorView.JavaExport token,int status)throws Exception{
        var bytes=new ByteArrayOutputStream();var event=downloads.event(bytes);v.javaDownloadHandler(token).handleDownloadRequest(event);
        verify(event.getResponse()).setStatus(status);verify(event,never()).getOutputStream();assertThat(bytes.size()).isZero();
    }
    @Test void lateBundleCannotOutliveRowEditReorderOwnerChangeOrReferenceContext()throws Exception{
        for(var change:List.of("row","order","owner","context")){
            var v=open("owned-catalog-2");button(v,"Показать Java").click();var token=v.javaExport();var section=v.session().draft().sections().getLast();
            switch(change){
                case "row"->{v.session().selectField(section.id(),section.fields().getFirst().id());v.session().editField(section.fields().getFirst().id(),f->f.withName("changedNote"));}
                case "order"->{v.session().selectSection(section.id());v.session().moveSelected(-1);}
                case "owner"->{var o=v.session().draft().owner();v.session().editOwner(new OwnerDraft(o.targetTypeId(),o.fieldName(),o.columnName(),"Новая подпись"));}
                case "context"->{var c=v.session().context();v.setContextSource(()->new ProjectDesignContext(c.projectId(),c.version(),c.defaultPackage(),c.existingTypes(),c.existingTables(),c.subsystemIds(),c.iconIds(),Map.of(),true,c.runtimeSnapshot()));}
            }
            denied(v,token,409);assertThat(v.session().draft().sections()).hasSize(2);ui.remove(v);
        }
    }
    @Test void actualBundleRechecksAccessAndPrincipalAndPreservesDirty()throws Exception{
        var v=open("document-2");text(v,"label").setValue("Изменённый документ");button(v,"Показать Java").click();var token=v.javaExport();
        v.javaDownloadHandler(token).handleDownloadRequest(downloads.event(new ByteArrayOutputStream()));assertThat(v.isDirty()).isTrue();
        principal("other-admin",true);denied(v,token,403);ui.remove(v);principal("admin",true);
        v=open("catalog-2");button(v,"Показать Java").click();token=v.javaExport();principal("admin",false);denied(v,token,403);assertThat(v.session()).isNull();
    }
    @Test void sectionHintsSeparateParentOwnerAndReferenceAndDiagnosticOpensExactRowField()throws Exception{
        var v=open("owned-catalog-2");var section=v.session().draft().sections().getLast();
        var header=all(v).filter(c->section.id().toString().equals(c.getElement().getAttribute("data-section-id"))).findFirst().orElseThrow();
        com.vaadin.flow.component.ComponentUtil.fireEvent(header,new com.vaadin.flow.component.ClickEvent<>(header));
        assertThat(v.getElement().getTextRecursively()).contains("Корень агрегата","Связь строки с корнем","Владелец справочника и обычные ссылки строки задаются отдельно");
        var field=section.fields().getFirst();clickRow(v,field.id());text(v,"name").setValue("lineNumber");button(v,"Проверить").click();
        var link=all(v).filter(c->c instanceof Button b&&b.getText().contains("Строки 2 / lineNumber")&&b.getText().contains("Имя занято")).map(Button.class::cast).findFirst().orElseThrow();
        link.click();assertThat(v.session().place().sectionId()).isEqualTo(section.id());assertThat(v.session().place().fieldId()).isEqualTo(field.id());assertThat(text(v,"name").isInvalid()).isTrue();
        assertThat(v.session().draft().owner()).isEqualTo(input("owned-catalog-2").owner());assertThat(v.javaExport()).isNull();
    }
}
