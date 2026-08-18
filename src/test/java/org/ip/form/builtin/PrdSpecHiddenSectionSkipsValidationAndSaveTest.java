package org.ip.form.builtin;

import org.ip.form.FieldFactory;
import org.ip.form.TableSectionFactory;
import org.ip.form.registry.FormResolver;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.PrdSpecOper;
import org.ip.service.PrdSpecMtrService;
import org.ip.service.PrdSpecOperService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Скрытая секция варианта «только материалы» (PR-1.5, решение №7):
 * секция, не прошедшая фильтр состава, не attach-ится вовсе — она не участвует
 * ни в validateTableSections(), ни в commitTableSections(), её сервис не запрашивается.
 */
class PrdSpecHiddenSectionSkipsValidationAndSaveTest {

    private PrdSpecMtrService mtrService;
    private ApplicationContext applicationContext;
    private TableSectionFactory factory;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        mtrService = mock(PrdSpecMtrService.class);

        MetadataResolver metadataResolver = mock(MetadataResolver.class);
        TableSectionMetadataInfo mtrSection = sectionMeta(PrdSpecMtr.class, PrdSpecMtrService.class);
        TableSectionMetadataInfo operSection = sectionMeta(PrdSpecOper.class, PrdSpecOperService.class);
        doReturn(List.of(mtrSection, operSection)).when(metadataResolver).resolveTableSections(PrdSpec.class);

        applicationContext = mock(ApplicationContext.class);
        doReturn(mtrService).when(applicationContext).getBean(PrdSpecMtrService.class);
        doReturn(mock(PrdSpecOperService.class)).when(applicationContext).getBean(PrdSpecOperService.class);

        ObjectProvider<FormResolver> resolverProvider = mock(ObjectProvider.class);
        factory = new TableSectionFactory(metadataResolver, mock(FieldFactory.class), applicationContext,
            resolverProvider, List.of());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void hiddenSectionIsNotAttachedAndItsServiceIsNeverLookedUp() {
        ItemForm<PrdSpec> form = formWithNoFields();
        form.setSectionFilter(List.of(PrdSpecMtr.class));

        factory.attachTableSections(form, PrdSpec.class);

        assertThat(((ItemForm) form).getTableSections()).hasSize(1);
        assertThat(form.tableSection(PrdSpecMtr.class)).isNotNull();
        // секция операций скрыта: сервис даже не запрашивается
        verify(applicationContext, never()).getBean(PrdSpecOperService.class);
        verify(applicationContext, never()).getBean("prdSpecOperService");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void withoutFilterAllSectionsAreAttached() {
        ItemForm<PrdSpec> form = formWithNoFields();

        factory.attachTableSections(form, PrdSpec.class);

        assertThat(((ItemForm) form).getTableSections()).hasSize(2);
        assertThat(form.tableSection(PrdSpecMtr.class)).isNotNull();
        assertThat(form.tableSection(PrdSpecOper.class)).isNotNull();
        verify(applicationContext).getBean(PrdSpecOperService.class);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void hiddenSectionDoesNotParticipateInValidation() {
        ItemForm<PrdSpec> form = formWithNoFields();
        form.setSectionFilter(List.of(PrdSpecMtr.class));
        factory.attachTableSections(form, PrdSpec.class);

        PrdSpec spec = new PrdSpec();
        form.setEntity(spec);

        List<String> errors = form.validateTableSections();

        assertThat(errors).isEmpty();
        verify(mtrService).validateRows(any(PrdSpec.class), anyList());
        verify(applicationContext, never()).getBean(PrdSpecOperService.class);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void hiddenSectionDoesNotParticipateInCommit() {
        ItemForm<PrdSpec> form = formWithNoFields();
        form.setSectionFilter(List.of(PrdSpecMtr.class));
        factory.attachTableSections(form, PrdSpec.class);

        PrdSpec saved = new PrdSpec();
        saved.setId(5L);

        form.commitTableSections(saved);

        verify(mtrService).replaceAll(any(PrdSpec.class), anyList());
        verify(applicationContext, never()).getBean(PrdSpecOperService.class);
    }

    @SuppressWarnings("unchecked")
    private static TableSectionMetadataInfo sectionMeta(Class<?> rowClass, Class<?> serviceClass) {
        TableSectionMetadataInfo section = mock(TableSectionMetadataInfo.class);
        doReturn((Class) rowClass).when(section).getRowClass();
        doReturn((Class) serviceClass).when(section).getServiceClass();
        doReturn(List.of()).when(section).getGridFields();
        doReturn(List.of()).when(section).getFormFields();
        doReturn(rowClass.getSimpleName()).when(section).getTitle();
        return section;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ItemForm<PrdSpec> formWithNoFields() {
        EntityMetadataInfo metadata = mock(EntityMetadataInfo.class);
        doReturn(PrdSpec.class).when(metadata).getEntityClass();
        doReturn(List.of()).when(metadata).getFormFields();
        return new ItemForm<>(metadata, mock(FieldFactory.class));
    }
}
