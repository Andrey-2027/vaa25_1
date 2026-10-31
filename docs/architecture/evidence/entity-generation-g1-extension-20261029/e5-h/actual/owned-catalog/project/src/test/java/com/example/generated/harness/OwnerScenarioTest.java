package com.example.generated.harness;

import com.example.generated.targets.*;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.ipro.data.*;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.*;
import org.ipro.metadata.annotation.*;
import org.ipro.rls.*;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent E3.C expectations: required immutable counterparty, ordinary reference control. */
@SpringBootTest(classes=GenerationHarnessApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class OwnerScenarioTest {
    @Autowired EntityDataAccess data;
    @Autowired CanonicalReadExecutor reads;
    @Autowired EntityDescriptorCatalog descriptors;
    @Autowired MetadataResolver metadata;
    @Autowired AccessGrantRepository grants;
    Class rootType; ReferenceTarget a,b;
    @BeforeEach void prepare() throws Exception {
        rootType=Class.forName(ScenarioSpec.CLASS_NAME);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("writer",null,List.of()));
        grants.deleteAll();grant(rootType,true);grant(ReferenceTarget.class,true);grant(OrdinaryOwnerReference.class,true);
        a=target("A");b=target("B");
    }
    @AfterEach void clear(){grants.deleteAll();SecurityContextHolder.clearContext();}
    void grant(Class type,boolean write){var g=new AccessGrant();g.setSubjectType(AccessGrant.SubjectType.USER);g.setSubjectKey("writer");g.setDimension(C5PermissionKeys.entity(type));g.setCanRead(true);g.setCanUpdate(write);g.setCanDelete(write);grants.saveAndFlush(g);}
    ReferenceTarget target(String name){var p=new ReferenceTarget();p.setName("Контрагент "+name+" "+UUID.randomUUID());return data.create(ReferenceTarget.class,p);}
    Object entity(ReferenceTarget owner) throws Exception {var e=rootType.getDeclaredConstructor().newInstance();rootType.getMethod("setName",String.class).invoke(e,"Договор "+UUID.randomUUID());rootType.getMethod("setDescription",String.class).invoke(e,"Первая версия");setOwner(e,owner);return e;}
    void setOwner(Object e,ReferenceTarget owner) throws Exception {rootType.getMethod("setCounterparty",ReferenceTarget.class).invoke(e,owner);}
    ReferenceTarget owner(Object e) throws Exception {return (ReferenceTarget)rootType.getMethod("getCounterparty").invoke(e);}
    Long id(Object e){return (Long)((IdentifiableEntity<?>)e).getId();}
    Object create(Object e){return data.create(rootType,(IdentifiableEntity<Long>)e);}
    Object read(Object e){return data.detail(rootType,id(e)).orElseThrow();}
    Object update(Object e){return data.update(rootType,(IdentifiableEntity<Long>)e);}
    @Test void exactOwnerCapabilityMappingPublicationAndOrderAreResolved() throws Exception {
        assertEquals("CATALOG",metadata.resolve(rootType).getEntityKind().name());
        assertEquals(ScenarioSpec.TABLE_NAME,((Table)rootType.getAnnotation(Table.class)).name());
        assertEquals(new CatalogOwnerDescriptor("counterparty",ReferenceTarget.class.getName()),descriptors.descriptorOf(rootType).owner());
        var annotation=(CatalogOwner)rootType.getAnnotation(CatalogOwner.class);assertEquals("counterparty",annotation.property());
        var field=rootType.getDeclaredField("counterparty");assertEquals(ReferenceTarget.class,field.getType());
        var relation=(ManyToOne)field.getAnnotation(ManyToOne.class);assertEquals(FetchType.LAZY,relation.fetch());assertFalse(relation.optional());assertEquals(0,relation.cascade().length);
        var join=(JoinColumn)field.getAnnotation(JoinColumn.class);assertEquals("counterparty_id",join.name());assertFalse(join.nullable());assertTrue(field.isAnnotationPresent(NotNull.class));assertNull(field.getAnnotation(Column.class));
        var meta=(EntityMetadata)rootType.getAnnotation(EntityMetadata.class);
        assertEquals("Договоры",meta.listFormTitle());assertEquals("Договоры",meta.itemFormTitle());assertEquals("Договоры",meta.selectionFormTitle());assertEquals("FILE",meta.icon());
        assertEquals(List.of("code","name","counterparty","description"),List.of(meta.listColumns()));assertEquals(List.of(meta.listColumns()),List.of(meta.selectColumns()));
        var info=metadata.resolve(rootType);assertEquals(30,info.getFieldByName("counterparty").getOrder());assertEquals(40,info.getFieldByName("description").getOrder());
        assertEquals(FieldType.ENTITY_REFERENCE,info.getFieldByName("counterparty").getResolvedType());assertTrue(info.getFieldByName("counterparty").isServerRequired());
        assertEquals("Контрагент",info.getFieldByName("counterparty").getLabel());assertTrue(info.getFormFields().stream().anyMatch(f->f.getName().equals("counterparty")));
        assertFalse(info.getFieldByName("counterparty").isReadOnly());
        assertTrue(ReferenceTarget.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith(".jar"));
        assertTrue(OrdinaryOwnerReference.class.getProtectionDomain().getCodeSource().getLocation().toString().endsWith(".jar"));
    }
    @Test void createReopenUpdateAndOwnerFilteredListAndSelectionUseClosedContexts() throws Exception {
        var first=create(entity(a));var second=create(entity(b));var loaded=read(first);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertEquals(a.getId(),owner(loaded).getId());
        var code=rootType.getMethod("getCode").invoke(loaded);rootType.getMethod("setDescription",String.class).invoke(loaded,"Обновлённый договор");update(loaded);
        loaded=read(first);assertEquals(code,rootType.getMethod("getCode").invoke(loaded));assertEquals("Обновлённый договор",rootType.getMethod("getDescription").invoke(loaded));
        for(var scenario:List.of(FetchScenario.LIST,FetchScenario.LOOKUP)) for(var target:List.of(a,b)) {
            Specification filter=(root,query,cb)->cb.equal(root.get("counterparty").get("id"),target.getId());
            var page=reads.readPage(new PageRead(rootType,scenario,filter,PageRequest.of(0,20),List.of("counterparty")));
            assertEquals(1,page.getTotalElements());var row=page.getContent().getFirst();
            assertEquals(target==a?id(first):id(second),id(row));assertEquals(target.getName(),owner(row).getName());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        }
        assertEquals(a.getName(),data.detail(ReferenceTarget.class,a.getId()).orElseThrow().getName());
        assertEquals(b.getName(),data.detail(ReferenceTarget.class,b.getId()).orElseThrow().getName());
    }
    @Test void requiredNullAndMissingTargetAreRejected() throws Exception {
        assertThrows(RuntimeException.class,()->create(entity(null)));
        var missing=new ReferenceTarget();missing.setId(Long.MAX_VALUE);assertThrows(RuntimeException.class,()->create(entity(missing)));
    }
    @Test void ownerTransferFailsAgainstPersistedStateButAnotherInstanceWithSameIdIsAllowed() throws Exception {
        var saved=create(entity(a));var loaded=read(saved);setOwner(loaded,b);
        var error=assertThrows(CatalogOwnerViolationException.class,()->update(loaded));assertEquals(CatalogOwnerViolationException.Reason.OWNER_IMMUTABLE,error.reason());
        assertEquals(a.getId(),owner(read(saved)).getId());
        var same=data.detail(ReferenceTarget.class,a.getId()).orElseThrow();assertNotSame(a,same);
        same.setName("Не должно изменить target");setOwner(loaded,same);update(loaded);
        assertEquals(a.getId(),owner(read(saved)).getId());assertEquals(a.getName(),data.detail(ReferenceTarget.class,a.getId()).orElseThrow().getName());
    }
    @Test void ownerContextRejectsSubstitutedOwnerAndRestoresThreadAfterSuccessAndFailure() throws Exception {
        Object correct=entity(a),wrong=entity(b);
        var created=CatalogOwnerContext.call(ReferenceTarget.class.getName(),a.getId(),()->create(correct));assertNotNull(id(created));
        assertThrows(CatalogOwnerViolationException.class,()->CatalogOwnerContext.call(ReferenceTarget.class.getName(),a.getId(),()->create(wrong)));
        assertTrue(CatalogOwnerContext.current().isEmpty());
    }
    @Test void usedOwnerDeletionFailsAndChildDeletionPreservesTargets() throws Exception {
        var saved=create(entity(a));
        assertThrows(RuntimeException.class,()->data.delete(ReferenceTarget.class,a.getId()));
        data.delete(rootType,id(saved));
        for(var target:List.of(a,b)){var found=data.detail(ReferenceTarget.class,target.getId()).orElseThrow();assertEquals(target.getName(),found.getName());assertEquals(target.getCode(),found.getCode());}
    }
    @Test void ownerDoesNotGrantRootWriteReadOrTargetLookupPermissions() throws Exception {
        var saved=create(entity(a));grants.deleteAll();grant(ReferenceTarget.class,true);
        assertThrows(AccessDeniedException.class,()->create(entity(a)));assertThrows(AccessDeniedException.class,()->read(saved));
        grants.deleteAll();grant(rootType,true);assertThrows(AccessDeniedException.class,()->data.lookup(ReferenceTarget.class,"",10));
        grants.deleteAll();grant(rootType,false);grant(ReferenceTarget.class,true);
        var loaded=read(saved);assertThrows(AccessDeniedException.class,()->update(loaded));
    }
    @Test void ordinaryReferenceNamedOwnerHasNoCapabilityAndCanChangeOrClear() {
        assertNull(descriptors.descriptorOf(OrdinaryOwnerReference.class).owner());
        var e=new OrdinaryOwnerReference();e.setName("Обычная ссылка");e.setOwner(a);e=data.create(OrdinaryOwnerReference.class,e);
        e.setOwner(b);e=data.update(OrdinaryOwnerReference.class,e);assertEquals(b.getId(),data.detail(OrdinaryOwnerReference.class,e.getId()).orElseThrow().getOwner().getId());
        e.setOwner(null);e=data.update(OrdinaryOwnerReference.class,e);assertNull(data.detail(OrdinaryOwnerReference.class,e.getId()).orElseThrow().getOwner());
        data.delete(OrdinaryOwnerReference.class,e.getId());assertTrue(data.detail(ReferenceTarget.class,a.getId()).isPresent());assertTrue(data.detail(ReferenceTarget.class,b.getId()).isPresent());
    }
}
