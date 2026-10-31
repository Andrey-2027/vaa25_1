package org.explorerpilot;

import org.ip.Application;
import org.ip.model.Nomenclature;
import org.ip.views.admin.fixture.EntityExplorerDensityFixture;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.FetchPlanInspection;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionProvenanceCatalog;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.registry.FormRegistry;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.MetadataConsistencyStartupCheck;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.ReferenceIndex;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.numbering.NumberingMetadataRegistry;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.vaadin.explorer.EntitySummary;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** External test classpath only: same host, address and panel, with five real + 25 synthetic fields.
 * No new JPA type, route, production option or business-data fixture is installed. */
public final class DensityLauncher {
    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[]{Application.class, DensityConfiguration.class}, args);
    }

    @Configuration(proxyBeanMethods = false)
    public static class DensityConfiguration {
        @Bean static BeanPostProcessor densityProjection(BeanFactory beans) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof EntitySummaryAssembler assembler ? new DensityAssembler(assembler, beans) : bean;
                }
            };
        }
    }

    private static final class DensityAssembler extends EntitySummaryAssembler {
        private final EntitySummaryAssembler delegate;
        private final MetadataResolver metadata;

        DensityAssembler(EntitySummaryAssembler delegate, BeanFactory beans) {
            super("org.ip", beans.getBean(MetadataResolver.class), beans.getBean(FormRegistry.class),
                beans.getBean(ReferenceIndex.class), beans.getBean(NumberingMetadataRegistry.class),
                beans.getBean(SubsystemRegistry.class), beans.getBean(FacetResolver.class),
                beans.getBean(EntityDescriptorCatalog.class), beans.getBean(FormRouteCatalog.class),
                beans.getBean(EntityLifecycleRegistry.class), beans.getBean(SectionMetadataRegistry.class),
                beans.getBeanProvider(MetadataConsistencyStartupCheck.class).getIfAvailable(),
                beans.getBean(ActionRegistry.class), beans.getBean(ActionHandlerRegistry.class),
                beans.getBean(ActionProvenanceCatalog.class), beans.getBean(FetchPlanInspection.class),
                beans.getBean(RlsDimensionRegistry.class));
            this.delegate = delegate;
            this.metadata = beans.getBean(MetadataResolver.class);
        }

        @Override public EntitySummary summarize(Class<?> type) {
            EntitySummary summary = delegate.summarize(type);
            if (type != Nomenclature.class) return summary;
            var fields = new ArrayList<>(summary.structure().fields());
            for (var field : metadata.resolveAllAnnotatedFields(EntityExplorerDensityFixture.class).subList(5, 30)) {
                String symbol = EntityExplorerDensityFixture.class.getName() + "#" + field.getName();
                var label = ResolvedValue.fact(field.getLabel(), FactOrigin.EXPLICIT, symbol);
                var key = FacetKey.of(FacetKind.FIELD_LABEL, type, field.getName());
                fields.add(new EntitySummary.StructuralFieldRow(new EntitySummary.FieldIdentity(type, null, field.getName()),
                    FacetKey.of(FacetKind.FIELD_STRUCTURE, type, field.getName()), EntityExplorerDensityFixture.class,
                    String.class, new EntitySummary.TypeFact(field.getResolvedType(), field.getTypeOrigin(), symbol),
                    label, new EntitySummary.IntFact(field.getOrder(), FactOrigin.DERIVED, symbol),
                    new EntitySummary.FlagFact(false, FactOrigin.DERIVED, symbol),
                    new EntitySummary.FlagFact(false, FactOrigin.DERIVED, symbol),
                    new EntitySummary.FlagFact(false, FactOrigin.PLATFORM_DEFAULT, ""),
                    new EntitySummary.FlagFact(false, FactOrigin.PLATFORM_DEFAULT, ""),
                    List.of(new EntitySummary.SizeConstraint(0, 200, List.of(), ResolvedValue.fact("@Size", FactOrigin.BEAN_VALIDATION, symbol))),
                    new EntitySummary.StorageFacts(List.of(), null),
                    List.of(new EntitySummary.FieldApplication(EntitySummary.FieldApplicationKind.FORM, key, field.getName(), label, field.getOrder())), null));
            }
            return new EntitySummary(summary.entityClass(), summary.simpleName(),
                ResolvedValue.fact(summary.displayName().value() + " · fixture 30", FactOrigin.DERIVED, ""),
                summary.overview(), summary.fieldsForm(), summary.fieldsGrid(), summary.listColumns(), summary.selectColumns(),
                summary.tableSections(), summary.forms(), summary.contextFilters(), summary.selections(), summary.references(),
                summary.numbering(), summary.lifecycle(), summary.diagnostics(), summary.actions(), summary.readPlans(),
                summary.accessRows(), summary.lookupTargets(), summary.readPlanInspectionAvailable(),
                new EntitySummary.StructureFacts(true, fields, summary.structure().sections(), summary.structure().presentation()));
        }
    }
}
