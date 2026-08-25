package org.ipro.ureport;

import java.util.List;

import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.dom.ReportTemplateState;
import org.ipro.reportstudio.service.ReportTemplateService;
import org.ipro.ureport.catalog.ReportCatalogItem;
import org.ipro.ureport.catalog.ReportCatalogService;
import org.ipro.ureport.catalog.ReportEngineType;
import org.ipro.ureport.dom.UreportTemplate;
import org.ipro.ureport.service.UreportTemplateService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportCatalogServiceTest {

    private ReportTemplateService udrService;
    private UreportTemplateService ureportService;
    private org.ipro.jr.service.JrxmlTemplateService jrService;
    private ReportCatalogService catalogService;

    @BeforeEach
    void setUp() {
        udrService = mock(ReportTemplateService.class);
        ureportService = mock(UreportTemplateService.class);
        jrService = mock(org.ipro.jr.service.JrxmlTemplateService.class);
        when(jrService.search(any())).thenReturn(List.of());
        RlsReadGate readGate = mock(RlsReadGate.class);
        when(readGate.canRead(any(), anyString())).thenReturn(true);
        RlsCurrentUser currentUser = mock(RlsCurrentUser.class);
        when(currentUser.username()).thenReturn("admin");
        catalogService = new ReportCatalogService(udrService, ureportService,
                jrService, readGate, currentUser);
    }

    @Test
    void mergesBothEnginesSortedByName() {
        ReportTemplate udr = new ReportTemplate();
        udr.setId(1L);
        udr.setName("Ясли UDR");
        udr.setState(ReportTemplateState.PUBLISHED);
        when(udrService.search("")).thenReturn(List.of(udr));

        UreportTemplate ureport = new UreportTemplate();
        ureport.setId(1L); // тот же числовой id, ДРУГАЯ строка (коллизия namespace)
        ureport.setName("Азбука UReport");
        ureport.setEnabled(true);
        ureport.setFileName("azbuka.ureport.xml");
        when(ureportService.search("")).thenReturn(List.of(ureport));
        when(ureportService.fileExists("azbuka.ureport.xml")).thenReturn(true);

        List<ReportCatalogItem> items = catalogService.findAll("", true);

        assertEquals(2, items.size());
        assertEquals("Азбука UReport", items.get(0).name()); // сортировка по имени
        assertEquals(ReportEngineType.UREPORT3, items.get(0).type());
        assertEquals(ReportEngineType.UDR, items.get(1).type());
        // коллизия (1, UDR) vs (1, UREPORT3) не склеивает записи: ключ - пара
        assertEquals(2, distinctKeys(items));
    }

    @Test
    void marksMissingFileForUreportItems() {
        when(udrService.search("")).thenReturn(List.of());
        UreportTemplate orphan = new UreportTemplate();
        orphan.setId(7L);
        orphan.setName("Осиротевший");
        orphan.setEnabled(true);
        orphan.setFileName("deleted.ureport.xml");
        when(ureportService.search("")).thenReturn(List.of(orphan));
        when(ureportService.fileExists("deleted.ureport.xml")).thenReturn(false);

        List<ReportCatalogItem> items = catalogService.findAll("", true);

        assertTrue(items.get(0).fileMissing());
        assertEquals("deleted.ureport.xml", designerFile(items.get(0)));
    }

    @Test
    void findResolvesByTypeAndIdPair() {
        ReportTemplate udr = new ReportTemplate();
        udr.setId(5L);
        udr.setName("UDR пять");
        udr.setState(ReportTemplateState.PUBLISHED);
        when(udrService.loadTemplate(5L)).thenReturn(udr);

        assertEquals("UDR пять", catalogService.find(ReportEngineType.UDR, 5L).name());
        // UREPORT3 c тем же id - другая запись, резолвится своим сервисом
        assertThrows(IllegalArgumentException.class,
                () -> catalogService.find(ReportEngineType.UREPORT3, 5L));
    }

    @Test
    void filtersDisabledUnlessRequested() {
        ReportTemplate disabled = new ReportTemplate();
        disabled.setId(2L);
        disabled.setName("Выключенный");
        disabled.setState(ReportTemplateState.DRAFT);
        when(udrService.search("")).thenReturn(List.of(disabled));
        when(ureportService.search("")).thenReturn(List.of());

        assertTrue(catalogService.findAll("", false).isEmpty());
        assertEquals(1, catalogService.findAll("", true).size());
    }

    private static int distinctKeys(List<ReportCatalogItem> items) {
        return (int) items.stream()
                .map(i -> i.type() + "#" + i.id())
                .distinct()
                .count();
    }

    private static String designerFile(ReportCatalogItem item) {
        String url = item.designerUrl();
        return url.substring(url.indexOf("file:") + "file:".length());
    }
}
