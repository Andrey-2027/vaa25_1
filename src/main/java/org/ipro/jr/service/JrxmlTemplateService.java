package org.ipro.jr.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import org.ipro.crud.ReferenceCheckService;
import org.ipro.crud.jpa.ValidatedJpaCrudService;
import org.ipro.jr.JrxmlTemplateRepository;
import org.ipro.jr.dom.JrxmlTemplate;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.Validator;

/**
 * Сервис жизненного цикла шаблонов JR (.jrxml): метаданные в БД + XML-файл
 * в отдельном хранилище движка ({@code jrxml.fileStoreDir} — не переиспользуем
 * ureport.fileStoreDir, чтобы жизненные циклы движков не были связаны).
 *
 * <p>Базовый CRUD — платформенный {@link ValidatedJpaCrudService}
 * (bean-валидация + reference-check на удаление).</p>
 */
public class JrxmlTemplateService extends ValidatedJpaCrudService<JrxmlTemplate> {

    /**
     * Минимальный компилируемый шаблон в JRXML 7 model (формат JasperReports 7 /
     * Jaspersoft Studio 7): queryString не используется, язык запроса задаётся
     * атрибутом {@code language} элемента {@code query}. Пользователь заменяет
     * макет и запрос в Jaspersoft Studio, сохраняя язык {@code jpql}.
     */
    static final String EMPTY_TEMPLATE_XML = """
            <jasperReport name="report" pageWidth="595" pageHeight="842" columnWidth="555"\
             leftMargin="20" rightMargin="20" topMargin="20" bottomMargin="20">
                <query language="jpql"><![CDATA[jpql:
            select j.id as id from Journal j]]></query>
                <field name="id" class="java.lang.Long"/>
                <title height="40">
                    <element kind="staticText" x="0" y="0" width="555" height="30">
                        <text><![CDATA[Замените макет в Jaspersoft Studio]]></text>
                    </element>
                </title>
                <detail>
                    <band height="20">
                        <element kind="textField" x="0" y="0" width="200" height="20">
                            <expression><![CDATA[$F{id}]]></expression>
                        </element>
                    </band>
                </detail>
            </jasperReport>""";

    private final JrxmlTemplateRepository repository;
    private final Path fileStoreDir;

    public JrxmlTemplateService(JrxmlTemplateRepository repository, Validator validator,
                                ReferenceCheckService referenceCheckService, String fileStoreDir) {
        super(repository, validator, referenceCheckService);
        this.repository = repository;
        this.fileStoreDir = Paths.get(fileStoreDir);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JrxmlTemplate> search(String term) {
        List<JrxmlTemplate> all = repository.findAll();
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
     * Создаёт метаданные + пустой компилируемый .jrxml в хранилище.
     * Имя файла генерируется из имени отчёта; при коллизии добавляется суффикс.
     * Макет затем правится в Jaspersoft Studio.
     */
    @Transactional
    public JrxmlTemplate createTemplate(String name, String description) {
        return createTemplate(name, description, null);
    }

    /** Создание с привязкой к реестру сущностей (кнопка «Печать» реестра). */
    @Transactional
    public JrxmlTemplate createTemplate(String name, String description,
                                        String targetEntityClass) {
        JrxmlTemplate template = new JrxmlTemplate();
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

    /** Файл шаблона отсутствует в хранилище (удалён вручную). */
    @Transactional(readOnly = true)
    public boolean fileExists(String fileName) {
        return Files.isRegularFile(fileStoreDir.resolve(fileName));
    }

    /**
     * Печатные формы JR для реестра сущностей (кнопка «Печать»):
     * включённые шаблоны, файл на месте, targetEntityClass совпадает с реестром.
     * Возвращает готовые строки каталога (type=JR).
     */
    @Transactional(readOnly = true)
    public List<org.ipro.ureport.catalog.ReportCatalogItem> findPrintableItemsForEntity(
            Class<?> entityClass) {
        if (entityClass == null) {
            return List.of();
        }
        String entityClassName = entityClass.getName();
        return repository.findAll().stream()
                .filter(JrxmlTemplate::isEnabled)
                .filter(t -> entityClassName.equals(t.getTargetEntityClass()))
                .filter(t -> Files.isRegularFile(fileStoreDir.resolve(t.getFileName())))
                .map(t -> new org.ipro.ureport.catalog.ReportCatalogItem(
                        t.getId(), org.ipro.ureport.catalog.ReportEngineType.JR,
                        t.getName(), t.getDescription(), true, null, false))
                .toList();
    }

    public Path resolve(String fileName) {
        return fileStoreDir.resolve(fileName);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        JrxmlTemplate template = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон JR не найден: " + id));
        super.delete(id);
        deleteTemplateFileQuietly(template.getFileName());
    }

    private String nextAvailableFileName(String name) {
        String stem = fileStem(name);
        String candidate = stem + ".jrxml";
        int index = 2;
        while (repository.existsByFileName(candidate) || Files.exists(fileStoreDir.resolve(candidate))) {
            candidate = stem + "_" + index++ + ".jrxml";
        }
        return candidate;
    }

    private static String fileStem(String name) {
        String stem = name == null ? "jrxml-report" : name.trim().replaceAll("[\\\\/:*?\"<>|]+", "_");
        if (stem.isBlank()) {
            stem = "jrxml-report";
        }
        return stem.length() > 200 ? stem.substring(0, 200) : stem;
    }

    private void writeTemplateFile(String fileName, String content) {
        try {
            Files.createDirectories(fileStoreDir);
            Files.writeString(fileStoreDir.resolve(fileName), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Не удалось создать файл шаблона JR: " + fileName, e);
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
}
