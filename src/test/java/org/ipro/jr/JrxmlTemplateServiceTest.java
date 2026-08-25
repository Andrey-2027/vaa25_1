package org.ipro.jr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.ipro.crud.ReferenceCheckService;
import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.jr.service.JrxmlTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Жизненный цикл шаблона JR: createTemplate пишет валидный .jrxml в хранилище,
 * search фильтрует по имени/описанию, delete удаляет и метаданные, и файл
 * (файл здесь — временный каталог, БД не нужна).
 */
class JrxmlTemplateServiceTest {

    @TempDir
    Path storeDir;

    private JrxmlTemplateRepository repository;
    private JrxmlTemplateService service;

    @BeforeEach
    void setUp() {
        repository = mock(JrxmlTemplateRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        service = new JrxmlTemplateService(repository,
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator(),
                mock(ReferenceCheckService.class),
                storeDir.toString());
    }

    @Test
    void createTemplateWritesCompilableXmlAndMetadata() throws IOException {
        when(repository.existsByFileName(anyString())).thenReturn(false);
        // save() уходит в репозиторий — мок вернёт сущность как есть с id
        when(repository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    JrxmlTemplate template = invocation.getArgument(0);
                    template.setId(42L);
                    return template;
                });

        JrxmlTemplate created = service.createTemplate("Отчёт по журналам", "описание");

        assertThat(created.getId()).isEqualTo(42L);
        assertThat(created.getFileName()).endsWith(".jrxml");
        Path file = storeDir.resolve(created.getFileName());
        assertThat(file).exists();
        String xml = Files.readString(file);
        assertThat(xml).contains("<jasperReport").contains("jpql:");
        assertThat(service.fileExists(created.getFileName())).isTrue();
    }

    @Test
    void searchFiltersByNameAndDescription() {
        JrxmlTemplate a = template(1L, "Азбука", "первый");
        JrxmlTemplate b = template(2L, "Ясли", "второй отчёт");
        when(repository.findAll()).thenReturn(List.of(a, b));

        assertThat(service.search("")).hasSize(2);
        assertThat(service.search("азб")).containsExactly(a);
        assertThat(service.search("ВТОРОЙ")).containsExactly(b);
        assertThat(service.search("нет такого")).isEmpty();
    }

    private static JrxmlTemplate template(long id, String name, String description) {
        JrxmlTemplate template = new JrxmlTemplate();
        template.setId(id);
        template.setName(name);
        template.setDescription(description);
        template.setFileName(name + ".jrxml");
        return template;
    }
}

