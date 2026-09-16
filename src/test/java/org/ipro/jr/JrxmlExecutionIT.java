package org.ipro.jr;

import jakarta.persistence.EntityManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperPrint;
import org.ip.Application;
import org.ip.config.JpqlRunService;
import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.jr.run.JrxmlExecutionService;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.persistence.config.PersistenceAutoConfiguration;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryExecutor;
import org.ipro.reportstudio.query.ReportQueryGuard;
import org.ipro.reportstudio.query.sqm.SqmQuerySemanticAnalyzer;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsReadableIdsCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.ipro.rls.config.RlsPersistenceAutoConfiguration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Сквозной прогон движка JR: createTemplate → подмена файла шаблона (имитация
 * правки в Jaspersoft Studio) → run: компиляция, jpql через guard+RLS,
 * JasperPrint, PDF-экспорт с DejaVu. RLS-сценарий: пользователь без грантов
 * получает отказ конвейера; SQL без маркера jpql: — явный отказ.
 */
@DataJpaTest
// org.ip объявлен в Application#@EnableJpaRepositories (иначе дублирование бобов репозиториев в срезе).
// Платформенный хаб даёт org.ipro.rls. Пакеты вынесенного persistence-артефакта срез больше не
// перечисляет: @DataJpaTest отключает авто-конфигурации, поэтому модуль подключается явно —
// так же, как это делало бы приложение, если бы полагалось на его авто-конфигурацию.
@ImportAutoConfiguration({PersistenceAutoConfiguration.class, RlsPersistenceAutoConfiguration.class})
@ContextConfiguration(classes = Application.class)
class JrxmlExecutionIT {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private org.ip.repository.UserRepository userRepository;

    @Autowired
    private JrxmlTemplateRepository jrxmlTemplateRepository;

    @TempDir
    Path storeDir;

    private JrxmlTemplateService templateService;
    private JrxmlExecutionService executionService;

    @BeforeEach
    void setUp() {
        loginAs("admin");
        RlsDimensionRegistry registry = new RlsDimensionRegistry("org.ip");
        registry.rebuild();
        AccessService accessService = new AccessService(accessGrantRepository,
                new org.ip.security.UserRepositoryRlsRoleResolver(userRepository), registry);
        RlsCurrentUser currentUser = () -> SecurityContextHolder.getContext()
                .getAuthentication().getName();

        var analyzer = new SqmQuerySemanticAnalyzer(entityManager.getEntityManagerFactory());
        ReportQueryGuard guard = new ReportQueryGuard(analyzer, accessService,
                registry, currentUser, entityManager.getEntityManagerFactory());
        RlsReadableIdsCache cache = new RlsReadableIdsCache(accessService);
        RlsFilterActivator activator = new RlsFilterActivator(registry, cache, currentUser);
        ReportPreviewService preview = new ReportPreviewService(
                new ReportQueryExecutor(entityManager, activator));
        JpqlRunService jpqlRunService = new JpqlRunService(guard, preview);

        templateService = new JrxmlTemplateService(jrxmlTemplateRepository,
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(),
                mock(org.ipro.crud.ReferenceCheckService.class), storeDir.toString());
        executionService = new JrxmlExecutionService(templateService, jpqlRunService, 50);

        persistData();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void persistData() {
        // admin: wildcard-грант; bob: грантов нет
        AccessGrant wildcard = new AccessGrant();
        wildcard.setSubjectType(AccessGrant.SubjectType.USER);
        wildcard.setSubjectKey("admin");
        wildcard.setDimension("*");
        wildcard.setDimensionValueId(null);
        wildcard.setCanRead(true);
        wildcard.setCanUpdate(false);
        wildcard.setCanDelete(false);
        entityManager.persist(wildcard);

        org.ip.model.Journal journal = new org.ip.model.Journal();
        journal.setCode("A");
        journal.setName("Журнал A");
        entityManager.persist(journal);
        entityManager.flush();
    }

    private static final String REPORT_XML = """
            <jasperReport name="journals" pageWidth="595" pageHeight="842" columnWidth="555"\
             leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                <parameter name="code" class="java.lang.String"/>
                <query language="jpql"><![CDATA[jpql:
            select j.id as id, j.name as nm from Journal j where j.code = :code]]></query>
                <field name="id" class="java.lang.Long"/>
                <field name="nm" class="java.lang.String"/>
                <title height="30">
                    <element kind="staticText" x="0" y="0" width="555" height="25">
                        <text><![CDATA[Журналы]]></text>
                    </element>
                </title>
                <detail>
                    <band height="20">
                        <element kind="textField" x="0" y="0" width="100" height="20">
                            <expression><![CDATA[$F{id}]]></expression>
                        </element>
                        <element kind="textField" x="110" y="0" width="400" height="20">
                            <expression><![CDATA[$F{nm}]]></expression>
                        </element>
                    </band>
                </detail>
            </jasperReport>""";

    /** Создаёт шаблон каталогом и «правит макет» — как из Jaspersoft Studio. */
    private JrxmlTemplate createReport() throws Exception {
        JrxmlTemplate created = templateService.createTemplate("Журналы JR", null);
        Files.writeString(templateService.resolve(created.getFileName()),
                REPORT_XML, StandardCharsets.UTF_8);
        return created;
    }

    @Test
    void runFillsJasperPrintThroughGuardAndRls() throws Exception {
        JrxmlTemplate template = createReport();

        JasperPrint print = executionService.run(template, Map.of("code", "A"));

        assertThat(print.getPages()).isNotEmpty();

        byte[] pdf = JasperExportManager.exportReportToPdf(print);
        var fonts = java.util.regex.Pattern.compile("/BaseFont\\s*/[A-Za-z0-9+#-]+")
                .matcher(new String(pdf, StandardCharsets.ISO_8859_1));
        fonts.results().forEach(m -> System.out.println("FONT " + m.group()));
        assertThat(pdf).startsWith(new byte[]{'%', 'P', 'D', 'F'});
        String iso = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(iso).contains("+DejaVu");
    }

    @Test
    void parameterSpecsExposeUserParametersWithDefaults() throws Exception {
        JrxmlTemplate template = createReport();

        var specs = executionService.parameterSpecs(template);

        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).name()).isEqualTo("code");
        assertThat(specs.get(0).valueClassName()).isEqualTo("java.lang.String");
    }

    @Test
    void runWithoutGrantsIsRejectedByPipeline() throws Exception {
        JrxmlTemplate template = createReport();
        loginAs("bob"); // грантов нет

        assertThatThrownBy(() -> executionService.run(template, Map.of("code", "A")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Журналы JR")
                .hasMessageContaining("доступ");
    }

    @Test
    void sqlWithoutJpqlMarkerIsRejected() throws Exception {
        JrxmlTemplate template = createReport();
        String sqlXml = REPORT_XML.replace("jpql:\nselect j.id", "select j.id")
                .replace("language=\"jpql\"", "language=\"sql\"");
        Files.writeString(templateService.resolve(template.getFileName()),
                sqlXml, StandardCharsets.UTF_8);

        // SQL-язык не зарегистрирован (и не должен быть): источник без маркера jpql:
        // отвергается до обращения к БД — RLS неприменим к сырому SQL
        assertThatThrownBy(() -> executionService.run(template, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sql");
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }
}
