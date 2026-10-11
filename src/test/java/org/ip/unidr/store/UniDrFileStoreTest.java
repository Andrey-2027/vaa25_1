package org.ip.unidr.store;

import org.ip.unidr.model.*;
import org.ip.unidr.run.*;
import org.ip.unidr.source.*;
import org.ipro.ureport.catalog.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UniDrFileStoreTest {
    @TempDir Path directory;
    final AtomicBoolean enabled=new AtomicBoolean(true);
    final UniDrAccess access=new UniDrAccess(enabled::get,()->SecurityContextHolder.getContext().getAuthentication().getName());
    final UniDrDefinitionJsonCodec codec=new UniDrDefinitionJsonCodec(new UniDrDefinitionValidator(UniDrSourceCatalog.pilot()));
    final UniDrDefinition base=UniDrDefinitions.receivingItemsSummary();
    UniDrFileStore store;
    @BeforeEach void setup() {login("alice");store=new UniDrFileStore(directory,codec,access);}
    @AfterEach void cleanup() {SecurityContextHolder.clearContext();}
    static void login(String user) {SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));}

    @Test void completeDefinitionSurvivesNewStoreInstanceAndUsesUtf8() throws Exception {
        var recipe=base.withComputedColumns(List.of(new UniDrDefinition.ComputedColumn("calc_total","Стоимость",UniDrDefinition.Computation.FORMULA,"{quantity} * 10")))
            .withFieldStyles(Map.of("quantity",new UniDrDefinition.FieldStyle(140,UniDrDefinition.Alignment.RIGHT,"#,##0.000",false))).withRowNumbers(true);
        var saved=store.create(recipe,"Описание по-русски");
        var reopened=new UniDrFileStore(directory,codec,access).load(saved.id());
        assertThat(reopened).isEqualTo(saved);assertThat(reopened.definition()).isEqualTo(recipe);
        String json=Files.readString(directory.resolve(saved.id()+".json"));
        assertThat(json).contains("Описание по-русски","Стоимость","\"fileVersion\"","\"definition\"","\"author\" : \"alice\"")
            .doesNotContain("dataset","completedAt","currentValues");
        assertThat(store.list("РУССКИ")).extracting(UniDrFileStore.Entry::id).containsExactly(saved.id());
    }
    @Test void queryAndTypedParameterDefinitionsSurviveFileRoundTrip() {
        var source = new UniDrQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.MANUAL,
                "select p.name as name, p.quantity as qty from Product p where p.name <> :excluded", null,
                List.of(new UniDrQuerySource.Param("excluded", "Исключить", org.ipro.reportstudio.dom.ReportParamKind.SCALAR,
                        null, org.ipro.reportstudio.dom.ReportParamSource.FORM, true, true, null, null, 0)));
        var metadata = org.ip.unidr.query.UniDrQuerySourceService.metadata(List.of(
                org.ipro.reportstudio.data.QueryField.scalar("name", String.class),
                org.ipro.reportstudio.data.QueryField.scalar("qty", java.math.BigDecimal.class)));
        var queryCodec = new UniDrDefinitionJsonCodec(new UniDrDefinitionValidator(new UniDrSourceCatalog(List.of(), ignored -> metadata)));
        var queryStore = new UniDrFileStore(directory, queryCodec, access);
        var recipe = new UniDrDefinition(1,"query",1,"По запросу",
                List.of(new UniDrDefinition.Column("name",null),new UniDrDefinition.Column("qty",null)),List.of(),List.of(),
                List.of(),List.of(),List.of(),UniDrDefinition.Mode.DETAILS,false).withQuery(source);
        var saved = queryStore.create(recipe, "Произвольный источник");
        assertThat(new UniDrFileStore(directory,queryCodec,access).load(saved.id()).definition()).isEqualTo(recipe);
    }
    @Test void updatingKeepsIdentityAndCopyGetsSeparateFile() throws Exception {
        var original=store.create(base,"Первый");
        var updated=store.update(original.id(),original.revision(),base.withTitle("Обновлённый"),"Второй");
        assertThat(updated.id()).isEqualTo(original.id());assertThat(updated.revision()).isNotEqualTo(original.revision());
        var copy=store.create(updated.definition().withTitle("Копия"),updated.description());
        assertThat(copy.id()).isNotEqualTo(updated.id());assertThat(store.load(original.id())).isEqualTo(updated);
        assertThat(store.list(null)).extracting(UniDrFileStore.Entry::name).containsExactly("Копия","Обновлённый");
        try(var files=Files.list(directory)){assertThat(files.filter(path->path.toString().endsWith(".tmp")).toList()).isEmpty();}
    }
    @Test void staleRevisionNeverOverwritesNewerSettings() {
        var first=store.create(base,"");
        var second=new UniDrFileStore(directory,codec,access).update(first.id(),first.revision(),base.withTitle("Из второй вкладки"),"");
        assertThatThrownBy(()->store.update(first.id(),first.revision(),base.withTitle("Старая вкладка"),""))
            .isInstanceOf(UniDrFileStore.StoreException.class).hasMessageContaining("изменён");
        assertThat(store.load(first.id())).isEqualTo(second);
    }
    @Test void manualFileEditAlsoCausesAConflict() throws Exception {
        var first=store.create(base,"До изменения");Path file=directory.resolve(first.id()+".json");
        Files.writeString(file,Files.readString(file).replace("До изменения","После изменения"));
        assertThatThrownBy(()->store.update(first.id(),first.revision(),base,""))
            .isInstanceOf(UniDrFileStore.StoreException.class).hasMessageContaining("изменён");
        assertThat(store.load(first.id()).description()).isEqualTo("После изменения");
    }
    @Test void otherUserCanReadSharedReportAndSaveACopyButCannotOverwriteIt() {
        var saved=store.create(base,"");login("bob");
        assertThat(store.list(null)).hasSize(1);assertThat(store.load(saved.id()).author()).isEqualTo("alice");
        assertThatThrownBy(()->store.update(saved.id(),saved.revision(),base,""))
            .isInstanceOf(UniDrFileStore.StoreException.class).hasMessageContaining("автор");
        assertThat(store.create(base.withTitle("Копия Боба"),"").author()).isEqualTo("bob");
    }
    @Test void duplicateNamesAreRejectedWithoutReplacingFiles() {
        var first=store.create(base.withTitle("Мой отчёт"),"");
        assertThatThrownBy(()->store.create(base.withTitle(" МОЙ ОТЧЁТ "),""))
            .isInstanceOf(UniDrFileStore.StoreException.class).hasMessageContaining("названием");
        assertThat(store.load(first.id())).isEqualTo(first);assertThat(store.list(null)).hasSize(1);
    }
    @Test void invalidSettingsCannotDamageAnExistingFile() {
        var saved=store.create(base,"");
        assertThatThrownBy(()->store.update(saved.id(),saved.revision(),base.withTitle(""),""))
            .isInstanceOf(UniDrValidationException.class);
        assertThat(store.load(saved.id())).isEqualTo(saved);
    }
    @Test void corruptFileDoesNotHideOtherReportsOrGetSilentlyRepaired() throws Exception {
        var good=store.create(base,"");Files.writeString(directory.resolve("123.json"),"{broken");
        assertThat(store.list(null)).extracting(UniDrFileStore.Entry::id).containsExactly(good.id());
        assertThatThrownBy(()->store.load(123)).isInstanceOf(UniDrFileStore.StoreException.class);
        assertThat(Files.readString(directory.resolve("123.json"))).isEqualTo("{broken");
    }
    @Test void wrongVersionsUnknownFieldsAndMismatchedIdsAreRejected() throws Exception {
        var saved=store.create(base,"");var file=directory.resolve(saved.id()+".json");String original=Files.readString(file);
        for(String invalid:List.of(original.replace("\"fileVersion\" : 1","\"fileVersion\" : 2"),
            original.replace("\"fileVersion\" : 1","\"fileVersion\" : 4294967297"),
            original.replace("\"author\" :","\"unknown\" :"),original.replace("\"id\" : "+saved.id(),"\"id\" : 42"))) {
            Files.writeString(file,invalid);assertThatThrownBy(()->store.load(saved.id())).isInstanceOf(UniDrFileStore.StoreException.class);
        }
    }
    @Test void oversizedAndMissingFilesFailSafely() throws Exception {
        Files.writeString(directory.resolve("123.json")," ".repeat(600_001));
        assertThatThrownBy(()->store.load(123)).isInstanceOf(UniDrFileStore.StoreException.class).hasMessageContaining("размер");
        assertThatThrownBy(()->store.load(456)).isInstanceOf(UniDrFileStore.StoreException.class).hasMessageContaining("не найден");
        assertThatThrownBy(()->store.load(-1)).isInstanceOf(UniDrFileStore.StoreException.class).hasMessageContaining("идентификатор");
    }
    @Test void anonymousDisabledAndBypassedCallsCannotReadOrWrite() {
        var saved=store.create(base,"");enabled.set(false);
        assertThatThrownBy(()->store.load(saved.id())).isInstanceOf(UniDrRunException.class);
        assertThatThrownBy(()->store.create(base,"")).isInstanceOf(UniDrRunException.class);
        assertThat(new UniDrCatalogContributor(store).findAll(null,true)).isEmpty();
        enabled.set(true);SecurityContextHolder.clearContext();
        assertThatThrownBy(()->store.list(null)).isInstanceOf(UniDrRunException.class);
        login("alice");try(var bypass=mockStatic(org.ipro.rls.RlsContext.class)) {
            bypass.when(org.ipro.rls.RlsContext::isBypassed).thenReturn(true);
            assertThatThrownBy(()->store.load(saved.id())).isInstanceOf(UniDrRunException.class);
        }
    }
    @Test void concurrentStoreInstancesAllowOnlyOneUpdate() throws Exception {
        var saved=store.create(base,"");var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> writes=new ArrayList<>();
            for(String name:List.of("Один","Два"))writes.add(executor.submit(()->{
                login("alice");start.await();
                try {new UniDrFileStore(directory,codec,access).update(saved.id(),saved.revision(),base.withTitle(name),"");return true;}
                catch(UniDrFileStore.StoreException conflict) {assertThat(conflict).hasMessageContaining("изменён");return false;}
                finally {SecurityContextHolder.clearContext();}
            }));
            start.countDown();assertThat(List.of(writes.get(0).get(),writes.get(1).get())).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test void commonCatalogMergesFileReportsWithoutConfusingEngineIds() {
        var saved=store.create(base,"");var udr=mock(org.ipro.reportstudio.service.ReportTemplateService.class);
        var ureport=mock(org.ipro.ureport.service.UreportTemplateService.class);
        when(udr.search(any())).thenReturn(List.of());when(ureport.search(any())).thenReturn(List.of());
        var catalog=new ReportCatalogService(udr,ureport,null,mock(org.ipro.rls.RlsReadGate.class),()->"alice",List.of(new UniDrCatalogContributor(store)));
        assertThat(catalog.findAll("цехам",true)).singleElement().satisfies(item->{
            assertThat(item.type()).isEqualTo(ReportEngineType.UNIDR);assertThat(item.id()).isEqualTo(saved.id());
        });
        assertThat(catalog.find(ReportEngineType.UNIDR,saved.id()).name()).isEqualTo(base.title());
    }
}
