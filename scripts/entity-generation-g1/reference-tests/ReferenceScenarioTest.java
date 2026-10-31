package com.example.generated.harness;

import com.example.generated.targets.ReferenceTarget;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.ipro.data.EntityDataAccess;
import org.ipro.data.presentation.EntityPresentationService;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.rls.*;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

/** Independent E2 input: two ordinary references, one optional and one server-required. */
@SpringBootTest(classes = GenerationHarnessApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.autoconfigure.exclude=org.ipro.form.config.FormAutoConfiguration,org.ipro.vaadin.search.config.GlobalSearchVaadinAutoConfiguration")
class ReferenceScenarioTest {
    @Autowired EntityDataAccess data;
    @Autowired org.ipro.data.CanonicalReadExecutor reads;
    @Autowired MetadataResolver metadata;
    @Autowired EntityPresentationService presentation;
    @Autowired AccessGrantRepository grants;
    @Autowired org.ipro.crud.LookupService lookup;
    Class rootType;
    ReferenceTarget a, b;
    @BeforeEach void prepare() throws Exception {
        rootType=Class.forName(ScenarioSpec.CLASS_NAME);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("writer",null,List.of()));
        grants.deleteAll(); grant(rootType,true); grant(ReferenceTarget.class,true);
        a=new ReferenceTarget(); a.setName("Цель A " + UUID.randomUUID()); a=data.create(ReferenceTarget.class,a);
        b=new ReferenceTarget(); b.setName("Цель B " + UUID.randomUUID()); b=data.create(ReferenceTarget.class,b);
    }
    @AfterEach void clear() { grants.deleteAll(); SecurityContextHolder.clearContext(); }
    void grant(Class type, boolean write) {
        var g=new AccessGrant(); g.setSubjectType(AccessGrant.SubjectType.USER);g.setSubjectKey("writer");
        g.setDimension(C5PermissionKeys.entity(type));g.setCanRead(true);g.setCanUpdate(write);g.setCanDelete(write);grants.saveAndFlush(g);
    }
    Object entity(ReferenceTarget optional, ReferenceTarget required) throws Exception {
        var root=rootType.getDeclaredConstructor().newInstance();
        if(ScenarioSpec.BASE==ScenarioSpec.Base.DOCUMENT) rootType.getMethod("setDate",LocalDate.class).invoke(root,LocalDate.of(2026,10,29));
        else rootType.getMethod("setName",String.class).invoke(root,"Ссылочный справочник");
        rootType.getMethod("setOptionalTarget",ReferenceTarget.class).invoke(root,optional);
        rootType.getMethod("setRequiredTarget",ReferenceTarget.class).invoke(root,required);return root;
    }
    Object create(Object root) { return data.create(rootType,(IdentifiableEntity<Long>)root); }
    Object read(Object root) { return data.detail(rootType,((IdentifiableEntity<?>)root).getId()).orElseThrow(); }
    ReferenceTarget ref(Object root,String name) throws Exception {return (ReferenceTarget)rootType.getMethod("get"+name).invoke(root);}
    @Test void mappingAndLookupAreInferredWithoutCascadeOrScalarConstraints() throws Exception {
        assertEquals(ScenarioSpec.BASE.name(),metadata.resolve(rootType).getEntityKind().name());
        assertEquals(ScenarioSpec.TABLE_NAME,((Class<?>)rootType).getAnnotation(Table.class).name());
        var annotation=(org.ipro.metadata.annotation.EntityMetadata)rootType.getAnnotation(org.ipro.metadata.annotation.EntityMetadata.class);
        assertEquals(ScenarioSpec.LIST_COLUMNS,List.of(annotation.listColumns()));
        assertEquals(ScenarioSpec.SELECT_COLUMNS,List.of(annotation.selectColumns()));
        for(var name:List.of("optionalTarget","requiredTarget")) {
            boolean required=name.equals("requiredTarget");var field=rootType.getDeclaredField(name);
            assertEquals(ReferenceTarget.class,field.getType());var relation=(ManyToOne)field.getAnnotation(ManyToOne.class);
            assertNotNull(relation);assertEquals(FetchType.LAZY,relation.fetch());assertEquals(!required,relation.optional());assertEquals(0,relation.cascade().length);
            var join=(JoinColumn)field.getAnnotation(JoinColumn.class);assertEquals(required?"required_target_id":"optional_target_id",join.name());assertEquals(!required,join.nullable());
            assertNull(field.getAnnotation(Column.class));assertNull(field.getAnnotation(Size.class));assertNull(field.getAnnotation(Digits.class));
            assertEquals(required,field.isAnnotationPresent(NotNull.class));
            var info=metadata.resolve(rootType).getFieldByName(name);assertEquals(FieldType.ENTITY_REFERENCE,info.getResolvedType());assertEquals(required,info.isServerRequired());
        }
        assertTrue(ReferenceTarget.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith(".jar"));
    }
    @Test void referencesPersistReplaceAndClearAcrossClosedPersistenceContexts() throws Exception {
        var root=create(entity(a,a));var loaded=read(root);assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(a.getId(),ref(loaded,"OptionalTarget").getId());assertEquals(a.getId(),ref(loaded,"RequiredTarget").getId());
        rootType.getMethod("setOptionalTarget",ReferenceTarget.class).invoke(loaded,b);
        rootType.getMethod("setRequiredTarget",ReferenceTarget.class).invoke(loaded,b);data.update(rootType,(IdentifiableEntity<Long>)loaded);
        loaded=read(root);assertEquals(b.getId(),ref(loaded,"OptionalTarget").getId());assertEquals(b.getId(),ref(loaded,"RequiredTarget").getId());
        var key=new EntityPresentationService.Reference(ReferenceTarget.class,b.getId());assertEquals(b.getCode()+" — "+b.getName(),presentation.resolve(List.of(key)).get(key));
        // Standard list forms supply their explicit column fetch paths to the read boundary.
        var paths=metadata.resolve(rootType).getListColumnPaths().stream().flatMap(path->path.getFetchPaths().stream()).distinct().toList();
        var page=reads.readPage(new org.ipro.data.PageRead(rootType,org.ipro.fetch.plan.FetchScenario.LIST,null,org.springframework.data.domain.PageRequest.of(0,100),paths));
        var listed=page.getContent().stream().filter(item->((IdentifiableEntity<?>)item).getId().equals(((IdentifiableEntity<?>)root).getId())).findFirst().orElseThrow();
        assertEquals(b.getName(),ref(listed,"OptionalTarget").getName());
        rootType.getMethod("setOptionalTarget",ReferenceTarget.class).invoke(loaded,new Object[]{null});data.update(rootType,(IdentifiableEntity<Long>)loaded);
        assertNull(ref(read(root),"OptionalTarget"));assertEquals(b.getId(),ref(read(root),"RequiredTarget").getId());
    }
    @Test void requiredAndMissingTargetAreRejected() throws Exception {
        var missing=new ReferenceTarget();missing.setId(Long.MAX_VALUE);
        assertThrows(RuntimeException.class,()->create(entity(a,null)));
        assertThrows(RuntimeException.class,()->create(entity(a,missing)));
    }
    @Test void ordinaryLookupAndRootWriteRespectGrants() throws Exception {
        assertTrue(data.lookup(ReferenceTarget.class,a.getName(),20).stream().anyMatch(t->t.getId().equals(a.getId())));
        grants.deleteAll(); grant(rootType,true);
        assertThrows(AccessDeniedException.class,()->data.lookup(ReferenceTarget.class,"",20));
        grants.deleteAll(); grant(ReferenceTarget.class,true);
        assertThrows(AccessDeniedException.class,()->create(entity(a,a)));
    }
    @Test void usedTargetCannotBeDeletedAndRootLifecycleDoesNotMutateTargets() throws Exception {
        var root=create(entity(a,a));
        assertThrows(RuntimeException.class,()->data.delete(ReferenceTarget.class,a.getId()));
        assertEquals(a.getName(),data.detail(ReferenceTarget.class,a.getId()).orElseThrow().getName());
        data.delete(rootType,((IdentifiableEntity<?>)root).getId());
        assertEquals(a.getName(),data.detail(ReferenceTarget.class,a.getId()).orElseThrow().getName());
        assertEquals(b.getName(),data.detail(ReferenceTarget.class,b.getId()).orElseThrow().getName());
    }
    @Test void standardFieldFactoryBindingsAndLazyLookupUseActualGeneratedMetadata() throws Exception {
        var ui=new com.vaadin.flow.component.UI();com.vaadin.flow.component.UI.setCurrent(ui);
        try {
            var assembler=new org.ipro.form.SelectionFormAssembler(metadata,null);
            assertEquals(List.of("code","name"),assembler.resolveColumns(ReferenceTarget.class).columns().stream().map(org.ipro.metadata.ColumnPath::getKey).toList());
            var factory=new org.ipro.form.FieldFactory(lookup,assembler,metadata,new org.ipro.form.registry.FormRegistry());
            var bindings=new org.ipro.form.FormBindingRegistry();
            var optional=(org.ipro.form.EntityField)factory.createField(metadata.resolve(rootType).getFieldByName("optionalTarget"),bindings);
            var required=(org.ipro.form.EntityField)factory.createField(metadata.resolve(rootType).getFieldByName("requiredTarget"),bindings);
            assertFalse(bindings.isValid());required.setValue(a);assertTrue(bindings.isValid());optional.setValue(b);
            var root=entity(null,null);bindings.applyAllToEntity(root);var saved=create(root);var loaded=read(saved);bindings.readAllFromEntity(loaded);
            assertEquals(a.getId(),((ReferenceTarget)required.getValue()).getId());assertEquals(b.getId(),((ReferenceTarget)optional.getValue()).getId());
            optional.clear();bindings.applyAllToEntity(loaded);data.update(rootType,(IdentifiableEntity<Long>)loaded);assertNull(ref(read(saved),"OptionalTarget"));
            var box=new com.vaadin.flow.component.combobox.ComboBox<ReferenceTarget>();
            org.ipro.form.LookupComboHelper.installSuggestItems(box,ReferenceTarget.class,lookup,metadata);
            org.ipro.form.LookupComboHelper.restoreSavedSelection(box,ReferenceTarget.class,a.getId().toString(),lookup);
            assertEquals(a.getId(),box.getValue().getId());
        } finally {com.vaadin.flow.component.UI.setCurrent(null);}
    }
}
