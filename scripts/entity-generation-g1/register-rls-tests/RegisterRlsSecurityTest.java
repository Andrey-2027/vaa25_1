package com.example.generated.harness;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.data.EntityDataAccess;
import org.ipro.register.*;
import org.ipro.rls.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;

/** Mandatory C/D security checks on the actual generated class and independently declared policy. */
@SpringBootTest(classes=GenerationHarnessApplication.class)
@SuppressWarnings({"rawtypes","unchecked"})
class RegisterRlsSecurityTest {
    static final LocalDateTime FIRST=LocalDateTime.of(2026,11,1,12,0), SECOND=FIRST.plusDays(1);
    @Autowired EntityDataAccess data;
    @Autowired RegisterKeyWrites writes;
    @Autowired RegisterReaderFactory readers;
    @Autowired RlsDimensionRegistry policies;
    @Autowired AccessGrantRepository grants;
    @Autowired EntityManagerFactory emf;
    Class type, referenceType;
    StandardCatalogEntity a,b;
    BaseEntity rowA,rowB;

    @BeforeEach void fixture() throws Exception {
        type=Class.forName(ScenarioSpec.CLASS_NAME);referenceType=Class.forName(ScenarioSpec.DIMENSION_TYPE);
        grants.deleteAll();login("seed");
        entity("seed",true);actions("seed");scope("seed",null,true);
        a=reference("A");b=reference("B");
        rowA=set(a,FIRST,BigDecimal.ONE);rowB=set(b,FIRST,BigDecimal.valueOf(2));
        if(ScenarioSpec.PERIODIC){set(a,SECOND,BigDecimal.TEN);set(b,SECOND,new BigDecimal("20"));}
        entity("writer",true);actions("writer");scope("writer",a.getId(),true);
        entity("generic-writer",true);scope("generic-writer",a.getId(),true);
        entity("reader",false);scope("reader",a.getId(),false);
        entity("wildcard",true);actions("wildcard");scope("wildcard",null,true);
        entity("no-scope",true);actions("no-scope");
        scope("denied",a.getId(),false);
    }
    @AfterEach void logout(){ConsumerGrants.logout();}
    static void login(String user){ConsumerGrants.login(user);}
    void entity(String user,boolean write){
        if(write){ConsumerGrants.entityWrite(grants,user,type);ConsumerGrants.entityWrite(grants,user,referenceType);}
        else {ConsumerGrants.entityRead(grants,user,type);ConsumerGrants.entityRead(grants,user,referenceType);}
    }
    void actions(String user){
        ConsumerGrants.action(grants,user,RegisterKeyWritePermissions.setPermissionId(type));
        ConsumerGrants.action(grants,user,RegisterKeyWritePermissions.deletePermissionId(type));
    }
    void scope(String user,Long id,boolean write){
        var g=new AccessGrant();g.setSubjectType(AccessGrant.SubjectType.USER);g.setSubjectKey(user);
        g.setDimension(ScenarioSpec.RLS_DIMENSION);g.setDimensionValueId(id);g.setCanRead(true);g.setCanUpdate(write);g.setCanDelete(write);
        grants.saveAndFlush(g);
    }
    StandardCatalogEntity reference(String suffix) throws Exception {
        var value=(StandardCatalogEntity)referenceType.getDeclaredConstructor().newInstance();
        value.setCode(suffix+UUID.randomUUID().toString().substring(0,8));value.setName("Scope "+suffix);
        return (StandardCatalogEntity)data.create(referenceType,value);
    }
    Map<String,Object> key(StandardCatalogEntity value){return Map.of(ScenarioSpec.DIMENSION,value.getId());}
    LocalDateTime period(LocalDateTime value){return ScenarioSpec.PERIODIC ? value : null;}
    BaseEntity set(StandardCatalogEntity value,LocalDateTime time,BigDecimal rate){
        return (BaseEntity)writes.set(type,key(value),Map.of("rate",rate),period(time)).row();
    }
    BigDecimal amount(Object row){
        try{return (BigDecimal)type.getMethod("getRate").invoke(row);}catch(Exception e){throw new IllegalStateException(e);}
    }
    void amount(Object row,BigDecimal value){
        try{type.getMethod("setRate",BigDecimal.class).invoke(row,value);}catch(Exception e){throw new IllegalStateException(e);}
    }
    List<BaseEntity> visible(){return (List)data.list(type,null,PageRequest.of(0,100)).getContent();}
    void assertOnlyScope(StandardCatalogEntity allowed){
        for(var row:visible()) try {
            var ref=(BaseEntity)type.getMethod("get"+Character.toUpperCase(ScenarioSpec.DIMENSION.charAt(0))+ScenarioSpec.DIMENSION.substring(1)).invoke(row);
            assertThat(ref.getId()).isEqualTo(allowed.getId());
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    void assertForeignUnchanged(){
        login("seed");var actual=(BaseEntity)data.detail(type,rowB.getId()).orElseThrow();
        assertThat(amount(actual)).isEqualByComparingTo(BigDecimal.valueOf(2));assertThat(actual.getVersion()).isEqualTo(rowB.getVersion());
        assertThat(actual.getModifiedAt()).isEqualTo(rowB.getModifiedAt());
    }

    @Test void actualMappingRegistersTheIndependentReadWritePolicy(){
        assertThat(policies.dimensionsOf(type)).containsExactly(ScenarioSpec.RLS_DIMENSION);
        var declared=(RlsDimension)type.getAnnotation(RlsDimension.class);
        assertThat(declared.valuePaths()).containsExactly(ScenarioSpec.RLS_VALUE_PATH);
        var mapping=(org.hibernate.annotations.FilterDef)type.getAnnotation(org.hibernate.annotations.FilterDef.class);
        assertThat(mapping.applyToLoadByKey()).isTrue();assertThat(mapping.parameters()[0].type()).isEqualTo(Long.class);
        var filter=(org.hibernate.annotations.Filter)type.getAnnotation(org.hibernate.annotations.Filter.class);
        assertThat(filter.condition()).isEqualTo(ScenarioSpec.DIMENSION_COLUMN+" in (:allowedIds)");
    }
    @Test void listDetailLookupAndCountHaveOneScope(){
        login("reader");var page=data.list(type,null,PageRequest.of(0,100));
        assertThat(page.getTotalElements()).isEqualTo(ScenarioSpec.PERIODIC ? 2 : 1);
        assertOnlyScope(a);assertThat(data.detail(type,rowA.getId())).isPresent();assertThat(data.detail(type,rowB.getId())).isEmpty();
        assertThat(data.lookup(type,"",100)).hasSize(ScenarioSpec.PERIODIC ? 2 : 1);
    }
    @Test void temporalReadsPreserveTheScopeAcrossVersionsAndKeys(){
        login("reader");
        if(!ScenarioSpec.PERIODIC){
            assertThatThrownBy(() -> readers.point(type)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> readers.slice(type)).isInstanceOf(IllegalArgumentException.class);return;
        }
        assertThat(readers.point(type).getLast(Map.of(ScenarioSpec.DIMENSION,a),SECOND.plusDays(1))).hasValueSatisfying(row -> assertThat(amount(row)).isEqualByComparingTo("10"));
        assertThat(readers.point(type).getLast(Map.of(ScenarioSpec.DIMENSION,b),SECOND.plusDays(1))).isEmpty();
        assertThat(readers.point(type).getLast(Map.of(ScenarioSpec.DIMENSION,a),FIRST.minusDays(1))).isEmpty();
        var slice=readers.slice(type);
        assertThat(slice.latest(SliceSpec.latest(type,SECOND.plusDays(1)),PageRequest.of(0,100)).getTotalElements()).isEqualTo(1);
        assertThat(slice.latestForKeys(SECOND.plusDays(1),List.of(key(a),key(b)))).hasSize(1);
    }
    @Test void namedSetDeleteCannotTouchTheForeignScope(){
        login("writer");
        assertThatThrownBy(() -> writes.set(type,key(b),Map.of("rate",BigDecimal.valueOf(99)),period(FIRST))).isInstanceOf(AccessDeniedException.class);
        try {assertThat(writes.delete(type,key(b),period(FIRST),rowB.getVersion()).verdict()).isEqualTo(RegisterKeyWrites.Verdict.ABSENT);}
        catch(AccessDeniedException expected){ /* Same denial without disclosing whether the row exists. */ }
        assertForeignUnchanged();
    }
    @Test void genericCrudUsesScopeIndependentlyOfNamedGrants(){
        login("generic-writer");var own=(BaseEntity)data.detail(type,rowA.getId()).orElseThrow();
        amount(own,new BigDecimal("7"));data.update(type,own);
        assertThat(amount(data.detail(type,rowA.getId()).orElseThrow())).isEqualByComparingTo("7");
        assertThatThrownBy(() -> writes.set(type,key(a),Map.of("rate",BigDecimal.valueOf(8)),period(FIRST))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> writes.delete(type,key(a),period(FIRST),own.getVersion())).isInstanceOf(AccessDeniedException.class);
        amount(rowB,new BigDecimal("99"));
        assertThatThrownBy(() -> data.update(type,rowB)).isInstanceOf(org.ipro.data.CanonicalWriteDeniedException.class)
            .satisfies(error -> assertThat(((org.ipro.data.CanonicalWriteDeniedException)error).kind()).isEqualTo(org.ipro.data.WriteTelemetry.DenialKind.ACCESS));
        // Canonical DELETE deliberately treats an RLS-hidden id as absent; verify the durable row below.
        data.delete(type,rowB.getId());
        data.delete(type,rowA.getId());assertThat(data.detail(type,rowA.getId())).isEmpty();assertForeignUnchanged();
    }
    @Test void readerScopeDoesNotGrantWritesAndKnownIdsDoNotGrantEntityRead(){
        login("reader");var own=(BaseEntity)data.detail(type,rowA.getId()).orElseThrow();amount(own,BigDecimal.valueOf(99));
        assertThatThrownBy(() -> data.update(type,own)).isInstanceOf(AccessDeniedException.class);
        login("denied");assertThatThrownBy(() -> data.detail(type,rowA.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> data.lookup(type,"",10)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void genericCreateRequiresTheReferenceValueToBeInTheWriteScope() throws Exception {
        login("seed");var allowed=reference("C");scope("writer",allowed.getId(),true);
        login("writer");var foreign=(BaseEntity)type.getDeclaredConstructor().newInstance();
        var field=type.getDeclaredField(ScenarioSpec.DIMENSION);field.setAccessible(true);field.set(foreign,b);amount(foreign,BigDecimal.TEN);
        if(ScenarioSpec.PERIODIC)((PeriodicRegisterEntryBase)foreign).setPeriod(SECOND.plusDays(1));
        assertThatThrownBy(() -> data.create(type,foreign)).isInstanceOf(AccessDeniedException.class);
        var own=(BaseEntity)type.getDeclaredConstructor().newInstance();field.set(own,allowed);amount(own,BigDecimal.ONE);
        if(ScenarioSpec.PERIODIC)((PeriodicRegisterEntryBase)own).setPeriod(SECOND.plusDays(1));
        var saved=(BaseEntity)data.create(type,own);assertThat(data.detail(type,saved.getId())).isPresent();assertForeignUnchanged();
    }
    @Test void wildcardMissingScopesAndGrantChangesAreObservedInOneThread(){
        login("wildcard");assertThat(visible()).extracting(BaseEntity::getId).contains(rowA.getId(),rowB.getId());
        login("no-scope");assertThat(visible()).isEmpty();
        assertThatThrownBy(() -> writes.set(type,key(a),Map.of("rate",BigDecimal.TEN),period(FIRST))).isInstanceOf(AccessDeniedException.class);
        login("reader");assertOnlyScope(a);
        ConsumerGrants.revokeAll(grants,"reader");entity("reader",false);scope("reader",b.getId(),false);
        assertThat(data.detail(type,rowA.getId())).isEmpty();assertThat(data.detail(type,rowB.getId())).isPresent();assertOnlyScope(b);
    }
    @Test void managedFlushCannotPersistForeignMutation() throws Exception {
        login("writer");
        try(EntityManager em=emf.createEntityManager()){
            var tx=em.getTransaction();tx.begin();
            try{
                Object foreign=em.find(type,rowB.getId());assertThat(foreign).isNotNull();amount(foreign,new BigDecimal("999"));
                assertThatThrownBy(em::flush).isInstanceOf(RuntimeException.class);
            }finally{if(tx.isActive())tx.rollback();}
        }
        assertForeignUnchanged();
    }
}
