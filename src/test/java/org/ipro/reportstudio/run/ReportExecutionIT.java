package org.ipro.reportstudio.run;

import jakarta.persistence.EntityManager;
import net.sf.jasperreports.engine.JasperPrint;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.ip.Application;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.UserRepository;
import org.ip.security.CurrentUser;
import org.ip.security.UserRepositoryRlsRoleResolver;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.render.JasperReportCompiler;
import org.ipro.reportstudio.render.ReportCompiler;
import org.ipro.reportstudio.render.ReportExportFormat;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadableIdsCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной цикл Фазы 4 на H2 с настоящими RLS-фильтрами: guard → resolve →
 * execute (авто ORDER BY групповых полей) → compile (группы + агрегаты) →
 * экспорт PDF/XLSX/DOCX/CSV из одного артефакта.
 */
@DataJpaTest
@EnableJpaRepositories(basePackages = {"org.ip", "org.ipro.rls"})
@ContextConfiguration(classes = Application.class)
class ReportExecutionIT {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private jakarta.persistence.EntityManagerFactory entityManagerFactory;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private UserRepository userRepository;

    private ReportExecutionService service;

    private Long journalAId;

    @BeforeEach
    void setUp() {
        var registry = new RlsDimensionRegistry("org.ip");
        registry.rebuild();
        var accessService = new AccessService(accessGrantRepository,
            new UserRepositoryRlsRoleResolver(userRepository), registry);
        var cache = new RlsReadableIdsCache(accessService);
        var activator = new RlsFilterActivator(registry, cache, () -> CurrentUser.username());
        var readGate = new org.ipro.rls.RlsReadGate(accessService, registry);

        var analyzer = new org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer(entityManagerFactory);
        var guard = new org.ipro.reportstudio.query.ReportQueryGuard(analyzer, accessService, registry,
            () -> CurrentUser.username(), entityManagerFactory);
        var executor = new org.ipro.reportstudio.query.ReportQueryExecutor(entityManager, activator);
        var refresher = new org.ipro.reportstudio.param.EntityParamRefresher(entityManager, activator,
            readGate, () -> CurrentUser.username(),
            entityManagerFactory.unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class));
        var resolver = new org.ipro.reportstudio.param.ReportParamResolver(refresher, accessService,
            () -> CurrentUser.username(),
            entityManagerFactory.unwrap(org.hibernate.engine.spi.SessionFactoryImplementor.class),
            new com.fasterxml.jackson.databind.ObjectMapper(), "BRANCH");
        ReportCompiler compiler = new JasperReportCompiler();
        var artifactCache = new ReportArtifactCache(4);

        service = new ReportExecutionService(guard, executor, resolver, compiler, artifactCache);

        Journal journalA = new Journal();
        journalA.setCode("A");
        journalA.setName("Журнал A");
        entityManager.persist(journalA);
        entityManager.flush();
        journalAId = journalA.getId();

        persistGrant("admin", "*", null, true);
        entityManager.flush();

        createPrdSpec(journalA, "SPEC-1");
        createPrdSpec(journalA, "SPEC-2");
        createPrdSpec(journalA, "SPEC-3");
        entityManager.flush();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void fullPipelineGroupAndAggregatesToPdfAndXlsx() throws Exception {
        loginAs("admin");
        ReportTemplate template = groupedTemplate();
        entityManager.persist(template);
        entityManager.flush();

        ReportRunResult result = service.run(template,
            ReportContext.empty(currentUser()), Map.of("journal", journalAId), "ru_RU", "UTC");

        assertThat(result.print()).isInstanceOf(JasperPrint.class);

        byte[] pdf = service.export(result, ReportExportFormat.PDF);
        String pdfText = pdfText(pdf);
        assertThat(pdfText)
            .contains("Отчёт по спецификациям")
            .contains("SPEC-1").contains("SPEC-2").contains("SPEC-3")
            .contains("Количество");

        byte[] xlsx = service.export(result, ReportExportFormat.XLSX);
        String xlsxText = xlsxSharedStrings(xlsx);
        assertThat(xlsxText)
            .contains("SPEC-1").contains("Количество");

        byte[] docx = service.export(result, ReportExportFormat.DOCX);
        assertThat(docx).isNotEmpty();
        assertThat(docxDocumentXml(docx)).contains("SPEC-1");

        byte[] csv = service.export(result, ReportExportFormat.CSV);
        String csvText = new String(csv, StandardCharsets.UTF_8);
        assertThat(csvText).contains("SPEC-1").contains("SPEC-3");
    }

    @Test
    void cachedArtifactReused() {
        loginAs("admin");
        ReportTemplate template = groupedTemplate();
        entityManager.persist(template);
        entityManager.flush();

        ReportRunResult first = service.run(template,
            ReportContext.empty(currentUser()), Map.of("journal", journalAId), "ru_RU", "UTC");

        var cached = service.cached(first.key());
        assertThat(cached).isPresent();
        assertThat(cached.orElseThrow()).isSameAs(first.print());
    }

    @Test
    void guardRefusesUnboundRequiredEntityParam() {
        loginAs("admin");
        ReportTemplate template = groupedTemplate();
        entityManager.persist(template);
        entityManager.flush();

        org.junit.jupiter.api.Assertions.assertThrows(ReportRunException.class, () ->
            service.run(template, ReportContext.empty(currentUser()), Map.of(), "ru_RU", "UTC"));
    }

    private ReportTemplate groupedTemplate() {
        ReportTemplate template = new ReportTemplate();
        template.setName("Отчёт по спецификациям");
        template.setState(ReportTemplateState.PUBLISHED);
        template.setJpql("select s.journal.code, s.journal.name, s.codeSpec from PrdSpec s where s.journal = :journal");
        template.setMaxRows(100);
        template.setTimeoutMs(30_000);

        ReportParam journal = new ReportParam();
        journal.setName("journal");
        journal.setKind(ReportParamKind.ENTITY);
        journal.setValueSource(ReportParamSource.FORM);
        journal.setRequired(true);
        journal.setEntityClass(Journal.class.getName());
        template.addParam(journal);

        ReportBand detail = band(ReportBandKind.DETAIL, null, null);
        detail.addField(field("s.journal.code", "Журнал", null));
        detail.addField(field("s.journal.name", "Наименование", null));
        detail.addField(field("s.codeSpec", "Код спецификации", null));
        template.addBand(detail);

        ReportBand groupHeader = band(ReportBandKind.GROUP_HEADER, null, "s.journal.code");
        template.addBand(groupHeader);

        ReportBand groupFooter = band(ReportBandKind.GROUP_FOOTER, groupHeader, null);
        ReportField count = field("s.codeSpec", "Количество", null);
        count.setAggregation(ReportFieldAggregation.COUNT);
        groupFooter.addField(count);
        template.addBand(groupFooter);
        return template;
    }

    private static ReportBand band(ReportBandKind kind, ReportBand parent, String groupField) {
        ReportBand band = new ReportBand();
        band.setKind(kind);
        band.setParent(parent);
        band.setGroupField(groupField);
        return band;
    }

    private static ReportField field(String queryField, String caption, Integer width) {
        ReportField field = new ReportField();
        field.setQueryField(queryField);
        field.setCaption(caption);
        field.setWidth(width);
        field.setVisible(true);
        return field;
    }

    private void createPrdSpec(Journal journal, String codeSpec) {
        UnitOfMeasurement unit = new UnitOfMeasurement();
        unit.setCode("U-" + codeSpec);
        unit.setShortCode("SC-" + codeSpec);
        unit.setName("Штука " + codeSpec);
        entityManager.persist(unit);

        Nomenclature nomenclature = new Nomenclature();
        nomenclature.setCode("N-" + codeSpec);
        nomenclature.setName("Деталь " + codeSpec);
        nomenclature.setUnitOfMeasurement(unit);
        entityManager.persist(nomenclature);

        PrdSpec spec = new PrdSpec();
        spec.setJournal(journal);
        spec.setNomenclature(nomenclature);
        spec.setCodeSpec(codeSpec);
        entityManager.persist(spec);
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private static String currentUser() {
        return CurrentUser.username();
    }

    private void persistGrant(String subjectKey, String dimension, Long dimensionValueId, boolean read) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setDimensionValueId(dimensionValueId);
        grant.setCanRead(read);
        grant.setCanUpdate(false);
        grant.setCanDelete(false);
        entityManager.persist(grant);
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static String docxDocumentXml(byte[] docx) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(docx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("word/document.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
                zip.closeEntry();
            }
        }
        return "";
    }
    private static String xlsxSharedStrings(byte[] xlsx) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(xlsx))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals("xl/sharedStrings.xml")) {
                    byte[] buf = zip.readAllBytes();
                    sb.append(new String(buf, StandardCharsets.UTF_8));
                }
                zip.closeEntry();
            }
        }
        return sb.toString();
    }
}