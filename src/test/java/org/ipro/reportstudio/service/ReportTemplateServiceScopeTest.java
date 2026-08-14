package org.ipro.reportstudio.service;

import jakarta.validation.Validator;
import org.ip.model.PrdSpec;
import org.ip.model.Workshop;
import org.ipro.reportstudio.ReportTemplateRepository;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportParamSource;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportTemplateServiceScopeTest {

    private ReportTemplateRepository repository;
    private ReportTemplateService service;

    @BeforeEach
    void setUp() {
        repository = mock(ReportTemplateRepository.class);
        service = new ReportTemplateService(repository, mock(Validator.class));
    }

    @Test
    void findsOnlyExplicitlyTargetedTemplatesForEachRegistry() {
        ReportTemplate prdSpecOne = template("Spec card", PrdSpec.class);
        ReportTemplate prdSpecTwo = template("Spec list", PrdSpec.class);
        ReportTemplate workshop = template("Workshop card", Workshop.class);
        when(repository.findAll()).thenReturn(List.of(prdSpecOne, workshop, prdSpecTwo));

        List<ReportTemplate> prdSpecTemplates = service.findPrintableForEntity(PrdSpec.class);
        List<ReportTemplate> workshopTemplates = service.findPrintableForEntity(Workshop.class);

        assertEquals(List.of("Spec card", "Spec list"), prdSpecTemplates.stream().map(ReportTemplate::getName).toList());
        assertEquals(List.of("Workshop card"), workshopTemplates.stream().map(ReportTemplate::getName).toList());
    }

    @Test
    void keepsLegacyContextTemplateVisibleForCompatibleRegistryUntilItIsScoped() {
        ReportTemplate legacy = new ReportTemplate();
        legacy.setName("Legacy spec print");
        ReportParam contextParam = new ReportParam();
        contextParam.setEntityClass(PrdSpec.class.getName());
        contextParam.setValueSource(ReportParamSource.CONTEXT);
        legacy.addParam(contextParam);
        when(repository.findAll()).thenReturn(List.of(legacy));

        assertEquals(List.of(legacy), service.findPrintableForEntity(PrdSpec.class));
        assertEquals(List.of(), service.findPrintableForEntity(Workshop.class));
    }

    private static ReportTemplate template(String name, Class<?> targetEntityClass) {
        ReportTemplate template = new ReportTemplate();
        template.setName(name);
        template.setTargetEntityClass(targetEntityClass.getName());
        return template;
    }
}
