package org.ipro.jr;

import java.util.List;
import java.util.Optional;

import org.ipro.jr.dom.JrxmlTemplate;
import org.ipro.jr.service.JrCatalogContributor;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.report.catalog.ReportCatalogItem;
import org.ipro.report.catalog.ReportCatalogReadGate;
import org.ipro.report.catalog.ReportEngineType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Правила ветки JR в общем каталоге: гейт чтения до поиска, флаг шаблона, .jrxml в хранилище. */
class JrCatalogContributorTest {

    private final JrxmlTemplateService templates = mock(JrxmlTemplateService.class);
    private final ReportCatalogReadGate readGate = mock(ReportCatalogReadGate.class);
    private final JrCatalogContributor contributor =
            new JrCatalogContributor(templates, readGate);

    @Test
    void deniedReadYieldsNoRowsWithoutSearching() {
        when(readGate.canRead(JrxmlTemplate.class)).thenReturn(false);

        assertThat(contributor.findAll("", true)).isEmpty();
        verify(templates, never()).search(anyString());
    }

    @Test
    void disabledRowsFollowTheIncludeDisabledFlag() {
        when(readGate.canRead(JrxmlTemplate.class)).thenReturn(true);
        when(templates.search("")).thenReturn(List.of(
                template(1L, "Прайс", true, "price.jrxml"),
                template(2L, "Черновик", false, "draft.jrxml")));

        assertThat(contributor.findAll("", false))
                .extracting(ReportCatalogItem::name).containsExactly("Прайс");
        assertThat(contributor.findAll("", true))
                .extracting(ReportCatalogItem::name).containsExactly("Прайс", "Черновик");
    }

    @Test
    void missingLayoutIsMarked() {
        when(readGate.canRead(JrxmlTemplate.class)).thenReturn(true);
        when(templates.search("")).thenReturn(List.of(template(3L, "Прайс", true, "gone.jrxml")));
        when(templates.fileExists("gone.jrxml")).thenReturn(false);

        ReportCatalogItem item = contributor.findAll("", true).get(0);

        assertThat(item.type()).isEqualTo(ReportEngineType.JR);
        assertThat(item.fileMissing()).isTrue();
    }

    @Test
    void findResolvesTheRowAndReportsAMissingTemplate() {
        when(templates.findById(4L))
                .thenReturn(Optional.of(template(4L, "Прайс", true, "price.jrxml")));
        when(templates.fileExists("price.jrxml")).thenReturn(true);

        assertThat(contributor.find(4L).name()).isEqualTo("Прайс");
        assertThatThrownBy(() -> contributor.find(8L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JR");
    }

    private static JrxmlTemplate template(long id, String name, boolean enabled,
                                          String fileName) {
        JrxmlTemplate template = new JrxmlTemplate();
        template.setId(id);
        template.setName(name);
        template.setEnabled(enabled);
        template.setFileName(fileName);
        return template;
    }
}
