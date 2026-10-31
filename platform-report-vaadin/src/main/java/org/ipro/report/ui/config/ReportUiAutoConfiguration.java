package org.ipro.report.ui.config;

import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.report.ui.ReportContextFactory;
import org.ipro.report.ui.print.ItemFormReportActions;
import org.ipro.report.ui.print.JrxmlRunLauncher;
import org.ipro.report.ui.print.ReportCardPrintCustomization;
import org.ipro.report.ui.print.ReportPrintAction;
import org.ipro.report.ui.unidr.UniDrCatalogGateway;
import org.ipro.report.ui.unidr.UniDrRunLauncher;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.unidr.query.UniDrQuerySourceService;
import org.ipro.unidr.query.UniDrReferenceCatalog;
import org.ipro.unidr.run.UniDrRunService;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.ipro.unidr.ui.UniDrQueryUiSupport;
import org.ipro.unidr.ui.UniDrRunDialogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Registers the report UI beans without component scanning.
 *
 * <p>The module lives under {@code org.ipro}, outside the application's {@code org.ip}
 * scan root. Beans are therefore declared explicitly, like every other extracted
 * platform module. UniDR ports are optional: a supply without the pilot simply has no
 * gateway/launcher beans, and the report UI hides the corresponding rows.</p>
 */
@AutoConfiguration
public class ReportUiAutoConfiguration {

    /** The application context's user/clock seam; the UI module only passes them through. */
    @Bean
    @ConditionalOnMissingBean
    public ReportContextFactory reportContextFactory(RlsCurrentUser currentUser) {
        return new ReportContextFactory(currentUser, java.time.Clock.systemDefaultZone());
    }

    /**
     * Какие типы отчётов включены конфигурацией: окно выбора типа и строки каталога
     * строятся из этого бина, а не из наличия портов. Приложение вправе подменить его
     * ({@code @ConditionalOnMissingBean}) — например, чтобы связать тип с профилем.
     *
     * <p>Умолчания повторяют умолчания условий: JR включён (его сервисы безусловны),
     * UniDR — нет ({@code app.unidr.enabled} включает пилот).</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public ReportTypeAvailability reportTypeAvailability(
            @Value("${app.jrxml.enabled:true}") boolean jrxmlEnabled,
            @Value("${app.unidr.enabled:false}") boolean unidrEnabled) {
        return new ReportTypeAvailability(jrxmlEnabled, unidrEnabled);
    }

    @Bean
    @ConditionalOnMissingBean
    public ItemFormReportActions.CardLauncher reportCardLauncher(
            JrxmlTemplateService jrTemplateService,
            ObjectProvider<JrxmlRunLauncher> jrRuns,
            ObjectProvider<UniDrCatalogGateway> uniDrFiles,
            ObjectProvider<UniDrRunLauncher> uniDrRuns) {
        return new ItemFormReportActions.CardLauncher(jrTemplateService,
                jrRuns.getIfAvailable(), uniDrFiles, uniDrRuns);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportCardPrintCustomization reportCardPrintCustomization(
            ItemFormReportActions.CardLauncher launchers,
            ReportContextFactory contextFactory) {
        return new ReportCardPrintCustomization(launchers, contextFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ReportPrintAction reportPrintAction(
            JrxmlTemplateService jrTemplateService,
            ObjectProvider<JrxmlRunLauncher> jrRuns,
            ReportContextFactory contextFactory,
            ObjectProvider<UniDrCatalogGateway> uniDrFiles,
            ObjectProvider<UniDrRunLauncher> uniDrRuns) {
        return new ReportPrintAction(jrTemplateService, jrRuns.getIfAvailable(),
                contextFactory, uniDrFiles, uniDrRuns);
    }

    /** UniDR editor wiring is present only when the application enabled the pilot. */
    @Bean
    @ConditionalOnProperty(prefix = "app.unidr", name = "enabled", havingValue = "true")
    @ConditionalOnBean(UniDrSourceCatalog.class)
    @ConditionalOnMissingBean
    public UniDrQueryUiSupport uniDrQueryUiSupport(
            org.ipro.query.ui.editor.QueryEditorAnalysisService analysis,
            org.ipro.query.ui.editor.QueryMetadataCatalogService metadata,
            org.ipro.query.ReportPreviewService preview,
            org.ipro.crud.EntityLookup lookup,
            org.ipro.form.SelectionFormAssembler selection,
            org.ipro.query.ui.selection.EntitySelection selectionPort,
            UniDrReferenceCatalog references,
            org.ipro.query.ReportQueryAssemblyService assembly,
            org.ipro.query.QueryBuilderMetadataCatalog constructors,
            ObjectProvider<org.ipro.query.register.RegisterSliceReportTemplates> slices,
            UniDrQuerySourceService sources,
            UniDrSourceCatalog catalog,
            jakarta.persistence.EntityManagerFactory entities) {
        return new UniDrQueryUiSupport(analysis, metadata, preview, lookup, selection,
                selectionPort, references, assembly, constructors, slices, sources, catalog, entities);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.unidr", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public UniDrRunDialogFactory uniDrRunDialogFactory(UniDrRunService runs,
                                                       UniDrReferenceCatalog references,
                                                       UniDrQueryUiSupport queries,
                                                       UniDrSourceCatalog catalog) {
        return new UniDrRunDialogFactory(runs, references, queries, catalog);
    }
}
