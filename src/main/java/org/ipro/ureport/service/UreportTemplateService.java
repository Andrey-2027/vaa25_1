package org.ipro.ureport.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.jpa.ValidatedJpaCrudService;
import org.ipro.ureport.UreportTemplateRepository;
import org.ipro.ureport.dom.UreportTemplate;
import org.ipro.ureport.params.ParamUiType;
import org.ipro.ureport.params.UreportParamSpec;
import org.springframework.transaction.annotation.Transactional;

import com.bstek.ureport.definition.ReportDefinition;
import com.bstek.ureport.definition.datasource.DataType;
import com.bstek.ureport.definition.dataset.Parameter;
import com.bstek.ureport.definition.dataset.SqlDatasetDefinition;
import com.bstek.ureport.definition.datasource.DatasourceDefinition;
import com.bstek.ureport.parser.ReportParser;

import jakarta.validation.Validator;

/**
 * Сервис жизненного цикла шаблонов UReport3: метаданные в БД + XML-файл
 * в файловом хранилище движка ({@code ureport.fileStoreDir}).
 *
 * <p>C4.6 волна F: класс больше не наследует compatibility base (сама база удалена в C4.7,
 * когда последние два наследника были мигрированы). Тип — {@code INTERNAL_STORE} (нет
 * {@code @EntityMetadata}), поэтому его владелец обслуживает storage сам, а не через
 * canonical entity facade: так решил ADR-0007 §3 для non-metadata report stores, и ту же
 * базу уже используют соседи по подсистеме ({@code ReportTemplate}, {@code JrxmlTemplate}).
 * Write-права RLS на этом пути не теряются: их обеспечивает общая repository/flush-граница,
 * а не сервис (см. ADR-0006 и C3.0.1).</p>
 *
 * <p>Следствие, зафиксированное намеренно: read-мост владельца
 * ({@code EntityCapabilityOverride} с {@code LIST}/{@code DETAIL}) снят — он существовал
 * ровно потому, что сервис шёл через canonical path. Теперь canonical handle у типа нет
 * вообще, и попытка получить его отклоняется до SQL, а не отдаёт граф наугад.</p>
 */
public class UreportTemplateService extends ValidatedJpaCrudService<UreportTemplate> {

    /** Минимальный валидный шаблон: одна пустая ячейка A1 + A4-страница. */
    static final String EMPTY_TEMPLATE_XML = """
            <?xml version="1.0" encoding="UTF-8"?><ureport>\
            <cell expand="None" name="A1" row="1" col="1">\
            <cell-style font-size="10" align="left" valign="middle"></cell-style>\
            <simple-value><![CDATA[]]></simple-value></cell>\
            <row row-number="1" height="18"/>\
            <column col-number="1" width="120"/>\
            <paper type="A4" left-margin="90" right-margin="90" top-margin="72" bottom-margin="72" \
            paging-mode="fitpage" fixrows="0" width="595" height="842" orientation="portrait" \
            html-report-align="left" bg-image="" html-interval-refresh-value="0" \
            column-enabled="false"></paper></ureport>""";

    /** Типизированный доступ к каталогу шаблонов (предметные запросы, а не generic CRUD). */
    private final UreportTemplateRepository templateRepository;
    private final Path fileStoreDir;

    public UreportTemplateService(UreportTemplateRepository repository, Validator validator,
                                  ReferenceCheckService referenceCheckService,
                                  String fileStoreDir) {
        super(repository, validator, referenceCheckService);
        this.templateRepository = repository;
        this.fileStoreDir = Paths.get(fileStoreDir);
    }

    /**
     * Поиск по каталогу шаблонов — предметная поверхность владельца storage, а не standard
     * search: тип не является metadata-driven, поэтому его поля поиска задаёт подсистема
     * (имя и описание). Каталог шаблонов невелик и читается для одного экрана.
     */
    @Override
    public List<UreportTemplate> search(String term) {
        List<UreportTemplate> all = templateRepository.findAll();
        if (term == null || term.isBlank()) {
            return all;
        }
        String needle = term.toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(t -> containsIgnoreCase(t.getName(), needle)
                        || containsIgnoreCase(t.getDescription(), needle))
                .toList();
    }

    /**
     * Создаёт метаданные + пустой XML-шаблон в хранилище. Имя файла генерируется
     * из имени отчёта; при коллизии в хранилище добавляется суффикс.
     */
    @Transactional
    public UreportTemplate createTemplate(String name, String description) {
        return createTemplate(name, description, null);
    }

    /** Создание с привязкой к реестру сущностей (кнопка «Печать» реестра). */
    @Transactional
    public UreportTemplate createTemplate(String name, String description, String targetEntityClass) {
        UreportTemplate template = new UreportTemplate();
        template.setName(name);
        template.setDescription(description);
        template.setTargetEntityClass(targetEntityClass);
        template.setFileName(nextAvailableFileName(name));
        writeTemplateFile(template.getFileName(), EMPTY_TEMPLATE_XML);
        try {
            return save(template);
        } catch (RuntimeException rollback) {
            deleteTemplateFileQuietly(template.getFileName());
            throw rollback;
        }
    }

    /**
     * Печатные формы UReport3 для реестра сущностей (кнопка «Печать»):
     * включённые шаблоны, файл на месте, targetEntityClass совпадает с реестром.
     * Возвращает готовые строки каталога (type=UREPORT3, designerUrl заполнен).
     */
    @Transactional(readOnly = true)
    public List<org.ipro.ureport.catalog.ReportCatalogItem> findPrintableItemsForEntity(Class<?> entityClass) {
        if (entityClass == null) {
            return List.of();
        }
        String entityClassName = entityClass.getName();
        return templateRepository.findAll().stream()
                .filter(UreportTemplate::isEnabled)
                .filter(t -> entityClassName.equals(t.getTargetEntityClass()))
                .filter(t -> Files.isRegularFile(fileStoreDir.resolve(t.getFileName())))
                .map(t -> new org.ipro.ureport.catalog.ReportCatalogItem(
                        t.getId(), org.ipro.ureport.catalog.ReportEngineType.UREPORT3,
                        t.getName(), t.getDescription(), true, designerUrl(t.getFileName()), false))
                .toList();
    }

    @Override
    @Transactional
    public void delete(Long id) {
        UreportTemplate template = templateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон UReport не найден: " + id));
        super.delete(id);
        deleteTemplateFileQuietly(template.getFileName());
    }

    /** Файл шаблона отсутствует в хранилище (удалён вручную) — грид покажет «файл отсутствует». */
    @Transactional(readOnly = true)
    public boolean fileExists(String fileName) {
        return Files.isRegularFile(fileStoreDir.resolve(fileName));
    }

    /**
     * Параметры датасетов шаблона для диалога запуска (Ф2): парсинг XML движком
     * (ReportParser), сбор {@code <param>} всех SQL-датасетов, дедупликация по имени.
     * Caption-резолюция (п. 5.1.1): на итерации caption = name.
     */
    @Transactional(readOnly = true)
    public List<UreportParamSpec> loadParamSpecs(String fileName) {
        if (!fileExists(fileName)) {
            throw new IllegalArgumentException("Файл шаблона отсутствует: " + fileName);
        }
        try (InputStream in = Files.newInputStream(fileStoreDir.resolve(fileName))) {
            ReportDefinition definition = new ReportParser().parse(in, "p");
            Map<String, UreportParamSpec> byName = new LinkedHashMap<>();
            for (DatasourceDefinition datasource : definition.getDatasources()) {
                if (datasource.getDatasets() == null) {
                    continue;
                }
                for (com.bstek.ureport.definition.dataset.DatasetDefinition dataset
                        : datasource.getDatasets()) {
                    if (!(dataset instanceof SqlDatasetDefinition sqlDataset)) {
                        continue;
                    }
                    List<Parameter> params = sqlDataset.getParameters();
                    if (params == null) {
                        continue;
                    }
                    for (Parameter param : params) {
                        byName.putIfAbsent(param.getName(),
                                new UreportParamSpec(param.getName(), param.getName(),
                                        mapUiType(param.getType()), param.getDefaultValue(), false));
                    }
                }
            }
            return List.copyOf(byName.values());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось разобрать параметры шаблона UReport: " + fileName
                            + " — " + e.getMessage(), e);
        }
    }

    private static ParamUiType mapUiType(DataType type) {
        if (type == null) {
            return ParamUiType.STRING;
        }
        return switch (type) {
            case Integer -> ParamUiType.INTEGER;
            case Float -> ParamUiType.FLOAT;
            case Boolean -> ParamUiType.BOOLEAN;
            case Date -> ParamUiType.DATE;
            default -> ParamUiType.STRING;
        };
    }

    /** Читает XML-шаблон из хранилища (для будущих серверных сценариев). */
    @Transactional(readOnly = true)
    public String loadTemplateXml(String fileName) {
        try {
            return Files.readString(fileStoreDir.resolve(fileName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать шаблон UReport: " + fileName, e);
        }
    }

    /** URL веб-дизайнера для шаблона (открывается в новой вкладке). */
    public static String designerUrl(String fileName) {
        return "/ureport/designer?_u=file:" + urlEncode(fileName);
    }

    private String nextAvailableFileName(String name) {
        String stem = fileStem(name);
        String candidate = stem + ".ureport.xml";
        int index = 2;
        while (templateRepository.existsByFileName(candidate)
                || Files.exists(fileStoreDir.resolve(candidate))) {
            candidate = stem + "_" + index++ + ".ureport.xml";
        }
        return candidate;
    }

    private static String fileStem(String name) {
        String stem = name == null ? "ureport" : name.trim().replaceAll("[\\\\/:*?\"<>|]+", "_");
        if (stem.isBlank()) {
            stem = "ureport";
        }
        return stem.length() > 200 ? stem.substring(0, 200) : stem;
    }

    private void writeTemplateFile(String fileName, String content) {
        try {
            Files.createDirectories(fileStoreDir);
            Files.writeString(fileStoreDir.resolve(fileName), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Не удалось создать файл шаблона UReport: " + fileName, e);
        }
    }

    private void deleteTemplateFileQuietly(String fileName) {
        try {
            Files.deleteIfExists(fileStoreDir.resolve(fileName));
        } catch (IOException e) {
            // файл мог быть уже удалён вручную; метаданные удалены — не мешаем
        }
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
