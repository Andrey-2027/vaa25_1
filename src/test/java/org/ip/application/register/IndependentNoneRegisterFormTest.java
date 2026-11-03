package org.ip.application.register;

import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.g1r4fixture.UiNoneRate;
import org.ipro.data.EntityDataAccess;
import org.ipro.form.FormSaveResult;
import org.ip.application.form.ItemFormSaveDispatcher;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.registry.FormResolver;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.c5.C5PermissionKeys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=Application.class)
@Import(IndependentNoneRegisterFormTest.Entities.class)
class IndependentNoneRegisterFormTest {
    @TestConfiguration @EntityScan({"org.ip.model", "org.ip.g1r4fixture"}) static class Entities { }
    @MockitoBean DataInitializer initializer;
    @Autowired FormResolver resolver;
    @Autowired ItemFormSaveDispatcher dispatcher;
    @Autowired EntityDataAccess data;
    @Autowired AccessGrantRepository grants;
    private String user;
    private Long savedId;
    @BeforeEach void login() {
        user="none-"+UUID.randomUUID().toString().substring(0,8);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        for (var dimension : List.of(C5PermissionKeys.entity(UiNoneRate.class), C5PermissionKeys.entityOperations(UiNoneRate.class))) {
            var grant=new AccessGrant(); grant.setSubjectType(AccessGrant.SubjectType.USER); grant.setSubjectKey(user);
            grant.setDimension(dimension); grant.setCanRead(true); grant.setCanUpdate(true); grant.setCanDelete(true); grants.saveAndFlush(grant);
        }
    }
    @AfterEach void cleanup() {
        try {
            if (savedId!=null) data.delete(UiNoneRate.class,savedId);
            grants.deleteAll(grants.findAll().stream().filter(g -> user.equals(g.getSubjectKey())).toList());
        } finally { SecurityContextHolder.clearContext(); }
    }
    @SuppressWarnings({"unchecked","rawtypes"}) ItemForm<UiNoneRate> form() {
        ItemForm<UiNoneRate> form=resolver.resolveItemForm(UiNoneRate.class,null,null,null);
        form.setSaveHandler(f -> (FormSaveResult)dispatcher.save((ItemForm)f)); return form;
    }
    @Test void noneFormAllowsCrudLocksKeyAndHasNoTemporalModeOrSavedAsOf() {
        var form=form(); form.initializeNewEntity(Map.of("region",user,"rate",new BigDecimal("10")));
        var created=form.save(); assertThat(created).isInstanceOf(FormSaveResult.Success.class);
        UiNoneRate row=((FormSaveResult.Success<UiNoneRate>)created).saved(); savedId=row.getId();
        var edit=form(); edit.setEntity(data.detail(UiNoneRate.class,savedId).orElseThrow());
        assertThat(edit.getEntityField("region",String.class).isReadOnly()).isTrue();
        assertThat(edit.getEntityField("rate",BigDecimal.class).isReadOnly()).isFalse();
        edit.getEntityField("rate",BigDecimal.class).setValue(new BigDecimal("12"));
        assertThat(edit.save()).isInstanceOf(FormSaveResult.Success.class);
        UiNoneRate stored=data.detail(UiNoneRate.class,savedId).orElseThrow();
        assertThat(stored.getRate()).isEqualByComparingTo("12");
        stored.setRegion("changed"); stored.setRate(new BigDecimal("13"));
        assertThatThrownBy(() -> data.update(UiNoneRate.class,stored)).hasMessageContaining("REGISTER_KEY_CHANGE_REQUIRED");
        var list=resolver.<UiNoneRate,Long>resolveListForm(UiNoneRate.class,null,null);
        assertThat(list.captureViewState().options()).doesNotContainKeys("register.asOf", "register.mode");
        assertThat(descendants(list).filter(c -> c instanceof com.vaadin.flow.component.datetimepicker.DateTimePicker)).isEmpty();
        data.delete(UiNoneRate.class,savedId); assertThat(data.detail(UiNoneRate.class,savedId)).isEmpty(); savedId=null;
    }
    private static java.util.stream.Stream<com.vaadin.flow.component.Component> descendants(com.vaadin.flow.component.Component c) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(c), c.getChildren().flatMap(IndependentNoneRegisterFormTest::descendants));
    }
}
