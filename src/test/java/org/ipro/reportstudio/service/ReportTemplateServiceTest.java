package org.ipro.reportstudio.service;

import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import org.ipro.reportstudio.ReportTemplateRepository;
import org.ipro.reportstudio.dom.ReportBand;
import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportField;
import org.ipro.reportstudio.dom.ReportParam;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.junit.jupiter.api.Test;
import org.ipro.rls.AccessService;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

class ReportTemplateServiceTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void savesStructurallyValidTemplateThroughExistingRepository() {
        ReportTemplateRepository repository = mock(ReportTemplateRepository.class);
        ReportTemplate template = validTemplate("Движения товаров");
        when(repository.save(template)).thenReturn(template);
        ReportTemplateService service = newService(repository);

        ReportTemplate saved = service.saveTemplate(template);

        assertThat(saved).isSameAs(template);
        verify(repository).save(template);
    }

    @Test
    void copiesTemplateAsIndependentDraft() {
        ReportTemplateRepository repository = mock(ReportTemplateRepository.class);
        ReportTemplate source = validTemplate("Остатки");
        ReportParam param = new ReportParam();
        param.setName("date");
        param.setCaption("Дата");
        param.setPosition(0);
        source.addParam(param);
        when(repository.findById(7L)).thenReturn(Optional.of(source));
        when(repository.existsByName("Остатки (копия)")).thenReturn(false);
        when(repository.save(any(ReportTemplate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReportTemplateService service = newService(repository);

        ReportTemplate copy = service.copyTemplate(7L);

        assertThat(copy).isNotSameAs(source);
        assertThat(copy.getName()).isEqualTo("Остатки (копия)");
        assertThat(copy.getParams()).singleElement().isNotSameAs(source.getParams().getFirst());
        assertThat(copy.getBands()).singleElement().satisfies(band -> {
            assertThat(band).isNotSameAs(source.getBands().getFirst());
            assertThat(band.getFields()).singleElement()
                    .isNotSameAs(source.getBands().getFirst().getFields().getFirst());
        });
        verify(repository).save(copy);
    }

    @Test
    void rejectsTemplateWithoutMandatoryDetailBand() {
        ReportTemplateRepository repository = mock(ReportTemplateRepository.class);
        ReportTemplateService service = newService(repository);
        ReportTemplate template = new ReportTemplate();
        template.setName("Без детализации");
        template.setJpql("select j from Journal j");

        assertThatThrownBy(() -> service.saveTemplate(template))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("DETAIL");
    }

    private ReportTemplateService newService(ReportTemplateRepository repository) {
        ReportTemplateService service = new ReportTemplateService(repository, validator);
        ReflectionTestUtils.setField(service, "accessService", mock(AccessService.class));
        return service;
    }

    private static ReportTemplate validTemplate(String name) {
        ReportTemplate template = new ReportTemplate();
        template.setName(name);
        template.setJpql("select j.code as code from Journal j");

        ReportBand detail = new ReportBand();
        detail.setKind(ReportBandKind.DETAIL);
        detail.setPosition(0);
        ReportField field = new ReportField();
        field.setQueryField("code");
        field.setPosition(0);
        detail.addField(field);
        template.addBand(detail);
        return template;
    }
}
