package com.example.generated.harness;

import com.example.generated.targets.ReferenceTarget;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import org.ipro.crud.*;
import org.ipro.data.*;
import org.ipro.events.EventSource;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.*;
import org.ipro.metadata.annotation.*;
import org.ipro.rls.*;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** E4.A actual-set acceptance, independent expectations authored from the input spec. */
@SpringBootTest(classes=GenerationHarnessApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class SectionsScenarioTest {
    @Autowired EntityDataAccess data;
    @Autowired MetadataDrivenAggregateSaveService aggregates;
    @Autowired GenericOwnedSectionService rows;
    @Autowired SectionMetadataRegistry sections;
    @Autowired MetadataResolver metadata;
    @Autowired EntityDescriptorCatalog descriptors;
    @Autowired AccessGrantRepository grants;
    @Autowired JdbcTemplate jdbc;
    Class rootType; ReferenceTarget a,b;
    @BeforeEach void prepare() throws Exception {
        rootType=Class.forName(ScenarioSpec.CLASS_NAME);grants.deleteAll();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("writer",null,List.of()));
        grant(rootType);grant(ReferenceTarget.class);a=target("A");b=target("B");
    }
    @AfterEach void clear(){grants.deleteAll();SecurityContextHolder.clearContext();}
    void grant(Class type){var g=new AccessGrant();g.setSubjectType(AccessGrant.SubjectType.USER);g.setSubjectKey("writer");g.setDimension(C5PermissionKeys.entity(type));g.setCanRead(true);g.setCanUpdate(true);g.setCanDelete(true);grants.saveAndFlush(g);}
    ReferenceTarget target(String text){var p=new ReferenceTarget();p.setName(text+" "+UUID.randomUUID());return data.create(ReferenceTarget.class,p);}
    Object root() throws Exception {var e=rootType.getDeclaredConstructor().newInstance();set(e,ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT?"date":"name",ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT?ScenarioSpec.DATE_SAMPLE:"Комплект "+UUID.randomUUID());if(ScenarioSpec.OWNER_ENABLED)set(e,"counterparty",a);return e;}
    Object row(ScenarioSpec.SectionSpec s,String text,ReferenceTarget target) throws Exception {var r=Class.forName(s.className()).getDeclaredConstructor().newInstance();set(r,s.fieldName(),text);set(r,"target",target);return r;}
    List<MetadataDrivenAggregateSaveService.SectionInput> inputs(int count) throws Exception {
        var inputs=new ArrayList<MetadataDrivenAggregateSaveService.SectionInput>();
        for(var s:ScenarioSpec.SECTIONS){var values=new ArrayList<Object>();for(int i=0;i<count;i++)values.add(row(s,"row "+i,a));inputs.add(input(s,values));}return inputs;
    }
    MetadataDrivenAggregateSaveService.SectionInput input(ScenarioSpec.SectionSpec s,List<?> values) throws Exception {return MetadataDrivenAggregateSaveService.SectionInput.attached((Class)Class.forName(s.className()),values);}
    MetadataDrivenAggregateSaveService.AggregateSaveResult save(Object e,List<MetadataDrivenAggregateSaveService.SectionInput> inputs){return aggregates.save((IdentifiableEntity<Long>)e,inputs,EventSource.UI);}
    Object reopen(Object e){return data.detail(rootType,id(e)).orElseThrow();}
    // GenericOwnedSectionService is persistence-only; the caller authorizes the root.
    List<?> readRows(Object parent,ScenarioSpec.SectionSpec s) throws Exception {var authorized=reopen(parent);return rows.findByParent((IdentifiableEntity<Long>)authorized,sections.findByRow(Class.forName(s.className())).orElseThrow());}
    Long id(Object e){return ((IdentifiableEntity<Long>)e).getId();}
    long countRows(ScenarioSpec.SectionSpec s,Object parent){return jdbc.queryForObject("select count(*) from "+s.tableName()+" where "+s.parentField()+"_id = ?",Long.class,id(parent));}
    @Test void wholeSetMetadataDeclaresExactTechnicalAndSubjectRoles() throws Exception {
        assertEquals(ScenarioSpec.BASE.name(),metadata.resolve(rootType).getEntityKind().name());
        assertEquals(ScenarioSpec.SECTIONS.stream().map(s->s.className()).toList(),Arrays.stream(((TableSections)rootType.getAnnotation(TableSections.class)).value()).map(Class::getName).toList());
        assertEquals(ScenarioSpec.SECTIONS.size(),sections.forOwner(rootType).size());
        for(var s:ScenarioSpec.SECTIONS){
            var type=Class.forName(s.className());var info=sections.findByRow(type).orElseThrow();
            assertEquals(rootType,info.getOwnerClass());assertEquals(s.parentField(),info.getParentFieldName());assertEquals(s.title(),info.getTitle());assertEquals(s.order(),info.getOrder());assertEquals(s.tableName(),type.getAnnotation(Table.class).name());
            assertEquals("OWNED_ROW",descriptors.descriptorOf(type).exposure().name());assertEquals("lineNumber",info.getLineNumberFieldName());assertEquals(0,info.getMinRows());
            var declaration=type.getAnnotation(TableSectionMetadata.class);assertEquals(SectionPersistenceMode.MUTABLE_REPLACE_ALL,declaration.persistence());assertEquals(SectionRlsPolicy.INHERIT_ROOT,declaration.rlsPolicy());
            var parent=type.getDeclaredField(s.parentField());assertEquals(rootType,parent.getType());assertTrue(parent.isAnnotationPresent(NotNull.class));assertNull(parent.getAnnotation(FieldMetadata.class));
            var relation=parent.getAnnotation(ManyToOne.class);assertEquals(FetchType.LAZY,relation.fetch());assertFalse(relation.optional());assertEquals(0,relation.cascade().length);
            var join=parent.getAnnotation(JoinColumn.class);assertEquals(s.parentField()+"_id",join.name());assertFalse(join.nullable());
            var number=type.getDeclaredField("lineNumber");assertEquals(Integer.class,number.getType());assertEquals("line_number",number.getAnnotation(Column.class).name());assertNull(number.getAnnotation(FieldMetadata.class));assertNull(type.getAnnotation(CatalogOwner.class));
            assertEquals(List.of("note","target"),info.getFormFields().stream().map(FieldMetadataInfo::getName).toList());assertEquals(List.of("note","target"),info.getGridFields().stream().map(FieldMetadataInfo::getName).toList());
            assertEquals(10,metadata.resolveAllAnnotatedFields(type).stream().filter(f->f.getName().equals("note")).findFirst().orElseThrow().getOrder());
            assertEquals(60,type.getDeclaredField("note").getAnnotation(Size.class).max());assertEquals(ReferenceTarget.class,type.getDeclaredField("target").getType());
        }
    }
    @Test void aggregateCreatesAllRowsAndReopensOrderedReferencesOutsideTransaction() throws Exception {
        var result=save(root(),inputs(2));var parent=reopen(result.aggregate());assertEquals(ScenarioSpec.SECTIONS.size(),result.sections().size());
        for(var s:ScenarioSpec.SECTIONS){var loaded=readRows(parent,s);assertEquals(2,loaded.size());for(int i=0;i<2;i++){
            var r=loaded.get(i);assertEquals(i+1,get(r,"lineNumber"));assertEquals("row "+i,get(r,"note"));assertEquals(id(parent),id(get(r,s.parentField())));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(a.getName(),((ReferenceTarget)get(r,"target")).getName());
        }}
    }
    @Test void replaceUpdatesRemovesReordersAndClearsOnlyAttachedSectionThenRootDeleteCleansRows() throws Exception {
        var saved=save(root(),inputs(2)).aggregate();var s=ScenarioSpec.SECTIONS.getFirst();var original=readRows(saved,s);var kept=original.getFirst();Long removed=id(original.get(1));
        set(kept,"note","Updated");set(kept,"target",b);
        save(reopen(saved),List.of(input(s,List.of(row(s,"Inserted",a),kept))));
        var current=readRows(reopen(saved),s);assertEquals(2,current.size());assertEquals("Inserted",get(current.get(0),"note"));assertEquals(id(kept),id(current.get(1)));assertFalse(current.stream().anyMatch(r->id(r).equals(removed)));assertEquals(2,get(current.get(1),"lineNumber"));assertEquals(b.getId(),((ReferenceTarget)get(current.get(1),"target")).getId());
        save(reopen(saved),List.of(input(s,List.of())));assertTrue(readRows(reopen(saved),s).isEmpty());
        for(var untouched:ScenarioSpec.SECTIONS.subList(1,ScenarioSpec.SECTIONS.size()))assertEquals(2,readRows(reopen(saved),untouched).size());
        data.delete(rootType,id(saved));for(var expected:ScenarioSpec.SECTIONS)assertEquals(0,countRows(expected,saved));
        assertEquals(a.getName(),data.detail(ReferenceTarget.class,a.getId()).orElseThrow().getName());assertEquals(b.getName(),data.detail(ReferenceTarget.class,b.getId()).orElseThrow().getName());
    }
    @Test void invalidLastSectionRollsBackHeaderAndEverySectionIncludingNewRoot() throws Exception {
        var saved=save(root(),inputs(1)).aggregate();var before=reopen(saved);Object header=get(before,ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT?"date":"name");
        var changed=reopen(saved);set(changed,ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT?"date":"name",ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT?ScenarioSpec.DATE_UPDATED:"Failed header");
        var inputs=inputs(1);var last=ScenarioSpec.SECTIONS.getLast();inputs.set(inputs.size()-1,input(last,List.of(row(last,null,a))));
        assertThrows(RuntimeException.class,()->save(changed,inputs));assertEquals(header,get(reopen(saved),ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT?"date":"name"));
        for(var s:ScenarioSpec.SECTIONS){assertEquals(1,countRows(s,saved));assertEquals("row 0",get(readRows(reopen(saved),s).getFirst(),"note"));}
        long rootCount=jdbc.queryForObject("select count(*) from "+ScenarioSpec.TABLE_NAME,Long.class);var fresh=root();assertThrows(RuntimeException.class,()->save(fresh,inputs));assertEquals(rootCount,jdbc.queryForObject("select count(*) from "+ScenarioSpec.TABLE_NAME,Long.class));
    }
    @Test void foreignRowCannotBeReparentedAndStandaloneRowCrudIsDenied() throws Exception {
        var first=save(root(),inputs(1)).aggregate();var second=save(root(),inputs(1)).aggregate();var s=ScenarioSpec.SECTIONS.getFirst();var foreign=readRows(first,s).getFirst();
        var input=input(s,List.of(foreign));assertThrows(RuntimeException.class,()->save(reopen(second),List.of(input)));
        assertEquals(id(foreign),id(readRows(reopen(first),s).getFirst()));assertEquals(1,countRows(s,second));
        var rowType=(Class)Class.forName(s.className());grant(rowType);var raw=row(s,"standalone",a);set(raw,s.parentField(),first);
        assertThrows(RuntimeException.class,()->data.create(rowType,(IdentifiableEntity<Long>)raw));
        assertThrows(RuntimeException.class,()->data.update(rowType,(IdentifiableEntity<Long>)foreign));assertThrows(RuntimeException.class,()->data.delete(rowType,id(foreign)));
    }
    @Test void lateForeignKeyFailureRollsBackEverySectionAndHeader() throws Exception {
        var saved=save(root(),inputs(1)).aggregate();var before=reopen(saved);var headerField=ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT?"date":"name";var header=get(before,headerField);
        var originalIds=new ArrayList<Long>();for(var s:ScenarioSpec.SECTIONS)originalIds.add(id(readRows(saved,s).getFirst()));
        var changed=reopen(saved);set(changed,headerField,ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT?ScenarioSpec.DATE_UPDATED:"Failed header");
        var missing=new ReferenceTarget();missing.setId(Long.MAX_VALUE);missing.setVersion(0L);
        var values=inputs(1);var last=ScenarioSpec.SECTIONS.getLast();values.set(values.size()-1,input(last,List.of(row(last,"valid note",missing))));
        assertThrows(RuntimeException.class,()->save(changed,values));assertEquals(header,get(reopen(saved),headerField));
        for(int i=0;i<ScenarioSpec.SECTIONS.size();i++){var loaded=readRows(saved,ScenarioSpec.SECTIONS.get(i));assertEquals(1,loaded.size());assertEquals(originalIds.get(i),id(loaded.getFirst()));assertEquals("row 0",get(loaded.getFirst(),"note"));}
    }
    @Test void staleAggregateVersionCannotOverwriteSectionChanges() throws Exception {
        var saved=save(root(),inputs(1)).aggregate();var stale=reopen(saved);var current=reopen(saved);var s=ScenarioSpec.SECTIONS.getFirst();
        save(current,List.of(input(s,List.of(row(s,"winner",a)))));
        var input=input(s,List.of(row(s,"stale",b)));var error=assertThrows(RuntimeException.class,()->save(stale,List.of(input)));
        boolean optimistic=false;for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof OptimisticLockException||cause instanceof org.springframework.dao.OptimisticLockingFailureException)optimistic=true;
        assertTrue(optimistic,error.toString());assertEquals("winner",get(readRows(reopen(saved),s).getFirst(),"note"));
    }
    @Test void rowsInheritRootReadWriteDenialWithoutStandaloneGrantEscape() throws Exception {
        var saved=save(root(),inputs(1)).aggregate();grants.deleteAll();grant(ReferenceTarget.class);
        var pending=root();var values=inputs(1);assertThrows(AccessDeniedException.class,()->save(pending,values));
        for(var s:ScenarioSpec.SECTIONS){grant(Class.forName(s.className()));assertThrows(AccessDeniedException.class,()->readRows(saved,s));}
    }
    @Test void rootOwnerRowParentAndMutableRowTargetDoNotShareSemantics() throws Exception {
        var saved=save(root(),inputs(1)).aggregate();var s=ScenarioSpec.SECTIONS.getFirst();var changed=readRows(saved,s).getFirst();set(changed,"target",b);
        save(reopen(saved),List.of(input(s,List.of(changed))));assertEquals(b.getId(),((ReferenceTarget)get(readRows(reopen(saved),s).getFirst(),"target")).getId());
        if(ScenarioSpec.OWNER_ENABLED){var transfer=reopen(saved);set(transfer,"counterparty",b);var values=inputs(1);assertThrows(CatalogOwnerViolationException.class,()->save(transfer,values));assertEquals(a.getId(),((ReferenceTarget)get(reopen(saved),"counterparty")).getId());}
        else assertNull(descriptors.descriptorOf(rootType).owner());
        assertEquals(id(saved),id(get(readRows(reopen(saved),s).getFirst(),s.parentField())));
    }
    static Object get(Object e,String name)throws Exception{var f=field(e.getClass(),name);return f.get(e);}
    static void set(Object e,String name,Object value)throws Exception{field(e.getClass(),name).set(e,value);}
    static Field field(Class<?> type,String name)throws Exception{for(var t=type;t!=null;t=t.getSuperclass())try{var f=t.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
}
