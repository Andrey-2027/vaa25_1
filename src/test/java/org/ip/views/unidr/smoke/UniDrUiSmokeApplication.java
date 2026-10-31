package org.ipro.unidr.ui.smoke;
import org.ip.unidr.model.UniDrDefinitions;
import org.ip.unidr.query.UniDrReferenceService;

import com.vaadin.flow.spring.annotation.EnableVaadin;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.ipro.unidr.model.*;
import org.ipro.unidr.model.UniDrDefinition.*;
import org.ipro.unidr.query.*;
import org.ipro.unidr.render.UniDrReportCompiler;
import org.ipro.unidr.run.*;
import org.ipro.unidr.source.*;
import org.ipro.report.data.*;
import org.ipro.query.ReportRunQuota;
import org.ipro.report.render.*;
import org.ipro.report.task.ReportTaskExecutor;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;

/** Loopback-only browser fixture. Real UniDrView/worker/DR/export, simulated source and choices. No DB. */
// Профиль-переключатель: fixture лежит в сканируемом приложением пакете, и без него его
// конфигурация попадала бы в каждый контекст @SpringBootTest — два fixture'а давали два
// бина security и роняли загрузку приложения в тестах. Профиль включает сам fixture в main().
@Profile(UniDrUiSmokeApplication.PROFILE)
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableVaadin("org.ipro.unidr.ui")
public class UniDrUiSmokeApplication {

    static final String PROFILE = "unidr-smoke";

    public static void main(String[] args) {
        var excluded=ImportCandidates.load(AutoConfiguration.class,UniDrUiSmokeApplication.class.getClassLoader()).getCandidates().stream()
            .filter(name->name.startsWith("org.ipro.") || name.contains("DataSource") || name.contains("HibernateJpa") || name.contains("JpaRepositories") || name.contains("UserDetailsServiceAutoConfiguration")).toList();
        var application=new SpringApplication(UniDrUiSmokeApplication.class);
        application.setAdditionalProfiles(PROFILE);
        application.setDefaultProperties(Map.of("server.address","127.0.0.1","server.port","8086","spring.config.location","optional:file:outputs/unidr-u4/smoke-empty.properties",
            "spring.autoconfigure.exclude",String.join(",",excluded),"app.unidr.enabled","true","vaadin.allowed-packages","org.ipro.unidr.ui","vaadin.launch-browser","false",
            "vaadin.node-auto-update","false","vaadin.require-home-node-exec","false"));
        application.run(args);
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize->authorize.anyRequest().permitAll()).csrf(csrf->csrf.disable())
            .headers(headers->headers.frameOptions(frame->frame.sameOrigin()))
            .addFilterBefore(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("unidr-smoke",null,List.of()));
                    try {chain.doFilter(request,response);} finally {SecurityContextHolder.clearContext();}
                }
            },UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Bean UniDrSourceCatalog catalog() { return UniDrDefinitions.catalog(references()); }
    @Bean UniDrDefinition initialDefinition() { return UniDrDefinitions.receivingItemsSummary(); }
    @Bean UniDrReferenceService references() {
        var references=mock(UniDrReferenceService.class);
        when(references.search(anyString(),anyString(),anyInt())).thenAnswer(call->{
            String field=call.getArgument(0),term=call.getArgument(1);int limit=call.getArgument(2);
            return choices(field).stream().filter(option->option.caption().toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))).limit(limit).toList();
        });
        when(references.selected(anyString(),anyLong())).thenAnswer(call->choices(call.getArgument(0)).stream().filter(option->option.id().equals(call.getArgument(1))).findFirst().orElseThrow());
        return references;
    }
    private static List<org.ipro.unidr.query.UniDrReferenceCatalog.Option> choices(String field) {
        return switch(field) {
            case "unit" -> List.of(new org.ipro.unidr.query.UniDrReferenceCatalog.Option(1L,"шт"));
            case "nomenclature" -> List.of(new org.ipro.unidr.query.UniDrReferenceCatalog.Option(11L,"Деталь X"),new org.ipro.unidr.query.UniDrReferenceCatalog.Option(12L,"Деталь Y"));
            case "document" -> List.of(new org.ipro.unidr.query.UniDrReferenceCatalog.Option(1L,"Накладная 1"));
            default -> List.of(new org.ipro.unidr.query.UniDrReferenceCatalog.Option(1L,"Цех A"),new org.ipro.unidr.query.UniDrReferenceCatalog.Option(2L,"Цех B"));
        };
    }
    @Bean(destroyMethod="close") ReportTaskExecutor worker() {return new ReportTaskExecutor(1,4);}
    @Bean UniDrRunService runs(ReportTaskExecutor worker) {
        var catalog=UniDrDefinitions.catalog();var validator=new UniDrDefinitionValidator(catalog);var resolver=new UniDrRunParameterResolver(validator);
        var source=catalog.find("receiving-items").orElseThrow();
        var query=mock(UniDrQueryService.class);
        when(query.read(any(),any(),any())).thenAnswer(call->{
            UniDrRunRequest request=call.getArgument(0);var values=resolver.resolve(request,call.getArgument(1));
            var fixture=fixture();List<ReportRow> rows=new ArrayList<>(List.of(fixture.rows()));
            for(var parameter:source.parameters()) if(values.containsKey(parameter.id())) rows.removeIf(row->!matches(row,source,parameter.fieldId(),parameter.operator(),values.get(parameter.id())));
            for(var filter:request.definition().filters()) {
                Literal value=filter.value() instanceof Literal literal?literal:values.get(((ParameterRef)filter.value()).parameterId());
                rows.removeIf(row->!matches(row,source,filter.fieldId(),filter.operator(),value));
            }
            Comparator<ReportRow> order=Comparator.comparing(row->(Long)row.value("rowId"));
            var groupIds=request.definition().groups();for(int i=groupIds.size()-1;i>=0;i--){String groupId=groupIds.get(i);String alias=source.fields().stream().filter(field->field.id().equals(groupId)).findFirst().orElseThrow().groupKeyAlias();var next=order;order=(a,b)->{int comparison=compare(a.value(alias),b.value(alias));return comparison==0?next.compare(a,b):comparison;};}
            rows.sort(order);return new UniDrQueryService.Data(new ReportDataset(fixture.fields(),rows.toArray(ReportRow[]::new)),values);
        });
        var access=new UniDrAccess(()->true,()->SecurityContextHolder.getContext().getAuthentication().getName());
        return new UniDrRunService(access,query,worker,new ReportRunQuota(2),Clock.systemUTC(),new UniDrReportCompiler(catalog,validator),new JasperReportExporter());
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static int compare(Object a,Object b) {return ((Comparable)a).compareTo(b);}
    private static boolean matches(ReportRow row,UniDrSourceDefinition source,String id,Operator operator,Literal literal) {
        var field=source.fields().stream().filter(candidate->candidate.id().equals(id)).findFirst().orElseThrow();Object actual=row.value(field.type()==ValueType.REFERENCE?field.groupKeyAlias():field.valueAlias());
        List<?> values=literal.values().stream().map(value->switch(field.type()){case DATE->LocalDate.parse(value);case DECIMAL->new BigDecimal(value);case REFERENCE->Long.valueOf(value);default->throw new IllegalArgumentException();}).toList();
        int c=compare(actual,values.getFirst());return switch(operator){case EQ->c==0;case NE->c!=0;case GT->c>0;case GE->c>=0;case LT->c<0;case LE->c<=0;case BETWEEN->c>=0 && compare(actual,values.getLast())<=0;};
    }
    private static ReportDataset fixture() {
        String[] names={"rowId","date","documentId","documentNumber","receivingWorkshopId","receivingWorkshopName","transferringWorkshopId","transferringWorkshopName","nomenclatureId","nomenclatureCode","nomenclatureName","unitId","unit","quantity"};
        Class<?>[] types={Long.class,LocalDate.class,Long.class,String.class,Long.class,String.class,Long.class,String.class,Long.class,String.class,String.class,Long.class,String.class,BigDecimal.class};
        QueryField[] fields=new QueryField[names.length];for(int i=0;i<fields.length;i++)fields[i]=QueryField.scalar(names[i],types[i]);
        String[] quantities={"2","4","9","5","10","6"};ReportRow[] rows=new ReportRow[6];
        for(int i=0;i<6;i++){long id=i+1;boolean second=i>=3,other=i==2 || i==5;rows[i]=new ReportRow(fields,new Object[]{id,LocalDate.of(2026,10,1),id,"Накладная "+id,second?2L:1L,second?"Цех B":"Цех A",1L,"Цех A",other?12L:11L,other?"Y":"X",other?"Деталь Y":"Деталь X",1L,"шт",new BigDecimal(quantities[i])});}
        return new ReportDataset(fields,rows);
    }
}
