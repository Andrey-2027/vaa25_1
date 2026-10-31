package com.example.generated.harness;

import com.example.generated.targets.ReferenceTarget;
import com.vaadin.flow.component.*;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.*;
import org.ipro.crud.*;
import org.ipro.data.*;
import org.ipro.form.*;
import org.ipro.form.builtin.*;
import org.ipro.form.registry.*;
import org.ipro.form.spi.*;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.*;
import org.ipro.rls.*;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual P8-delivered generated classes, real metadata/forms/lookup/aggregate persistence, H2. */
@SpringBootTest(classes=GenerationHarnessApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
@org.springframework.context.annotation.Import(GeneratedSectionFormsTest.Stores.class)
class GeneratedSectionFormsTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class Stores {
        @org.springframework.context.annotation.Bean FormSettingsStore settings(){var s=mock(FormSettingsStore.class);when(s.get(anyString())).thenReturn(Optional.empty());return s;}
        @org.springframework.context.annotation.Bean GridViewStore views(){return mock(GridViewStore.class);}
    }
    @Autowired EntityDataAccess data;
    @Autowired LookupService lookup;
    @Autowired MetadataResolver metadata;
    @Autowired SectionMetadataRegistry sections;
    @Autowired GenericOwnedSectionService rows;
    @Autowired MetadataDrivenAggregateSaveService aggregates;
    @Autowired AccessGrantRepository grants;
    UI ui;Class rootType;ReferenceTarget a,b;
    @Autowired FieldFactory fields;
    @Autowired TableSectionFactory factory;
    @Autowired MetadataDrivenItemFormSaveAdapter adapter;
    @BeforeEach void setup()throws Exception{
        rootType=Class.forName(ScenarioSpec.CLASS_NAME);grants.deleteAll();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("writer",null,List.of()));
        grant(rootType);grant(ReferenceTarget.class);a=target("A");b=target("B");
        ui=new UI();var session=mock(VaadinSession.class);when(session.hasLock()).thenReturn(true);when(session.getLocale()).thenReturn(Locale.forLanguageTag("ru"));when(session.getResourceRegistry()).thenReturn(new StreamResourceRegistry(session));ui.getInternals().setSession(session);UI.setCurrent(ui);
    }
    @AfterEach void cleanup(){UI.setCurrent(null);grants.deleteAll();SecurityContextHolder.clearContext();}
    void grant(Class<?> type){var g=new AccessGrant();g.setSubjectType(AccessGrant.SubjectType.USER);g.setSubjectKey("writer");g.setDimension(C5PermissionKeys.entity(type));g.setCanRead(true);g.setCanUpdate(true);g.setCanDelete(true);grants.saveAndFlush(g);}
    ReferenceTarget target(String name){var t=new ReferenceTarget();t.setName(name+UUID.randomUUID());return data.create(ReferenceTarget.class,t);}
    ItemForm form(){var f=new ItemForm(metadata.resolve(rootType),fields);factory.attachTableSections(f,rootType);f.setSaveHandler(adapter::save);ui.add(f);return f;}
    ItemForm fresh(){var f=form();var initial=new HashMap<String,Object>();
        initial.put(ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT?"date":"name",ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT?LocalDate.of(2026,10,30):"Форма "+UUID.randomUUID());
        // Manual value avoids making UI form validation depend on the numbering allocator.
        initial.put(ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT?"number":"code","UI"+UUID.randomUUID().toString().substring(0,12));
        if(metadata.resolve(rootType).getFieldByName("counterparty")!=null)initial.put("counterparty",a);
        f.initializeNewEntity(initial);return f;
    }
    Stream<Component> all(Component c){return Stream.concat(Stream.of(c),c.getChildren().flatMap(this::all));}
    Button button(Component c,String text){
        if(c instanceof ItemTable){
            for(var tabs:all(ui).filter(com.vaadin.flow.component.tabs.TabSheet.class::isInstance).map(com.vaadin.flow.component.tabs.TabSheet.class::cast).toList()){
                var tab=tabs.getTab(c);if(tab!=null){tabs.setSelectedTab(tab);ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();break;}
            }
        }
        return all(c).filter(x->x instanceof Button b&&b.getText().equals(text)).map(Button.class::cast).findFirst().orElseThrow();
    }
    Dialog dialog(){ui.getInternals().getStateTree().runExecutionsBeforeClientResponse();return all(ui).filter(c->c instanceof Dialog d&&d.isOpened()).map(Dialog.class::cast).findFirst().orElseThrow();}
    ItemForm rowForm(Dialog d){return all(d).filter(ItemForm.class::isInstance).map(ItemForm.class::cast).findFirst().orElseThrow();}
    void note(ItemForm f,String value){((TextField)f.getField("note")).setValue(value);}
    void target(ItemForm f,ReferenceTarget value){((EntityField)f.getField("target")).setValue(value);}
    void add(ItemTable table,String value,ReferenceTarget target){button(table,"Добавить").click();var d=dialog();var f=rowForm(d);note(f,value);target(f,target);button(d,"Сохранить").click();assertFalse(d.isOpened());}
    void select(ItemTable table,Object row){((Grid)all(table).filter(Grid.class::isInstance).findFirst().orElseThrow()).asSingleSelect().setValue(row);}
    ItemForm reopen(ItemForm f){var id=((IdentifiableEntity<Long>)f.peekEntity()).getId();var loaded=(IdentifiableEntity<Long>)data.detail(rootType,id).orElseThrow();var next=form();next.setEntity(loaded);return next;}
    @Test void factoryBuildsExactDeclaredSectionsAndHidesTechnicalFields(){
        var f=fresh();var declared=sections.forOwner(rootType);assertEquals(declared.size(),f.getTableSections().size());
        for(int i=0;i<declared.size();i++){var s=declared.get(i);var table=(ItemTable)f.getTableSections().get(i);assertEquals(s.getRowClass(),table.getRowClass());
            button(table,"Добавить").click();var d=dialog();var row=rowForm(d);
            assertNotNull(row.getField("note"));assertInstanceOf(EntityField.class,row.getField("target"));
            assertThrows(IllegalStateException.class,()->row.getField(s.getParentFieldName()));assertThrows(IllegalStateException.class,()->row.getField("lineNumber"));assertFalse(((EntityField)row.getField("target")).isReadOnly());d.close();
        }
    }
    @Test void standardDialogsAddEditRemoveAndReopenAllSectionsWithReferenceLookup()throws Exception{
        var f=fresh();for(var o:f.getTableSections()){var table=(ItemTable)o;add(table,"Первая",a);add(table,"Вторая",b);assertTrue(table.isDirty());}
        assertInstanceOf(FormSaveResult.Success.class,f.save());assertFalse(f.isDirty());var reopened=reopen(f);
        for(var o:reopened.getTableSections()){var table=(ItemTable)o;assertEquals(2,table.getRows().size());var first=table.getRows().getFirst();select(table,first);button(table,"Изменить").click();var d=dialog();var row=rowForm(d);
            assertEquals(a.getId(),((ReferenceTarget)((EntityField)row.getField("target")).getValue()).getId());
            var box=new com.vaadin.flow.component.combobox.ComboBox<ReferenceTarget>();LookupComboHelper.installSuggestItems(box,ReferenceTarget.class,lookup,metadata);LookupComboHelper.restoreSavedSelection(box,ReferenceTarget.class,b.getId().toString(),lookup);assertEquals(b.getId(),box.getValue().getId());
            note(row,"Изменена");target(row,box.getValue());button(d,"Сохранить").click();assertFalse(d.isOpened());
            select(table,table.getRows().get(1));button(table,"Удалить").click();assertEquals(1,table.getRows().size());
        }
        assertInstanceOf(FormSaveResult.Success.class,reopened.save());var latest=reopen(reopened);
        for(var o:latest.getTableSections()){var table=(ItemTable)o;assertEquals(1,table.getRows().size());var value=table.getRows().getFirst();var s=sections.findByRow(table.getRowClass()).orElseThrow();assertEquals(1,s.getLineNumberField().get(value));
            select(table,value);button(table,"Изменить").click();var d=dialog();var row=rowForm(d);assertEquals("Изменена",((TextField)row.getField("note")).getValue());assertEquals(b.getName(),((ReferenceTarget)((EntityField)row.getField("target")).getValue()).getName());d.close();
            select(table,value);button(table,"Удалить").click();
        }
        assertInstanceOf(FormSaveResult.Success.class,latest.save());for(var o:reopen(latest).getTableSections())assertTrue(((ItemTable)o).getRows().isEmpty());
    }
    @Test void rowValidationKeepsDialogAndRootWriteDenialKeepsDraftRows(){
        var f=fresh();if(!f.getTableSections().isEmpty()){
            var table=(ItemTable)f.getTableSections().getFirst();button(table,"Добавить").click();var d=dialog();var row=rowForm(d);assertFalse(row.isValid());button(d,"Сохранить").click();assertTrue(d.isOpened());assertTrue(table.getRows().isEmpty());
            note(row,"Не потерять");target(row,a);button(d,"Сохранить").click();assertEquals(1,table.getRows().size());
        }else if(ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT)f.getEntityField("date",LocalDate.class).setValue(LocalDate.of(2026,11,1));else ((TextField)f.getField("name")).setValue("Несохранённая правка");
        grants.deleteAll();grant(ReferenceTarget.class);assertInstanceOf(FormSaveResult.Failure.class,f.save());assertTrue(f.isDirty());
        for(int i=0;i<f.getTableSections().size();i++)assertEquals(i==0?1:0,((ItemTable)f.getTableSections().get(i)).getRows().size());
        grant(rootType);assertInstanceOf(FormSaveResult.Success.class,f.save());
        if(!f.getTableSections().isEmpty()){
            var table=(ItemTable)f.getTableSections().getLast();int persisted=table.getRows().size();
            var missing=new ReferenceTarget();missing.setId(Long.MAX_VALUE);missing.setVersion(0L);missing.setName("Удалённая цель");
            add(table,"Исправить после ошибки",missing);assertInstanceOf(FormSaveResult.Failure.class,f.save());assertTrue(f.isDirty());assertEquals(persisted+1,table.getRows().size());
            assertEquals(persisted,((ItemTable)reopen(f).getTableSections().getLast()).getRows().size());
            select(table,table.getRows().getLast());button(table,"Изменить").click();var d=dialog();target(rowForm(d),b);button(d,"Сохранить").click();assertInstanceOf(FormSaveResult.Success.class,f.save());
        }
        grants.deleteAll();grant(rootType);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->lookup.findSelectedById(ReferenceTarget.class,a.getId()));
    }
    @Test void rootOwnerLockDoesNotLockRowReferencesAndReadOnlyDisablesSectionActions(){
        var f=fresh();for(var o:f.getTableSections())add((ItemTable)o,"Строка",a);assertInstanceOf(FormSaveResult.Success.class,f.save());
        if(metadata.resolve(rootType).getFieldByName("counterparty")!=null)assertTrue(((EntityField)f.getField("counterparty")).isReadOnly());
        for(var o:f.getTableSections()){var table=(ItemTable)o;select(table,table.getRows().getFirst());button(table,"Изменить").click();var d=dialog();var row=rowForm(d);assertFalse(((EntityField)row.getField("target")).isReadOnly());target(row,b);button(d,"Сохранить").click();}
        assertInstanceOf(FormSaveResult.Success.class,f.save());f.setReadOnly(true);
        for(var o:f.getTableSections()){var table=(ItemTable)o;assertTrue(table.isReadOnly());assertFalse(button(table,"Добавить").isEnabled());assertFalse(button(table,"Изменить").isEnabled());assertFalse(button(table,"Удалить").isEnabled());}
        assertInstanceOf(FormSaveResult.Failure.class,f.save());
    }
}
