package org.ip.application.register;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ip.model.UnitOfMeasurement;
import org.ipro.filtergrid.filter.*;
import org.ipro.metadata.MetadataResolver;
import org.ipro.reportstudio.query.*;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.rls.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Р2: production mapping, настоящий каталог и SQM/guard; не приёмка безопасности Р3. */
@SpringBootTest(classes = Application.class)
@Transactional
class NomenclaturePriceCteSpikeTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    @MockitoBean DataInitializer initializer;
    @Autowired EntityManager em;
    @Autowired AccessGrantRepository grants;
    @Autowired org.ipro.data.EntityDataAccess writes;
    @Autowired AccountingObjectSaveService commands;
    @Autowired org.ipro.crud.MetadataDrivenAggregateSaveService aggregateSave;
    @Autowired EntityManagerFactory emf;
    @Autowired MetadataResolver metadata;
    @Autowired RlsReadGate readGate;
    @Autowired AccessService access;
    @Autowired RlsDimensionRegistry dimensions;
    @Autowired NomenclaturePriceService pointReader;
    @Autowired org.ipro.register.RegisterRegistry registers;
    @Autowired org.ipro.reportstudio.query.register.RegisterSliceCteGenerator sliceGenerator;
    @Autowired org.ipro.reportstudio.query.register.RegisterSliceReportTemplates sliceRecipes;
    @Autowired NomenclaturePriceReport priceReport;
    @Autowired org.ipro.reportstudio.service.ReportTemplateService templates;
    @Autowired org.ipro.reportstudio.transfer.ReportTemplateTransferService transfer;
    @Autowired ReportQueryAssemblyService assembly;
    @Autowired ReportQueryExecutor executor;
    @Autowired org.ipro.reportstudio.run.ReportExecutionService reportExecution;
    @Autowired org.ipro.reportstudio.render.ReportCompiler renderer;
    private QueryBuilderMetadataCatalog catalog;
    private ReportQueryGuard guard;
    private String username;
    private UnitOfMeasurement unit;

    @BeforeEach
    void setUp() {
        username = "ct" + UUID.randomUUID().toString().substring(0, 8);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "unused", List.of()));
        for (Class<?> type : List.of(NomenclaturePrice.class, Nomenclature.class, UnitOfMeasurement.class)) {
            var grant = new AccessGrant();
            grant.setSubjectType(AccessGrant.SubjectType.USER);
            grant.setSubjectKey(username);
            grant.setDimension("ENTITY:" + type.getName());
            grant.setCanRead(true);
            em.persist(grant);
        }
        unit = new UnitOfMeasurement(username, "Штука", "ШТ");
        em.persist(unit);
        em.flush();
        catalog = new QueryBuilderMetadataCatalog(emf, metadata, readGate, () -> username);
        guard = new ReportQueryGuard(new SqmQuerySemanticAnalyzer(emf), access, dimensions, () -> username, emf);
    }

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test void r8AccountingObjectReportRoundTripsParametersAndPresentations() throws Exception {
        username = RlsTestFixture.authenticateAsSuperuser(grants);
        RlsTestFixture.grantC5EntityRead(grants, username, org.ip.model.Workshop.class,
            Nomenclature.class, UnitOfMeasurement.class, org.ip.model.AccountingType.class,
            org.ip.model.AccountingObjectInstallation.class, org.ip.model.NomenclatureAccountingObject.class);
        catalog = new QueryBuilderMetadataCatalog(emf, metadata, readGate, () -> username);
        var workshop = writes.create(org.ip.model.Workshop.class, new org.ip.model.Workshop("w" + unit.getCode(), "Цех R8"));
        var item = aggregateSave.save(new Nomenclature("n" + unit.getCode(), "Изделие R8", unit), List.of(), org.ipro.events.EventSource.SYSTEM).aggregate();
        var accounting = writes.create(org.ip.model.AccountingType.class, new org.ip.model.AccountingType("a" + unit.getCode(), "Тип R8"));
        var document = new org.ip.model.AccountingObjectInstallation(); document.setNumber(unit.getShortCode()); document.setDate(START); document.setWorkshop(workshop);
        var line = new org.ip.model.AccountingObjectInstallationItem(); line.setNomenclature(item); line.setAccountingType(accounting);
        document = commands.save(document, List.of(org.ipro.crud.MetadataDrivenAggregateSaveService.SectionInput.attached(
            org.ip.model.AccountingObjectInstallationItem.class, List.of(line))), org.ipro.events.EventSource.SYSTEM).aggregate();
        String documentNumber = document.getNumber();
        String unitCaption = unit.getDisplayName();
        em.flush();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sliceRecipes.constructorSlice(
            org.ipro.register.SliceSpec.latest(org.ip.model.NomenclatureAccountingObject.class, START), "date", Set.of(), List.of(
                new org.ipro.reportstudio.query.register.SliceParameterFilter("workshop", org.ipro.register.SliceSpec.Operator.EQ, "same", false),
                new org.ipro.reportstudio.query.register.SliceParameterFilter("nomenclature", org.ipro.register.SliceSpec.Operator.EQ, "same", false))))
            .hasMessageContaining("Несовместимые типы параметра");
        var language = new ReportQueryLanguage(catalog,sliceRecipes);
        var parsed = language.parse("select s.nomenclature as nomenclature, Представление(s.recorder) as recorder, Представление(s.accountingType) as accountingType from СрезПоследних(NomenclatureAccountingObject, :parDate, workshop = :parWorkshop) s");
        var draft = new org.ipro.reportstudio.dom.ReportTemplate(); draft.setName(unit.getCode()+" R8"); draft.setQuerySource(org.ipro.reportstudio.dom.ReportQuerySource.VISUAL); draft.setJpql(parsed.compiled().jpql()); draft.setVisualQueryJson(parsed.json());
        var date = new org.ipro.reportstudio.dom.ReportParam(); date.setName("parDate"); date.setRequired(true); date.setShowOnForm(true); draft.addParam(date);
        var place = new org.ipro.reportstudio.dom.ReportParam(); place.setName("parWorkshop"); place.setKind(org.ipro.reportstudio.dom.ReportParamKind.ENTITY); place.setEntityClass(org.ip.model.Workshop.class.getName()); place.setRequired(true); place.setShowOnForm(true); place.setPosition(1); draft.addParam(place);
        var band = new org.ipro.reportstudio.dom.ReportBand(); for (String name:List.of("recorder","accountingType")) { var field=new org.ipro.reportstudio.dom.ReportField(); field.setQueryField(name); field.setCaption(name); band.addField(field); } draft.addBand(band);
        var saved=templates.saveTemplate(draft); Long id=saved.getId(); Long workshopId=workshop.getId(); em.flush(); em.clear();
        var reopened=templates.loadTemplate(id); var imported=transfer.importTemplate(transfer.exportTemplate(reopened));
        assertThat(imported.getVisualQueryJson()).isEqualTo(reopened.getVisualQueryJson());
        for (var template:List.of(reopened,imported)) {
            var text=language.format(template).orElseThrow(); var parsedAgain=language.parse(text);
            assertThat(parsedAgain.compiled().jpql()).isEqualTo(parsed.compiled().jpql());
            var constructor=new org.ipro.reportstudio.query.constructor.JpqlQueryConstructor(catalog,List.of()); constructor.setPackage(parsedAgain.pack()); constructor.setSliceSupport(sliceRecipes, parsedAgain.json(), (request, applied) -> { }); assertThat(constructor.compileError()).isNull();
            assertThat(VisualQueryCompiler.compile(constructor.queryPackage(), catalog).jpql()).isEqualTo(parsed.compiled().jpql());
            var values=Map.<String,Object>of("parDate",START,"parWorkshop",em.getReference(org.ip.model.Workshop.class,workshopId));
            var compiled=assembly.assemble(template,org.ipro.reportstudio.param.ReportContext.empty(username),values);
            assertThat(compiled.bindings()).containsKey("parWorkshop").containsEntry("sliceId_parWorkshop",workshopId);
            var dataset=executor.execute(compiled,100,30000); assertThat(dataset.rows()).singleElement().satisfies(result -> { assertThat(result.value("recorder").toString()).contains(documentNumber); assertThat(result.value("accountingType")).isEqualTo("Тип R8"); });
            var preview=assembly.assembleForPreview(template.getJpql(),compiled.bindings().keySet(),compiled.bindings(),template);
            assertThat(executor.execute(preview,100,30000).rows()[0].value("recorder")).isEqualTo(dataset.rows()[0].value("recorder"));
            var run = reportExecution.run(template, org.ipro.reportstudio.param.ReportContext.empty(username), values, "ru", "Europe/Moscow");
            var bytes = reportExecution.export(run, org.ipro.reportstudio.render.ReportExportFormat.PDF);
            try (var pdf=org.apache.pdfbox.Loader.loadPDF(bytes)) { assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf)).contains(documentNumber,"Тип R8"); }
        }
        var outside=language.parse("select Представление(n.unitOfMeasurement) as unit from Nomenclature n where n.code = '"+item.getCode()+"'");
        assertThat(executor.execute(outside.compiled(),100,30000).rows()).singleElement().satisfies(result -> assertThat(result.value("unit").toString()).isEqualTo(unitCaption));
    }

    @Test void accountingObjectSliceUsesItsReferenceResourceAndOrdinarySavedPackage() {
        var gate = org.mockito.Mockito.mock(RlsReadGate.class);
        org.mockito.Mockito.when(gate.canRead(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        var allCatalog = new QueryBuilderMetadataCatalog(emf, metadata, gate, () -> username);
        var recipe = new org.ipro.reportstudio.query.register.RegisterSliceReportTemplates(emf, registers, allCatalog, sliceGenerator);
        var fields = recipe.sliceFilters(org.ip.model.NomenclatureAccountingObject.class, true);
        assertThat(fields).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("accountingType");
            assertThat(field.javaType()).isEqualTo(Long.class);
            assertThat(field.referenceType()).isEqualTo(org.ip.model.AccountingType.class);
        });
        var query = recipe.createSliceQuery(org.ipro.register.SliceSpec.latest(org.ip.model.NomenclatureAccountingObject.class, START), "asOf");
        var pack = new VisualQueryPackageJsonCodec().read(query.getVisualQueryJson());
        assertThat(pack.ctes()).hasSize(5);
        assertThat(pack.ctes().get(pack.ctes().size() - 2).definition().joins()).anyMatch(join -> "AccountingType".equals(join.sourcePath()));
        var constructor = new org.ipro.reportstudio.query.constructor.JpqlQueryConstructor(allCatalog, List.of());
        constructor.setPackage(pack);
        assertThat(constructor.queryPackage()).isEqualTo(pack);
        assertThat(constructor.compileError()).isNull();
    }

    @Test void editorSliceSavesReopensAndExecutesItsDateAndBothFilterPhases() throws Exception {
        var first = item(0); var other = item(1);
        price(first, START, "150"); price(first, START.plusDays(1), "90"); price(other, START, "200");
        var spec = new org.ipro.register.SliceSpec(NomenclaturePrice.class, org.ipro.register.SliceSpec.Mode.LATEST, START,
            List.of(new org.ipro.register.SliceSpec.Condition("nomenclature", org.ipro.register.SliceSpec.Operator.EQ, first.getId())),
            List.of(new org.ipro.register.SliceSpec.Condition("price", org.ipro.register.SliceSpec.Operator.GT, new BigDecimal("100"))));
        var query = sliceRecipes.createSliceQuery(spec, "asOf"); query.setName(username + " editor slice");
        var band = new org.ipro.reportstudio.dom.ReportBand();
        var field = new org.ipro.reportstudio.dom.ReportField(); field.setQueryField("price"); field.setCaption("Цена"); band.addField(field); query.addBand(band);
        var saved = templates.saveTemplate(query); Long id = saved.getId();
        em.flush(); em.clear();
        var reopened = templates.loadTemplate(id);
        var imported = transfer.importTemplate(transfer.exportTemplate(reopened));
        assertThat(imported.getVisualQueryJson()).isEqualTo(reopened.getVisualQueryJson());
        for (var template : List.of(reopened, imported)) {
            for (int offset = -1; offset <= 1; offset++) {
                var compiled = assembly.assemble(template, org.ipro.reportstudio.param.ReportContext.empty(username),
                    Map.of("asOf", START.plusDays(offset)));
                var dataset = executor.execute(compiled.jpql(), compiled.bindings(), compiled.fields(), 100, 30000);
                assertThat(dataset.rowCount()).isEqualTo(offset == 0 ? 1 : 0);
                if (offset == 0) assertThat((BigDecimal) dataset.rows()[0].value("price")).isEqualByComparingTo("150");
            }
        }
    }

    @Test
    void savedPriceReportReopensAndImportsWithRuntimeDateAndPointOracleParity() throws Exception {
        var first = item(0); var second = item(1); item(2);
        price(first, START, "150"); price(first, START.plusDays(1), "90");
        price(second, START.plusDays(1), "0");
        var draft = priceReport.createDraft();
        draft.setName(username + " prices");
        var saved = templates.saveTemplate(draft);
        Long templateId = saved.getId();
        em.flush(); em.clear();
        var reopened = templates.loadTemplate(templateId);
        var imported = transfer.importTemplate(transfer.exportTemplate(reopened));
        assertThat(imported.getId()).isNotEqualTo(templateId);
        assertThat(imported.getVisualQueryJson()).isEqualTo(reopened.getVisualQueryJson());
        assertThat(imported.getParams()).singleElement().satisfies(param -> {
            assertThat(param.getName()).isEqualTo(NomenclaturePriceReport.DATE_PARAMETER);
            assertThat(param.isRequired()).isTrue(); assertThat(param.isShowOnForm()).isTrue();
        });
        String original = reopened.getVisualQueryJson();
        for (var template : List.of(reopened, imported)) {
            for (int offset = -1; offset <= 2; offset++) {
                LocalDateTime date = START.plusDays(offset);
                var compiled = assembly.assemble(template, org.ipro.reportstudio.param.ReportContext.empty(username),
                    Map.of(NomenclaturePriceReport.DATE_PARAMETER, date));
                assertThat(compiled.bindings()).containsEntry(NomenclaturePriceReport.DATE_PARAMETER, date);
                var dataset = executor.execute(compiled.jpql(), compiled.bindings(), compiled.fields(), 100, 30000);
                var rows = Arrays.stream(dataset.rows()).filter(row -> ((String) row.value("code")).startsWith(username)).toList();
                assertThat(rows).hasSize(3);
                assertThat(rows).extracting(row -> row.value("code")).isSortedAccordingTo((a,b) -> ((String)a).compareTo((String)b));
                for (var row : rows) {
                    long id = ((Number) row.value("itemId")).longValue();
                    var expected = id == first.getId() && offset >= 0
                        ? offset == 0 ? new BigDecimal("150") : new BigDecimal("90")
                        : id == second.getId() && offset >= 1 ? BigDecimal.ZERO : null;
                    var point = pointReader.getLast(em.getReference(Nomenclature.class, id), date);
                    assertThat(point.isPresent()).isEqualTo(expected != null);
                    if (expected == null) {
                        assertThat(row.value("price")).isNull(); assertThat(row.value("slicePeriod")).isNull();
                    } else {
                        assertThat((BigDecimal) row.value("price")).isEqualByComparingTo(expected);
                        assertThat(point.orElseThrow().getPrice()).isEqualByComparingTo(expected);
                        assertThat(row.value("slicePeriod")).isEqualTo(point.orElseThrow().getPeriod());
                    }
                }
            }
        }
        assertThat(reopened.getVisualQueryJson()).isEqualTo(original);
        var rendered = reportExecution.run(reopened, org.ipro.reportstudio.param.ReportContext.empty(username),
            Map.of("priceDate", START.plusDays(1)), "ru", "Europe/Moscow");
        assertThat(rendered.print().getPages()).isNotEmpty();
        var pdf = renderer.export(rendered.print(), org.ipro.reportstudio.render.ReportExportFormat.PDF);
        try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertThat(text).contains(first.getCode(), second.getCode(), "Дата записи", "Цена", "02.01.2026")
                .doesNotContain("null", "rs1_");
            assertThat(text).containsPattern("90[.,]0000");
        }
        reopened.setVisualFilterJson(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(FilterGroup.and(
            FilterConditionNode.of(new FilterCondition("code", FilterOperator.STARTS_WITH, username, null, FilterDataType.TEXT)),
            FilterConditionNode.of(new FilterCondition("price", FilterOperator.GT, "100", null, FilterDataType.NUMBER)))).toString());
        var filtered = transfer.importTemplate(transfer.exportTemplate(reopened));
        assertThat(filtered.getVisualFilterJson()).isEqualTo(reopened.getVisualFilterJson());
        for (int offset = 0; offset <= 1; offset++) {
            var compiled = assembly.assemble(filtered, org.ipro.reportstudio.param.ReportContext.empty(username),
                Map.of("priceDate", START.plusDays(offset)));
            var dataset = executor.execute(compiled.jpql(), compiled.bindings(), compiled.fields(), 100, 30000);
            assertThat(dataset.rowCount()).isEqualTo(offset == 0 ? 1 : 0);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assembly.assemble(reopened,
            org.ipro.reportstudio.param.ReportContext.empty(username), Map.of())).hasMessageContaining("priceDate");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assembly.assemble(reopened,
            org.ipro.reportstudio.param.ReportContext.empty(username), Map.of("priceDate", "2026-01-01")))
            .hasMessageContaining("LocalDateTime");
    }

    @Test
    void sliceAndPointReaderMatchIndependentOracleIncludingEmptyHistoryAndBoundaryDates() {
        var random = new Random(1302);
        Map<Long, NavigableMap<LocalDateTime, BigDecimal>> histories = new LinkedHashMap<>();
        List<Nomenclature> items = new ArrayList<>();
        for (int itemIndex = 0; itemIndex < 4; itemIndex++) {
            var item = item(itemIndex);
            items.add(item);
            var history = new TreeMap<LocalDateTime, BigDecimal>();
            if (itemIndex < 3) {
                for (int day = 0; day <= 20; day += 2) {
                    var price = BigDecimal.valueOf(random.nextInt(20001), 2);
                    history.put(START.plusDays(day), price);
                    em.persist(new NomenclaturePrice(item, START.plusDays(day), price));
                }
            }
            histories.put(item.getId(), history);
        }
        em.flush();
        em.clear();
        var generatedSource = org.ipro.reportstudio.query.register.RegisterSliceSource.resolve(
            NomenclaturePrice.class, registers, emf.getMetamodel(), catalog);
        for (int offset = -1; offset <= 22; offset++) {
            LocalDateTime date = START.plusDays(offset);
            List<Object[]> rows = execute(report(slice(date, null)), date);
            assertThat(rows).hasSize(4);
            var generated = sliceGenerator.generate(generatedSource,
                org.ipro.register.SliceSpec.latest(NomenclaturePrice.class, date),
                org.ipro.reportstudio.query.register.RegisterSliceCteGenerator.NameContext.empty());
            var generatedRows = execute(generated.asPackage(), date);
            var generatedById = new HashMap<Long, Object[]>();
            generatedRows.forEach(row -> assertThat(generatedById.put(((Number) row[0]).longValue(), row)).isNull());
            assertThat(generatedById).hasSize((int) histories.values().stream().filter(history -> history.floorEntry(date) != null).count());
            for (Object[] row : rows) {
                long id = ((Number) row[0]).longValue();
                var expected = histories.get(id).floorEntry(date);
                assertThat(row[1]).isEqualTo(expected == null ? null : expected.getKey());
                if (expected == null) assertThat(row[2]).isNull();
                else assertThat((BigDecimal) row[2]).isEqualByComparingTo(expected.getValue());
                if (expected == null) assertThat(generatedById).doesNotContainKey(id);
                else {
                    assertThat(generatedById.get(id)[1]).isEqualTo(expected.getKey());
                    assertThat((BigDecimal) generatedById.get(id)[2]).isEqualByComparingTo(expected.getValue());
                }
            }
            for (Nomenclature item : items) {
                var expected = histories.get(item.getId()).floorEntry(date);
                var actual = pointReader.priceOn(item, date);
                assertThat(actual.isPresent()).isEqualTo(expected != null);
                actual.ifPresent(value -> assertThat(value).isEqualByComparingTo(expected.getValue()));
            }
        }
    }

    @Test
    void resourceFilterAfterLatestDoesNotResurrectAnOlderMatchingPrice() {
        var falling = item(0);
        var rising = item(1);
        item(2); // Без истории: должен остаться в LEFT JOIN.
        price(falling, START, "150");
        price(falling, START.plusDays(1), "90");
        price(rising, START, "80");
        price(rising, START.plusDays(1), "120");
        em.flush();
        var date = START.plusDays(1);
        var sourceFilter = RegisterSliceCteSpike.condition("p.price", FilterOperator.GT, "100", FilterDataType.NUMBER);
        var filteredSlice = slice(date, sourceFilter);
        var sliceRows = execute(filteredSlice, date);
        assertThat(sliceRows).hasSize(1);
        assertThat(sliceRows.getFirst()[0]).isEqualTo(rising.getId());
        assertThat((BigDecimal) sliceRows.getFirst()[2]).isEqualByComparingTo("120");
        var reportRows = execute(report(filteredSlice), date);
        assertThat(reportRows).hasSize(3);
        assertThat(reportRows.stream().filter(r -> Objects.equals(r[0], falling.getId())).findFirst().orElseThrow()[2])
            .isNull();
        var consumer = RegisterSliceCteSpike.condition("l.price", FilterOperator.GT, "100", FilterDataType.NUMBER);
        var outerFiltered = RegisterSliceCteSpike.report(slice(date, null), "Nomenclature", scope(), consumer);
        assertThat(execute(outerFiltered, date)).hasSize(1);
    }

    @Test
    void codecRoundTripPreservesPackageBindingsTypesAndExecutedRows() {
        var item = item(0);
        price(item, START, "0");
        em.flush();
        var pack = report(slice(START, null));
        var codec = new VisualQueryPackageJsonCodec();
        var restored = codec.read(codec.write(pack));
        assertThat(restored).isEqualTo(pack);
        var before = VisualQueryCompiler.compile(pack, catalog);
        var after = VisualQueryCompiler.compile(restored, catalog);
        assertThat(after).isEqualTo(before);
        var first = execute(pack, START);
        var second = execute(restored, START);
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(second.getFirst()).containsExactly(first.getFirst());
        assertThat((BigDecimal) second.getFirst()[2]).isEqualByComparingTo("0");
    }

    @Test
    void realGuardRejectsMissingDateBindingAndSeesRegisterInsideCte() {
        var compiled = VisualQueryCompiler.compile(slice(START, null), catalog);
        var checked = guard.guard(compiled.jpql(), compiled.bindings().keySet());
        assertThat(checked.allowed()).as("%s", checked.errors()).isTrue();
        assertThat(checked.analysis().entities()).anyMatch(e -> e.entityName().contains("NomenclaturePrice"));
        assertThat(guard.guard(compiled.jpql(), Set.of()).allowed()).isFalse();
    }

    private VisualQueryPackage slice(LocalDateTime date, FilterNode resources) {
        return RegisterSliceCteSpike.slice("NomenclaturePrice", date, false, null, resources);
    }
    private VisualQueryPackage report(VisualQueryPackage slice) {
        return RegisterSliceCteSpike.report(slice, "Nomenclature", scope(), null);
    }
    private FilterNode scope() {
        return RegisterSliceCteSpike.condition("n.code", FilterOperator.STARTS_WITH, username, FilterDataType.TEXT);
    }
    private List<Object[]> execute(VisualQueryPackage pack, LocalDateTime date) {
        return RegisterSliceCteSpike.execute(em, catalog, guard, pack, date);
    }
    private Nomenclature item(int index) {
        var item = new Nomenclature(username + "-" + index, "CTE " + index, unit);
        em.persist(item);
        return item;
    }
    private void price(Nomenclature item, LocalDateTime date, String price) {
        em.persist(new NomenclaturePrice(item, date, new BigDecimal(price)));
    }
}
