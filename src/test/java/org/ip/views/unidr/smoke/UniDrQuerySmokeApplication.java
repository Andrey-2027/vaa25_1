package org.ip.views.unidr.smoke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.spring.annotation.EnableVaadin;
import jakarta.persistence.*;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.ip.config.AuditConfig;
import org.ip.model.*;
import org.ip.unidr.config.UniDrConfiguration;
import org.ip.views.unidr.UniDrQueryUiSupport;
import org.ipro.crud.*;
import org.ipro.data.*;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.*;
import org.ipro.reportstudio.param.*;
import org.ipro.reportstudio.query.*;
import org.ipro.reportstudio.query.editor.*;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.reportstudio.render.*;
import org.ipro.reportstudio.run.ReportTaskExecutor;
import org.ipro.rls.*;
import org.ipro.rls.c5.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.mockito.Mockito.*;

/** Disposable H2 + real UDR query/constructor/parameters and UniDR. Never connects to the working DB. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableVaadin("org.ip.views.unidr")
@EntityScan({"org.ip.model","org.ipro.rls"})
@EnableJpaRepositories("org.ipro.rls")
@EnableTransactionManagement
@Import({AuditConfig.class,UniDrConfiguration.class,UniDrQueryUiSupport.class})
public class UniDrQuerySmokeApplication {
    public static void main(String[] args) {
        var excluded=ImportCandidates.load(AutoConfiguration.class,UniDrQuerySmokeApplication.class.getClassLoader()).getCandidates().stream()
            .filter(name->name.startsWith("org.ipro.") || name.contains("UserDetailsServiceAutoConfiguration")).toList();
        var app=new SpringApplication(UniDrQuerySmokeApplication.class);
        app.setDefaultProperties(Map.ofEntries(Map.entry("server.address","127.0.0.1"),Map.entry("server.port","8086"),
            Map.entry("spring.config.location","optional:file:outputs/unidr-u4/smoke-empty.properties"),Map.entry("spring.autoconfigure.exclude",String.join(",",excluded)),
            Map.entry("spring.datasource.url","jdbc:h2:mem:unidr-query-smoke-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"),Map.entry("spring.datasource.username","sa"),
            Map.entry("spring.datasource.password",""),Map.entry("spring.jpa.hibernate.ddl-auto","create-drop"),Map.entry("spring.jpa.open-in-view","false"),Map.entry("spring.sql.init.mode","never"),
            Map.entry("app.unidr.enabled","true"),Map.entry("vaadin.allowed-packages","org.ip.views.unidr"),Map.entry("vaadin.launch-browser","false"),
            Map.entry("vaadin.node-auto-update","false"),Map.entry("vaadin.require-home-node-exec","false")));
        app.run(args);
    }
    @Bean SecurityFilterChain security(HttpSecurity http)throws Exception {return new UniDrUiSmokeApplication().security(http);}
    @Bean RlsCurrentUser currentUser(){return ()->SecurityContextHolder.getContext().getAuthentication().getName();}
    @Bean MetadataResolver metadata(){return new MetadataResolver();}
    @Bean RlsDimensionRegistry dimensions(){var registry=new RlsDimensionRegistry("org.ip");registry.rebuild();return registry;}
    @Bean AccessService access(AccessGrantRepository grants,RlsDimensionRegistry dimensions){return new AccessService(grants,user->List.of(),dimensions);}
    @Bean C5PermissionEvaluator permissions(AccessGrantRepository grants){return new DefaultC5PermissionEvaluator(grants,user->List.of());}
    @Bean RlsFilterActivator activator(RlsDimensionRegistry dimensions,AccessService access,RlsCurrentUser current){return new RlsFilterActivator(dimensions,new RlsReadableIdsCache(access),current);}
    @Bean RlsReadGate gate(C5PermissionEvaluator permissions,RlsDimensionRegistry dimensions){return new RlsReadGate(permissions,dimensions);}
    @Bean ReportReadAuthorizer authorizer(EntityManagerFactory factory,C5PermissionEvaluator permissions,RlsCurrentUser current,AccessService access,RlsDimensionRegistry dimensions) {
        return new ReportReadAuthorizer(factory,permissions,current,List.of(),access,dimensions);
    }
    @Bean ReportQueryGuard guard(EntityManagerFactory factory,AccessService access,RlsDimensionRegistry dimensions,RlsCurrentUser current,ReportReadAuthorizer authorizer) {
        return new ReportQueryGuard(new SqmQuerySemanticAnalyzer(factory),access,dimensions,current,factory,authorizer);
    }
    @Bean ReportQueryExecutor executor(EntityManager em,RlsFilterActivator activator,RlsCurrentUser current,ReportQueryGuard guard,ReportReadAuthorizer authorizer){return new ReportQueryExecutor(em,activator,current,guard,authorizer);}
    @Bean EntityParamRefresher refresher(EntityManager em,RlsFilterActivator activator,RlsReadGate gate,RlsCurrentUser current,EntityManagerFactory factory){return new EntityParamRefresher(em,activator,gate,current,factory.unwrap(SessionFactoryImplementor.class));}
    @Bean ReportParamResolver resolver(EntityParamRefresher refresher,AccessService access,RlsCurrentUser current,EntityManagerFactory factory){return new ReportParamResolver(refresher,access,current,factory.unwrap(SessionFactoryImplementor.class),new ObjectMapper(),"BRANCH");}
    @Bean QueryBuilderMetadataCatalog constructorCatalog(EntityManagerFactory factory,MetadataResolver metadata,RlsReadGate gate,RlsCurrentUser current){return new QueryBuilderMetadataCatalog(factory,metadata,gate,current);}
    @Bean ReportQueryAssemblyService assembly(ReportQueryGuard guard,ReportParamResolver resolver,EntityParamRefresher refresher,QueryBuilderMetadataCatalog catalog){return new ReportQueryAssemblyService(guard,resolver,refresher,catalog);}
    @Bean QueryEditorAnalysisService analysis(ReportQueryGuard guard){return new QueryEditorAnalysisService(guard);}
    @Bean QueryMetadataCatalogService queryMetadata(EntityManagerFactory factory,MetadataResolver metadata,RlsReadGate gate,RlsCurrentUser current){return new QueryMetadataCatalogService(factory,metadata,gate,current);}
    @Bean ReportPreviewService preview(ReportQueryExecutor executor){return new ReportPreviewService(executor);}
    @Bean ReportCompiler exporter(){return new JasperReportCompiler();}
    @Bean ReportRunQuota quota(){return new ReportRunQuota(2);}
    @Bean(destroyMethod="close") ReportTaskExecutor worker(){return new ReportTaskExecutor(1,4);}
    @Bean CanonicalReadExecutor canonical(EntityManager em,EntityManagerFactory factory,MetadataResolver metadata,RlsFilterActivator activator,RlsReadGate gate,RlsCurrentUser current,C5PermissionEvaluator permissions) {
        var sections=new SectionMetadataRegistry("org.ip.model",metadata);sections.afterPropertiesSet();
        var descriptors=new EntityDescriptorCatalog(new ManagedEntityCatalog(factory),sections,metadata,List.of(),List.of());
        var canonical=new CanonicalReadExecutor(descriptors,new ScenarioFetchGraphResolver(metadata,null,null),metadata,activator,gate,null,ReadTelemetry.noop(),null,current,permissions);
        org.springframework.test.util.ReflectionTestUtils.setField(canonical,"entityManager",em);return canonical;
    }
    @Bean EntityLookup lookup(CanonicalReadExecutor canonical){return new LookupService(canonical);}
    @SuppressWarnings({"rawtypes","unchecked"})
    @Bean SelectionFormAssembler selections(MetadataResolver metadata,CanonicalReadExecutor canonical,PlatformTransactionManager transactions) {
        // A local read-only service adapter lets the real platform selection form use the canonical page reader.
        var locator=mock(ServiceLocator.class);var reads=new TransactionTemplate(transactions);reads.setReadOnly(true);
        when(locator.findService(any())).thenAnswer(call->{
            Class type=call.getArgument(0);var service=mock(BaseService.class);
            when(service.findAllByScenario(any(),any(),any(),any())).thenAnswer(query->reads.execute(status->canonical.readPage(new PageRead(type,query.getArgument(0),query.getArgument(1),query.getArgument(2),query.getArgument(3)))));
            return service;
        });return new SelectionFormAssembler(metadata,locator);
    }
    @Bean ApplicationRunner fixtures(EntityManager em,PlatformTransactionManager transactions) {
        return args->{
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("unidr-smoke",null,List.of()));
            try {new TransactionTemplate(transactions).executeWithoutResult(status->{
                var branch=new Branch();branch.setCode("SMOKE");branch.setName("Пилот");em.persist(branch);
                var journal=new Journal();journal.setCode("SMOKE");journal.setName("Накладные пилота");em.persist(journal);
                var a=new Workshop("A","Цех A");a.setBranch(branch);em.persist(a);var b=new Workshop("B","Цех B");b.setBranch(branch);em.persist(b);
                var unit=new UnitOfMeasurement("шт","Штука","PCS");em.persist(unit);
                var x=new Nomenclature("X","Деталь X",unit);em.persist(x);var y=new Nomenclature("Y","Деталь Y",unit);em.persist(y);
                String[] quantities={"2","4","9","5","10","6"};
                for(int i=0;i<6;i++) {
                    var document=new ReceivingDocument("Пилот "+(i+1),LocalDate.of(2026,10,1),i<3?a:b,i<3?b:a);document.setJournal(journal);em.persist(document);
                    var row=new ReceivingDocumentItem(i==2 || i==5?y:x,new BigDecimal(quantities[i]));row.setDocument(document);row.setLineNumber(1);em.persist(row);
                }
                for(Class<?> type:List.of(ReceivingDocument.class,ReceivingDocumentItem.class,Workshop.class,Nomenclature.class,UnitOfMeasurement.class,Journal.class,Branch.class))
                    grant(em,C5PermissionKeys.entityRead(type),null);
                grant(em,"BRANCH",branch.getId());grant(em,"JOURNAL",journal.getId());grant(em,"ENTITY:ReceivingDocument",null);
            });} finally {SecurityContextHolder.clearContext();}
        };
    }
    private static void grant(EntityManager em,String dimension,Long id) {
        var grant=new AccessGrant();grant.setSubjectType(AccessGrant.SubjectType.USER);grant.setSubjectKey("unidr-smoke");
        grant.setDimension(dimension);grant.setDimensionValueId(id);grant.setCanRead(true);em.persist(grant);
    }
}
