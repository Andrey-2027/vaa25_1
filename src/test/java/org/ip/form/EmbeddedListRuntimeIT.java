package org.ip.form;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinSessionState;
import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.model.ReceivingDocument;
import org.ip.model.ReceivingDocumentItem;
import org.ip.model.Nomenclature;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EventSource;
import org.ipro.form.FieldFactory;
import org.ipro.form.FormSaveHandler;
import org.ipro.form.FormSaveResult;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.builder.layout.ItemFormLayout;
import org.ipro.form.builder.layout.Layout;
import org.ipro.form.registry.EmbeddedListFactory;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionHandler;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.ActionInvocation;
import org.ipro.form.action.ActionRequirement;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.form.builder.layout.FormAdjust;
import org.ipro.form.builder.layout.SelectionValue;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.GridViewState;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Test-only card: real UI-scoped factory, read gate, aggregate save and LIST readers. */
@SpringBootTest(classes=Application.class)
@Transactional
@Import(EmbeddedListRuntimeIT.Commands.class)
class EmbeddedListRuntimeIT {
    @MockitoBean DataInitializer initializer;
    @Autowired EmbeddedListFactory factory;
    @Autowired MetadataResolver metadata;
    @Autowired FieldFactory fields;
    @Autowired FormSaveHandler saveHandler;
    @Autowired EntityDataAccess data;
    @Autowired MetadataDrivenAggregateSaveService aggregates;
    @Autowired AccessGrantRepository grants;
    @Autowired EntityManager em;
    @Autowired ActionProbe action;
    @Autowired org.ipro.form.registry.FormResolver forms;
    private UI ui, previousUi;
    private VaadinSession previousSession;
    private int created;
    private ListForm<?,?> lastList;
    private String suffix;
    private static final ActionId INSPECT=ActionId.of("af6.test.inspect-context");

    static class ActionProbe implements ActionHandler {
        ActionInvocation last;
        ItemForm<ReceivingDocument> child;
        @Override public ActionDefinition definition() {
            return ActionDefinition.forEntity(INSPECT,ActionSurface.LIST_TOOLBAR,ReceivingDocument.class,
                "Test inspect","INFO",50,ActionRequirement.none());
        }
        @Override public void execute(ActionInvocation invocation) { last=invocation; }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Commands {
        @Bean ActionProbe actionProbe() { return new ActionProbe(); }
        @Bean ItemFormCustomization childObserver(ActionProbe probe) {
            return new ItemFormCustomization() {
                @Override public Class<?> entityClass() { return ReceivingDocument.class; }
                @Override @SuppressWarnings("unchecked") public void configure(ItemFormVariants variants) {
                    variants.customizeDefault((form,context)->probe.child=(ItemForm<ReceivingDocument>)form);
                }
            };
        }
    }

    static class Probe extends ItemForm<Workshop> {
        @Override protected ItemFormLayout layout(Layout l) {
            return l.of(l.tabs("workshop-tabs").add(l.tab("main").add(l.remainingFields()),
                l.tab("incoming-tab").add(l.list("incoming",ReceivingDocument.class)
                    .setFilter("receivingWorkshop",l.self()))));
        }
    }
    static class CommandsProbe extends Probe {
        @Override protected void adjust(FormAdjust a) {
            a.element("incoming").commands(c->{
                c.commands(CrudAction.CREATE.id(),CrudAction.REFRESH.id(),INSPECT);
                c.command(CrudAction.CREATE.id()).title("Создать здесь");
                c.command(INSPECT).title("Проверить контекст");
            });
        }
    }
    static class SourceProbe extends Probe {
        @Override protected void adjust(FormAdjust a) {
            a.element("incoming").setFilter("number",new SelectionValue.FieldValue("name"));
        }
    }

    @BeforeEach void setup() {
        String user=RlsTestFixture.authenticateAsSuperuser(grants);
        RlsTestFixture.grantC5EntityRead(grants,user,Workshop.class,ReceivingDocument.class,Journal.class,
            ReceivingDocumentItem.class,Nomenclature.class,UnitOfMeasurement.class);
        suffix=UUID.randomUUID().toString().substring(0,8);
        action.last=null; action.child=null;
        previousUi=UI.getCurrent(); previousSession=VaadinSession.getCurrent();
        var session=mock(VaadinSession.class);
        when(session.getState()).thenReturn(VaadinSessionState.OPEN);
        when(session.hasLock()).thenReturn(true); when(session.getLockInstance()).thenReturn(new ReentrantLock());
        Map<Class<?>,Object> attributes=new HashMap<>();
        when(session.getAttribute(any(Class.class))).thenAnswer(call->attributes.get(call.getArgument(0)));
        doAnswer(call->{ attributes.put(call.getArgument(0),call.getArgument(1)); return null; })
            .when(session).setAttribute(any(Class.class),any());
        ui=new UI(); ui.getInternals().setSession(session); UI.setCurrent(ui); VaadinSession.setCurrent(session);
    }
    @AfterEach void restore() {
        UI.setCurrent(previousUi); VaadinSession.setCurrent(previousSession); SecurityContextHolder.clearContext();
    }
    private Probe card() {
        return card(new Probe());
    }
    private <P extends Probe> P card(P form) {
        form.setEmbeddedListFactory(new EmbeddedListFactory() {
            @Override public boolean canRead(Class<?> type) { return factory.canRead(type); }
            @Override public ListForm<?,?> create(Class<? extends IdentifiableEntity<Long>> type,
                    String selectedVariant,Map<String,Object> filters) {
                created++; return lastList=factory.create(type,selectedVariant,filters);
            }
        });
        form.buildDescription(metadata.resolve(Workshop.class),fields);
        form.setSaveHandler(saveHandler);
        return form;
    }
    private TabSheet tabs(Probe form) { return (TabSheet)form.getFormLayout().getChildren().findFirst().orElseThrow(); }

    @Test void firstRealSaveActivatesOnlyTheVisibleListAndKeepsParentClean() {
        var form=card(); form.initializeNewEntity(Map.of("code","AF6-"+suffix,"name","AF6 Workshop"));
        ui.add(form); tabs(form).setSelectedIndex(1); assertThat(created).isZero();
        assertThat(form.save()).isInstanceOf(FormSaveResult.Success.class);
        assertThat(form.peekEntity().getId()).isNotNull(); assertThat(created).isEqualTo(1);
        assertThat(lastList.getOpeningContextFilters()).containsEntry("receivingWorkshop",form.peekEntity());
        assertThat(form.isDirty()).isFalse();
        tabs(form).setSelectedIndex(0); tabs(form).setSelectedIndex(1); assertThat(created).isEqualTo(1);
        lastList.refresh(); assertThat(form.isDirty()).isFalse();
    }

    @Test @SuppressWarnings("unchecked")
    void actualListReaderReturnsOnlyIncomingDocumentsForTheCurrentParent() {
        var first=data.save(Workshop.class,new Workshop("A"+suffix,"First"));
        var second=data.save(Workshop.class,new Workshop("B"+suffix,"Second"));
        var journal=new Journal(); journal.setCode("J"+suffix); journal.setName("AF6 Journal");
        journal=data.save(Journal.class,journal);
        var incoming=document(first,second,journal);
        var outgoing=document(second,first,journal);
        em.flush();
        var form=card(); form.setEntity(first); ui.add(form); assertThat(created).isZero();
        tabs(form).setSelectedIndex(1); assertThat(created).isEqualTo(1);
        var list=(ListForm<ReceivingDocument,Long>)lastList;
        var rows=list.getGrid().getDataProvider().fetch(new Query<>()).toList();
        assertThat(rows).extracting(ReceivingDocument::getId).contains(incoming.getId()).doesNotContain(outgoing.getId());
        assertThat(rows).allMatch(row->row.getReceivingWorkshop().getId().equals(first.getId()));
        assertThat(list.getRequiredContextValues()).containsEntry("receivingWorkshop",first);
        form.setEntity(second); assertThat(created).isEqualTo(2);
        var other=(ListForm<ReceivingDocument,Long>)lastList;
        assertThat(other.getGrid().getDataProvider().fetch(new Query<>()).toList())
            .extracting(ReceivingDocument::getId).contains(outgoing.getId()).doesNotContain(incoming.getId());
        assertThat(form.isDirty()).isFalse();
    }

    private ReceivingDocument document(Workshop receiving,Workshop transferring,Journal journal) {
        var value=new ReceivingDocument(); value.setDate(LocalDate.of(2026,11,8));
        value.setJournal(journal); value.setReceivingWorkshop(receiving); value.setTransferringWorkshop(transferring);
        return aggregates.save(value,List.of(),EventSource.SYSTEM).aggregate();
    }

    @Test void missingRealEntityReadGrantHidesTheTabAndRejectsDirectFactoryCreation() {
        var parent=data.save(Workshop.class,new Workshop("D"+suffix,"Denied-list parent"));
        String denied="af6-no-document-read-"+suffix;
        RlsTestFixture.grantC5EntityRead(grants,denied,Workshop.class);
        SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(denied,"n/a",List.of()));
        assertThat(factory.canRead(ReceivingDocument.class)).isFalse();
        var form=card(); form.setEntity(parent); ui.add(form);
        assertThat(tabs(form).getTabAt(1).isVisible()).isFalse(); assertThat(created).isZero();
        assertThat(lastList).isNull();
        assertThatThrownBy(()->factory.create(ReceivingDocument.class,null,Map.of("receivingWorkshop",parent)))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void scopedCommandsUseTheDeclaredActionAndLeaveDefaultListPresentationAlone() {
        var parent=data.save(Workshop.class,new Workshop("C"+suffix,"Commands"));
        var form=card(new CommandsProbe()); form.setEntity(parent); ui.add(form); tabs(form).setSelectedIndex(1);
        assertThat(lastList.getAddButton().getText()).isEqualTo("Создать здесь");
        var button=buttons(lastList).filter(b->"Проверить контекст".equals(b.getText())).findFirst().orElseThrow();
        button.click(); assertThat(action.last).isNotNull();
        assertThat(action.last.contextFilter("receivingWorkshop")).isEqualTo(parent);
        var ordinary=factory.create(ReceivingDocument.class,null,Map.of("receivingWorkshop",parent));
        assertThat(ordinary.getAddButton().getText()).isNotEqualTo("Создать здесь");
        assertThat(buttons(ordinary).map(com.vaadin.flow.component.button.Button::getText)).contains("Test inspect");
        assertThat(form.isDirty()).isFalse();
    }

    @Test @SuppressWarnings("unchecked") void savedViewResetAndCreationKeepTheParentAndJournal() {
        var first=data.save(Workshop.class,new Workshop("V"+suffix,"View first"));
        var second=data.save(Workshop.class,new Workshop("W"+suffix,"View second"));
        var journal=new Journal(); journal.setCode("J"+suffix); journal.setName("View journal"); journal=data.save(Journal.class,journal);
        var incoming=document(first,second,journal); document(second,first,journal); em.flush();
        var form=card(); form.setEntity(first); ui.add(form); tabs(form).setSelectedIndex(1);
        var list=(ListForm<ReceivingDocument,Long>)lastList;
        list.setContextFilter("receivingWorkshop",second); list.clearContextFilter();
        list.applyViewState(new GridViewState(List.of(new ColumnPath.Spec("number",null)),List.of()));
        assertThat(list.getGrid().getDataProvider().fetch(new Query<>()).toList())
            .extracting(ReceivingDocument::getId).containsExactly(incoming.getId());
        var portable=list.captureViewState();
        assertThat(portable.toJson()).doesNotContain("receivingWorkshop");
        list.setContextFilter("journal",journal);
        try(var dialogs=mockConstruction(com.vaadin.flow.component.dialog.Dialog.class)) {
            list.getAddButton().click(); assertThat(dialogs.constructed()).hasSize(1);
            assertThat(action.child).isNotNull();
            assertThat(action.child.<Workshop>entityField("receivingWorkshop").getValue().getId()).isEqualTo(first.getId());
            assertThat(action.child.<Journal>entityField("journal").getValue().getId()).isEqualTo(journal.getId());
        }
        assertThat(form.isDirty()).isFalse();
    }

    @Test @SuppressWarnings("unchecked") void changingTheSourceEditorChangesTheActualListReadWithoutWritingTheParent() {
        var first=data.save(Workshop.class,new Workshop("S"+suffix,"Original source"));
        var second=data.save(Workshop.class,new Workshop("T"+suffix,"Other source"));
        var journal=new Journal(); journal.setCode("J"+suffix); journal.setName("Source journal"); journal=data.save(Journal.class,journal);
        var incoming=document(first,second,journal); em.flush();
        var form=card(new SourceProbe()); form.setEntity(first); ui.add(form); tabs(form).setSelectedIndex(1);
        var list=(ListForm<ReceivingDocument,Long>)lastList;
        assertThat(list.getGrid().getDataProvider().fetch(new Query<>()).toList()).isEmpty();
        form.getEntityField("name",String.class).setValue(incoming.getNumber());
        assertThat(list.getGrid().getDataProvider().fetch(new Query<>()).toList())
            .extracting(ReceivingDocument::getId).containsExactly(incoming.getId());
        assertThat(first.getName()).isEqualTo("Original source"); assertThat(form.isDirty()).isTrue();
        form.getEntityField("name",String.class).clear(); assertThat(list.getParent()).isEmpty();
    }

    private java.util.stream.Stream<com.vaadin.flow.component.button.Button> buttons(com.vaadin.flow.component.Component root) {
        return java.util.stream.Stream.concat(root instanceof com.vaadin.flow.component.button.Button button
            ? java.util.stream.Stream.of(button) : java.util.stream.Stream.empty(), root.getChildren().flatMap(this::buttons));
    }

    private java.util.stream.Stream<com.vaadin.flow.component.Component> components(com.vaadin.flow.component.Component root) {
        if(root instanceof TabSheet sheet) return java.util.stream.Stream.concat(java.util.stream.Stream.of(root),
            java.util.stream.IntStream.range(0,sheet.getTabCount())
                .mapToObj(index->sheet.getComponent(sheet.getTabAt(index))).flatMap(this::components));
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(root),root.getChildren().flatMap(this::components));
    }
    private ItemForm<Workshop> workshop(Workshop parent) {
        var form=forms.resolveItemForm(Workshop.class,null,null,Map.of());
        assertThat(form).isExactlyInstanceOf(org.ip.views.forms.WorkshopItemForm.class);
        form.setSaveHandler(saveHandler);
        if(parent==null) form.initializeNewEntity(Map.of("code","P"+suffix,"name","Pilot"));
        else form.setEntity(parent);
        ui.add(form); return form;
    }
    private TabSheet workshopTabs(ItemForm<Workshop> form) {
        return components(form).filter(TabSheet.class::isInstance).map(TabSheet.class::cast).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked") private ListForm<ReceivingDocument,Long> incoming(ItemForm<Workshop> form) {
        return (ListForm<ReceivingDocument,Long>)components(form).filter(ListForm.class::isInstance).findFirst().orElseThrow();
    }

    @Test void workshopPilotCreatesAndReadsBackAnIncomingDocumentWithoutDirtyingTheCard() {
        var parent=data.save(Workshop.class,new Workshop("P"+suffix,"Pilot receiving"));
        var sender=data.save(Workshop.class,new Workshop("Q"+suffix,"Pilot sender"));
        var journal=new Journal(); journal.setCode("P"+suffix); journal.setName("Pilot journal");
        journal=data.save(Journal.class,journal);
        var outgoing=document(sender,parent,journal); em.flush();
        var form=workshop(parent);
        assertThat(components(form).filter(ListForm.class::isInstance)).isEmpty();
        var tabs=workshopTabs(form);
        assertThat(tabs.getTabAt(0).getLabel()).isEqualTo("Основное");
        assertThat(tabs.getTabAt(1).getLabel()).isEqualTo("Входящие накладные");
        tabs.setSelectedIndex(1); var list=incoming(form);
        assertThat(list.getGrid().getColumns()).extracting(column->column.getKey())
            .containsExactly("number","date","journal","transferringWorkshop");
        assertThat(list.getAddButton().getText()).isEqualTo("Создать накладную");
        assertThat(list.getAddButton().isEnabled()).isFalse();
        assertThat(list.getGrid().getDataProvider().fetch(new Query<>()).toList()).isEmpty();
        list.setContextFilter("journal",journal);
        assertThat(list.getAddButton().isEnabled()).isTrue();
        Long childId;
        try(var dialogs=mockConstruction(com.vaadin.flow.component.dialog.Dialog.class)) {
            list.getAddButton().click(); assertThat(dialogs.constructed()).hasSize(1);
            var child=action.child; assertThat(child).isNotNull();
            assertThat(child.<Workshop>entityField("receivingWorkshop").getValue().getId()).isEqualTo(parent.getId());
            assertThat(child.<Journal>entityField("journal").getValue().getId()).isEqualTo(journal.getId());
            child.<Workshop>entityField("transferringWorkshop").setValue(sender);
            child.getEntityField("date",LocalDate.class).setValue(LocalDate.of(2026,11,8));
            var unit=data.save(UnitOfMeasurement.class,new UnitOfMeasurement(suffix.substring(0,4),"Pilot unit","U"+suffix));
            var material=data.save(Nomenclature.class,new Nomenclature("N"+suffix,"Pilot material",unit));
            var row=new ReceivingDocumentItem(material,java.math.BigDecimal.ONE);
            row.setDocument(child.peekEntity());
            var positions=child.<ReceivingDocumentItem>tableSection(ReceivingDocumentItem.class);
            positions.applyPersistedRows(child.peekEntity(),List.of(row)); positions.markDirty();
            var result=child.save(); assertThat(result).isInstanceOf(FormSaveResult.Success.class);
            assertThat(positions.getRows()).singleElement().satisfies(value->assertThat(value.getId()).isNotNull());
            childId=((FormSaveResult.Success<ReceivingDocument>)result).saved().getId();
        }
        em.flush(); list.refresh();
        assertThat(list.getGrid().getDataProvider().fetch(new Query<>()).toList())
            .extracting(ReceivingDocument::getId).containsExactly(childId).doesNotContain(outgoing.getId());
        list.clearContextFilter();
        list.applyViewState(new GridViewState(List.of(new ColumnPath.Spec("number",null)),List.of()));
        assertThat(list.getOpeningContextFilters()).containsEntry("receivingWorkshop",parent);
        assertThat(list.getGrid().getDataProvider().fetch(new Query<>()).toList())
            .extracting(ReceivingDocument::getId).containsExactly(childId);
        assertThat(list.getAddButton().isEnabled()).isFalse();
        var ordinary=factory.create(ReceivingDocument.class,null,Map.of());
        assertThat(ordinary.getGrid().getColumns()).extracting(column->column.getKey())
            .contains("receivingWorkshop.code","journal.code");
        assertThat(ordinary.getAddButton().getText()).isNotEqualTo("Создать накладную");
        assertThat(ordinary.captureViewState().toJson()).isNotEqualTo(list.captureViewState().toJson());
        assertThat(form.isDirty()).isFalse();
    }

    @Test void workshopPilotWaitsForFirstSaveAndReusesTheLazyList() {
        var form=workshop(null); var tabs=workshopTabs(form); tabs.setSelectedIndex(1);
        assertThat(components(form).filter(ListForm.class::isInstance)).isEmpty();
        assertThat(form.save()).isInstanceOf(FormSaveResult.Success.class);
        var list=incoming(form);
        assertThat(list.getOpeningContextFilters()).containsEntry("receivingWorkshop",form.peekEntity());
        tabs.setSelectedIndex(0); tabs.setSelectedIndex(1);
        assertThat(incoming(form)).isSameAs(list); assertThat(form.isDirty()).isFalse();
    }

    @Test void workshopPilotHidesIncomingTabWithoutDocumentReadPermission() {
        String denied="pilot-denied-"+suffix;
        RlsTestFixture.grantC5EntityRead(grants,denied,Workshop.class);
        SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(denied,"n/a",List.of()));
        var form=workshop(null);
        assertThat(workshopTabs(form).getTabAt(1).isVisible()).isFalse();
        assertThat(components(form).filter(ListForm.class::isInstance)).isEmpty();
    }
}
