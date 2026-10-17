package org.ipro.reportstudio.service;

import java.util.List;

import org.ipro.report.catalog.ReportCatalogItem;
import org.ipro.report.catalog.ReportEngineType;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Правила ветки UDR в общем каталоге: публикация как «включён», поиск по термину. */
class UdrCatalogContributorTest {

    private final ReportTemplateService templates = mock(ReportTemplateService.class);
    private final UdrCatalogContributor contributor = new UdrCatalogContributor(templates);

    @Test
    void draftIsHiddenUntilDisabledRowsAreRequested() {
        when(templates.search("")).thenReturn(List.of(
                template(1L, "Остатки", ReportTemplateState.PUBLISHED),
                template(2L, "Черновик", ReportTemplateState.DRAFT)));

        assertThat(contributor.findAll("", false))
                .extracting(ReportCatalogItem::name).containsExactly("Остатки");
        assertThat(contributor.findAll("", true))
                .extracting(ReportCatalogItem::name).containsExactly("Остатки", "Черновик");
    }

    @Test
    void declaredEngineTypeIsUdr() {
        assertThat(contributor.type()).isEqualTo(ReportEngineType.UDR);
    }

    @Test
    void findLoadsTheTemplateAndBuildsTheRow() {
        when(templates.loadTemplate(9L))
                .thenReturn(template(9L, "Остатки", ReportTemplateState.PUBLISHED));

        ReportCatalogItem item = contributor.find(9L);

        assertThat(item.id()).isEqualTo(9L);
        assertThat(item.type()).isEqualTo(ReportEngineType.UDR);
        assertThat(item.enabled()).isTrue();
    }

    @Test
    void searchTermReachesTheTemplateService() {
        when(templates.search("остат")).thenReturn(List.of());

        contributor.findAll("остат", true);

        verify(templates).search("остат");
    }

    private static ReportTemplate template(long id, String name, ReportTemplateState state) {
        ReportTemplate template = new ReportTemplate();
        template.setId(id);
        template.setName(name);
        template.setState(state);
        return template;
    }
}
