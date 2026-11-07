package org.ip.config;

import java.util.List;
import org.ip.model.*;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.*;
import org.ipro.rls.c5.C5PermissionKeys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Explicit test-only fixture, installed solely in the isolated EM6 editor copy. */
@Component
@ConditionalOnProperty(name="acceptance.em6.prepare",havingValue="true")
public class AcceptanceEm6Fixture {
    private final AccessGrantRepository grants;
    private final EntityDataAccess data;
    private final JdbcTemplate jdbc;
    private final org.ipro.crud.MetadataDrivenAggregateSaveService aggregates;
    public AcceptanceEm6Fixture(AccessGrantRepository grants,EntityDataAccess data,JdbcTemplate jdbc,
            org.ipro.crud.MetadataDrivenAggregateSaveService aggregates) {
        this.grants=grants;this.data=data;this.jdbc=jdbc;this.aggregates=aggregates;
    }
    public void prepare() throws Exception {
        if(jdbc.queryForObject("select count(*) from information_schema.tables where table_name='ACCEPTANCE_EM6_FIXTURE'",Integer.class)>0) return;
        for(String user:List.of("admin","manager","viewer")) {
            boolean write=user.equals("admin");
            for(Class<?> type:List.of(Workshop.class,ReceivingDocument.class,ReceivingDocumentItem.class,Journal.class,Branch.class)) grant(user,C5PermissionKeys.entity(type),write);
            for(String scope:List.of("BRANCH","JOURNAL")) grant(user,scope,write);
        }
        var previous=SecurityContextHolder.getContext();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(context);
        try {
            var a=workshop("EM6-A","Контрольный цех A");
            var b=workshop("EM6-B","Контрольный цех B");
            var journal=data.list(Journal.class,null,org.springframework.data.domain.PageRequest.of(0,100)).stream().filter(j->"EM6".equals(j.getCode())).findFirst().orElse(null);
            if(journal==null) {journal=new Journal();journal.setCode("EM6");journal.setName("Журнал приёмки EM6");journal=data.create(Journal.class,journal);}
            var document=new ReceivingDocument("EM6-OLD",java.time.LocalDate.of(2026,11,3),a,b);document.setJournal(journal);
            document=aggregates.save(document,List.of(),org.ipro.events.EventSource.UI).aggregate();
            jdbc.execute("create table acceptance_em6_fixture (workshop_a bigint, workshop_b bigint, journal_id bigint, document_id bigint)");
            jdbc.update("insert into acceptance_em6_fixture values (?,?,?,?)",a.getId(),b.getId(),journal.getId(),document.getId());
            prepareDocumentRow();
            var proof=java.util.Map.of("workshopA",a.getId(),"workshopB",b.getId(),"journal",journal.getId(),"document",document.getId(),"workshopCode","EM6-A","documentNumber","EM6-OLD","origin","explicit consumer fixture; canonical creates; no product grant bypass");
            var root=java.nio.file.Path.of(System.getProperty("acceptance.evidence")).getParent();
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(root.resolve("em6-fixture.json").toFile(),proof);
        } finally {SecurityContextHolder.setContext(previous);}
    }
    private void grant(String user,String dimension,boolean write) {
        if(grants.findAll().stream().anyMatch(g->g.getSubjectType()==AccessGrant.SubjectType.USER && user.equals(g.getSubjectKey()) && dimension.equals(g.getDimension()) && g.getDimensionValueId()==null)) return;
        var g=new AccessGrant();g.setSubjectType(AccessGrant.SubjectType.USER);g.setSubjectKey(user);g.setDimension(dimension);
        g.setCanRead(true);g.setCanUpdate(write);g.setCanDelete(write);grants.saveAndFlush(g);
    }
    private Workshop workshop(String code,String name) {
        return data.list(Workshop.class,null,org.springframework.data.domain.PageRequest.of(0,100)).stream().filter(w->code.equals(w.getCode())).findFirst().orElseGet(()->data.create(Workshop.class,new Workshop(code,name)));
    }
    public void prepareDocumentRow() {
        grant("admin",C5PermissionKeys.entity(UnitOfMeasurement.class),true);
        grant("admin",C5PermissionKeys.entity(Nomenclature.class),true);
        Long id=jdbc.queryForObject("select document_id from acceptance_em6_fixture",Long.class);
        if(jdbc.queryForObject("select count(*) from receiving_document_item where document_id=?",Long.class,id)>0) return;
        var document=data.detail(ReceivingDocument.class,id).orElseThrow();
        var unit=data.list(UnitOfMeasurement.class,null,org.springframework.data.domain.PageRequest.of(0,100)).stream().filter(u->"EM6".equals(u.getCode())).findFirst().orElseGet(()->data.create(UnitOfMeasurement.class,new UnitOfMeasurement("EM6","Штука EM6","EM6")));
        var nomenclature=data.list(Nomenclature.class,null,org.springframework.data.domain.PageRequest.of(0,100)).stream().filter(n->"EM6-CONTROL".equals(n.getCode())).findFirst().orElseGet(()->aggregates.save(new Nomenclature("EM6-CONTROL","Контрольная позиция EM6",unit),List.of(),org.ipro.events.EventSource.UI).aggregate());
        var row=new ReceivingDocumentItem(nomenclature,java.math.BigDecimal.ONE);
        aggregates.save(document,List.of(org.ipro.crud.MetadataDrivenAggregateSaveService.SectionInput.attached(ReceivingDocumentItem.class,List.of(row))),org.ipro.events.EventSource.UI);
    }
}
