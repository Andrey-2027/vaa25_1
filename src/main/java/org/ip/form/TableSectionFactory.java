package org.ip.form;

import org.ip.form.builtin.ItemForm;
import org.ip.form.builtin.ItemTable;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.TableSectionMetadataInfo;
import org.ip.service.TableSectionService;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Строит ItemTable для табличных частей сущности и подключает их к ItemForm.
 *
 * Единая точка, вызываемая из двух мест, которые создают generic ItemForm
 * (FormResolver — для Dialog-режима, ItemFormWrapperView — для Workspace-режима),
 * чтобы табличные части подключались одинаково независимо от режима открытия формы.
 */
@Component
public class TableSectionFactory {

    private final MetadataResolver metadataResolver;
    private final FieldFactory fieldFactory;
    private final ApplicationContext applicationContext;

    public TableSectionFactory(MetadataResolver metadataResolver,
                                FieldFactory fieldFactory,
                                ApplicationContext applicationContext) {
        this.metadataResolver = metadataResolver;
        this.fieldFactory = fieldFactory;
        this.applicationContext = applicationContext;
    }

    /**
     * Резолвит табличные части для entityClass и подключает их к форме.
     * Если табличных частей нет — ничего не делает (form остаётся как есть).
     */
    public <T extends IdentifiableEntity> void attachTableSections(ItemForm<T> form, Class<T> entityClass) {
        List<TableSectionMetadataInfo> sections = metadataResolver.resolveTableSections(entityClass);
        for (TableSectionMetadataInfo section : sections) {
            ItemTable<?, T> table = createItemTable(section);
            form.addTableSection(section.getTitle(), table);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity> ItemTable<?, T> createItemTable(TableSectionMetadataInfo section) {
        TableSectionService rawService = findTableSectionService(section);
        return new ItemTable(section, fieldFactory, rawService);
    }

    /**
     * Находит Spring-бин TableSectionService для табличной части.
     *
     * Стратегия аналогична FormCoordinator.findService() для BaseService:
     *   1. @TableSectionMetadata.serviceClass(), если указан
     *   2. Fallback: бин по имени "<rowClassName>Service"
     */
    private TableSectionService<?, ?> findTableSectionService(TableSectionMetadataInfo section) {
        Class<?> serviceClass = section.getServiceClass();

        if (serviceClass != null && serviceClass != void.class) {
            try {
                return (TableSectionService<?, ?>) applicationContext.getBean(serviceClass);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Service class specified in @TableSectionMetadata not found: " +
                    serviceClass.getName() + ". Make sure " + serviceClass.getSimpleName() +
                    " is a Spring @Service bean.", e);
            }
        }

        String serviceName = uncapitalize(section.getRowClass().getSimpleName()) + "Service";
        try {
            return (TableSectionService<?, ?>) applicationContext.getBean(serviceName);
        } catch (Exception e) {
            throw new IllegalStateException(
                "No TableSectionService found for " + section.getRowClass().getSimpleName() + ". " +
                "Expected bean name: '" + serviceName + "'. " +
                "Solutions:\n" +
                "  1. Add serviceClass to @TableSectionMetadata: serviceClass = YourItemService.class\n" +
                "  2. Create a @Service class named " + capitalize(serviceName) + "\n" +
                "  3. Rename your service bean to '" + serviceName + "'", e);
        }
    }

    private String uncapitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
