package org.ip.views.forms;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.VaadinSessionState;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.*;
import org.ipro.data.EntityDataAccess;
import org.ipro.form.FormSaveResult;
import org.ipro.form.FieldFactory;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.FormBehavior;
import org.ipro.form.builder.ItemFormCustomization;
import org.ipro.form.builder.ItemFormVariants;
import org.ipro.form.builder.layout.FormAdjust;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.form.registry.FormResolver;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** K3: real resolver/coordinator, real persistence; only the modal window is replaced in tests. */
@SpringBootTest(classes=Application.class)
@Import(WorkshopItemFormTest.Observer.class)
class WorkshopItemFormTest {
    @MockitoBean DataInitializer initializer;
    @Autowired FormResolver resolver;
    @Autowired ObjectProvider<FormCoordinator> coordinators;
    @Autowired MetadataResolver metadata;
    @Autowired FieldFactory fields;
    @Autowired EntityDataAccess access;
    @Autowired AccessGrantRepository grants;
    private UI previousUi, currentUi;
    private VaadinSession previousSession;
    static final AtomicReference<ItemForm<Workshop>> lastBuilt = new AtomicReference<>();

    @TestConfiguration(proxyBeanMethods=false)
    static class Observer {
        @Bean ItemFormCustomization workshopProbe() {
            return new ItemFormCustomization() {
                public Class<?> entityClass() { return Workshop.class; }
                public void configure(ItemFormVariants variants) {
                    variants.add("test-fin", FinForm.class, context -> new FinForm());
                    variants.customizeDefault((form, context) -> {
                        assertThat(form.getField("code")).isNotNull();
                        assertThat(form.getField("name")).isNotNull();
                        assertThat(form.getField("branch")).isNotNull();
                        assertThat(form.peekEntity()).isNull();
                        @SuppressWarnings("unchecked") var typed = (ItemForm<Workshop>)form;
                        lastBuilt.set(typed);
                    });
                }
            };
        }
    }
    /** The demonstration rule exists only on the test classpath and in a named test variant. */
    public static class FinForm extends WorkshopItemForm {
        int changes, loads;
        @Override protected void adjust(FormAdjust a) { super.adjust(a); a.element("name").setWidth("24em"); }
        @Override protected void behavior(FormBehavior b) {
            b.onChange("name", event -> { changes++; if (String.valueOf(event.value()).contains("Фин")) b.setReadOnly(true); });
            b.onLoaded(() -> loads++);
        }
    }
    @BeforeEach void bindUiAndPermissions() {
        String username = RlsTestFixture.authenticateAsSuperuser(grants);
        RlsTestFixture.grantC5EntityRead(grants, username, Workshop.class, Branch.class, Role.class, User.class);
        previousUi=UI.getCurrent(); previousSession=VaadinSession.getCurrent();
        var session=mock(VaadinSession.class);
        when(session.getState()).thenReturn(VaadinSessionState.OPEN);
        when(session.hasLock()).thenReturn(true);
        when(session.getLockInstance()).thenReturn(new ReentrantLock());
        Map<Class<?>,Object> attributes=new HashMap<>();
        when(session.getAttribute(any(Class.class))).thenAnswer(call -> attributes.get(call.getArgument(0)));
        doAnswer(call -> { attributes.put(call.getArgument(0),call.getArgument(1)); return null; }).when(session).setAttribute(any(Class.class),any());
        currentUi=new UI(); currentUi.getInternals().setSession(session);
        VaadinSession.setCurrent(session); UI.setCurrent(currentUi); lastBuilt.set(null);
    }
    @AfterEach void restore() {
        UI.setCurrent(previousUi); VaadinSession.setCurrent(previousSession); SecurityContextHolder.clearContext(); lastBuilt.set(null);
    }
    private ItemForm<Workshop> open(String variant, Long id, Map<String,Object> parameters) {
        try(var dialogs=mockConstruction(Dialog.class)) {
            coordinators.getObject().openItemForm(Workshop.class,variant,id,saved -> {},parameters);
            assertThat(dialogs.constructed()).hasSize(1);
            verify(dialogs.constructed().getFirst()).setHeight("auto");
            return Objects.requireNonNull(lastBuilt.get());
        }
    }
    @Test void defaultCardHasThreeOrdinaryFieldsAndRemainsIndependentAcrossOpenings() {
        var first=open(null,null,Map.of());
        assertThat(first).isExactlyInstanceOf(WorkshopItemForm.class);
        assertThat(first.getBindingRegistry().getAll()).extracting(binding -> binding.getFieldName()).containsExactly("code","name","branch");
        assertThat(first.getBindingRegistry().getAll()).extracting(binding -> binding.getDescriptor().label()).containsExactly("Код","Наименование","Филиал");
        var name=first.getField("name");
        first.buildDescription(metadata.resolve(Workshop.class),fields);
        assertThat(first.getField("name")).isSameAs(name);
        first.getEntityField("name",String.class).setValue("Фин — обычное наименование");
        assertThat(first.isReadOnly()).isFalse();
        var second=open(null,null,Map.of());
        assertThat(second).isNotSameAs(first);
        assertThat(second.getField("name")).isNotSameAs(name);
        assertThat(second.getEntityField("name",String.class).getValue()).isEmpty();
    }
    @Test void behaviorIgnoresInitializationAndPersistedPopulationButRespondsToInput() {
        var form=(FinForm)open("test-fin",null,Map.of("initialValues",Map.of("code","AF55-F","name","Фин при открытии")));
        assertThat(form.getEntity().getName()).isEqualTo("Фин при открытии");
        assertThat(form.changes).isZero(); assertThat(form.loads).isEqualTo(1);
        assertThat(form.isReadOnly()).isFalse();
        var saved=new Workshop("AF55-F","Фин после записи"); saved.setId(55L);
        form.applyPersistedEntity(saved);
        assertThat(form.changes).isZero(); assertThat(form.loads).isEqualTo(2);
        assertThat(form.isReadOnly()).isFalse();
        form.getEntityField("name",String.class).setValue("Фин при вводе");
        assertThat(form.changes).isEqualTo(1); assertThat(form.isReadOnly()).isTrue();
        assertThat(form.isDirty()).isTrue();
    }
    @Test void requiredDirtySaveAndReadBackUseTheNormalCoordinatorAndSaveHandler() {
        var form=open(null,null,Map.of());
        assertThat(form.isDirty()).isFalse();
        assertThat(form.validate()).anyMatch(message -> message.startsWith("Код:"))
            .anyMatch(message -> message.startsWith("Наименование:"));
        String code="AF55-"+UUID.randomUUID().toString().substring(0,8);
        form.getEntityField("code",String.class).setValue(code);
        form.getEntityField("name",String.class).setValue("Пилот AF5.5");
        assertThat(form.validate()).isEmpty(); assertThat(form.isDirty()).isTrue();
        var result=form.save();
        assertThat(result).isInstanceOf(FormSaveResult.Success.class);
        var saved=((FormSaveResult.Success<Workshop>)result).saved();
        assertThat(saved.getId()).isNotNull(); assertThat(form.isDirty()).isFalse();
        var reopened=open(null,saved.getId(),Map.of());
        assertThat(reopened.getEntity().getCode()).isEqualTo(code);
        assertThat(reopened.getEntity().getName()).isEqualTo("Пилот AF5.5");
        assertThat(reopened.isDirty()).isFalse();
        var readonly=open(null,saved.getId(),Map.of(FormCoordinator.READ_ONLY_PARAMETER,true));
        assertThat(readonly.isReadOnly()).isTrue();
        assertThat(readonly.getEntityField("name",String.class).isReadOnly()).isTrue();
    }
    @Test void oldConstructorFormsStillBuildInTheSameContext() {
        assertThat(resolver.resolveItemForm(User.class,null,null,null)).isInstanceOf(UserItemForm.class);
        var correction=resolver.resolveItemForm(AccountingObjectCorrection.class,null,null,null);
        assertThat(correction).isInstanceOf(AccountingObjectCorrectionFormConfig.CorrectionForm.class);
        assertThat(correction.getEntityField("date",java.time.LocalDateTime.class)).isNotNull();
    }
}
