package org.ipro.ureport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.ipro.ureport.dom.UreportTemplate;
import org.ipro.ureport.service.UreportTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UreportTemplateServiceTest {

    @TempDir
    Path fileStoreDir;

    private UreportTemplateRepository repository;
    private UreportTemplateService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(UreportTemplateRepository.class);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        service = new UreportTemplateService(repository, validator,
                fileStoreDir.toString());
        when(repository.save(any())).thenAnswer(invocation -> {
            UreportTemplate t = invocation.getArgument(0);
            t.setId(1L);
            return t;
        });
        when(repository.existsByFileName(any())).thenReturn(false);
        // AbstractBaseService.numberingService - @Autowired Optional, в юнит-тесте пустой
        java.lang.reflect.Field numbering = org.ipro.crud.AbstractBaseService.class
                .getDeclaredField("numberingService");
        numbering.setAccessible(true);
        numbering.set(service, java.util.Optional.empty());
        // accessService используется в checkRlsWrite (method reference) - мокаем
        java.lang.reflect.Field access = org.ipro.crud.AbstractBaseService.class
                .getDeclaredField("accessService");
        access.setAccessible(true);
        access.set(service, mock(org.ipro.rls.AccessService.class));
    }

    @Test
    void createTemplateWritesMinimalXml() {
        UreportTemplate created = service.createTemplate("Проба1", "тестовое описание");

        assertEquals("Проба1", created.getName());
        assertTrue(created.getFileName().endsWith(".ureport.xml"),
                "имя файла: " + created.getFileName());
        Path xml = fileStoreDir.resolve(created.getFileName());
        assertTrue(Files.exists(xml), "XML-файл должен быть создан");
        try {
            String content = Files.readString(xml);
            assertTrue(content.contains("<ureport>"), "минимальный валидный шаблон");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void createTemplateGeneratesUniqueFileNameOnCollision() {
        when(repository.existsByFileName("Проба1.ureport.xml")).thenReturn(true);

        UreportTemplate created = service.createTemplate("Проба1", null);

        assertEquals("Проба1_2.ureport.xml", created.getFileName());
    }

    @Test
    void createTemplateRejectsBlankNameAndLeavesNoFile() {
        assertThrows(Exception.class, () -> service.createTemplate(" ", null));
        List<Path> files = List.of();
        try (var stream = Files.list(fileStoreDir)) {
            files = stream.toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertTrue(files.isEmpty(), "при откате файл не должен остаться");
    }

    @Test
    void fileExistsReflectsStorage() {
        UreportTemplate created = service.createTemplate("Отчёт X", null);
        assertTrue(service.fileExists(created.getFileName()));
        assertFalse(service.fileExists("missing.ureport.xml"));
    }

    @Test
    void loadParamSpecsReadsDatasetParameters() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?><ureport>\
                <cell expand="None" name="A1" row="1" col="1"><cell-style font-size="10"></cell-style>\
                <simple-value><![CDATA[]]></simple-value></cell>\
                <row row-number="1" height="18"/><column col-number="1" width="120"/>\
                <datasource type="buildin" name="Рабочая база">\
                <dataset type="sql" name="ds1">\
                <sql><![CDATA[jpql:
                select a.id as id from Journal a
                where (:parId is null or a.id = :parId)]]></sql>\
                <field name="id"/>\
                <parameter name="parId" type="Integer" default-value=""/>\
                <parameter name="parName" type="String" default-value="АБВ"/>\
                </dataset></datasource>\
                <paper type="A4" left-margin="90" right-margin="90" top-margin="72" bottom-margin="72" \
                paging-mode="fitpage" fixrows="0" width="595" height="842" orientation="portrait" \
                html-report-align="left" bg-image="" html-interval-refresh-value="0" \
                column-enabled="false"></paper></ureport>""";
        Files.writeString(fileStoreDir.resolve("with-params.ureport.xml"), xml);

        List<org.ipro.ureport.params.UreportParamSpec> specs = service.loadParamSpecs("with-params.ureport.xml");

        assertEquals(2, specs.size());
        var parId = specs.get(0);
        assertEquals("parId", parId.name());
        assertEquals(org.ipro.ureport.params.ParamUiType.INTEGER, parId.uiType());
        assertEquals("", parId.defaultValue());
        var parName = specs.get(1);
        assertEquals(org.ipro.ureport.params.ParamUiType.STRING, parName.uiType());
        assertEquals("АБВ", parName.defaultValue());
    }

    @Test
    void loadParamSpecsFailsReadableOnMissingFile() {
        assertThrows(IllegalArgumentException.class,
                () -> service.loadParamSpecs("no-such-file.ureport.xml"));
    }
}
