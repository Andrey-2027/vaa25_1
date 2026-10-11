package com.example.materialrequests.acceptance;

import com.example.materialrequests.config.DemoData;
import com.example.materialrequests.model.*;
import com.example.materialrequests.ui.SessionFormStores;
import org.ipro.data.EntityDataAccess;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsAccessDeniedException;
import org.springframework.security.access.AccessDeniedException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "consumer.demo-password=bootstrap-test-password")
@ActiveProfiles(resolver = ConsumerTestProfiles.class)
class ProtectedReadTest {
    @Autowired EntityDataAccess access;
    @Autowired AccessGrantRepository grants;
    @Autowired DemoData fixtures;
    @Autowired SessionFormStores stores;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagers;
    static void login(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void aliceAndBobReadOnlyTheirDepartmentIncludingDirectIds() {
        login("alice");
        var alice = access.list(MaterialRequest.class, null, PageRequest.of(0, 20)).getContent();
        assertThat(alice).singleElement().extracting(MaterialRequest::getDepartmentId).isEqualTo(1L);
        Long aliceId = alice.getFirst().getId();
        assertThat(access.detail(MaterialRequest.class, aliceId)).isPresent();
        login("bob");
        var bob = access.list(MaterialRequest.class, null, PageRequest.of(0, 20)).getContent();
        assertThat(bob).singleElement().extracting(MaterialRequest::getDepartmentId).isEqualTo(2L);
        assertThat(access.detail(MaterialRequest.class, aliceId)).isEmpty();
        assertThat(access.detail(MaterialRequest.class, Long.MAX_VALUE)).isEmpty();
        login("alice");
        assertThat(access.detail(MaterialRequest.class, bob.getFirst().getId())).isEmpty();
    }
    @Test void materialLookupIsSharedAndOwnedRowsHaveNoStandaloneHandle() {
        for (String user : List.of("alice", "bob")) {
            login(user);
            assertThat(access.lookup(Material.class, "Бумага", 10)).singleElement()
                    .extracting(Material::getCode).isEqualTo("MAT-001");
            assertThat(access.list(Material.class, null, PageRequest.of(0, 20))).hasSize(2);
            assertThatThrownBy(() -> access.detail(MaterialRequestLine.class, 1L))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("owned");
            assertThatThrownBy(() -> access.create(MaterialRequestLine.class, new MaterialRequestLine()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void serverRejectsReaderWritesAndEditorOutsideDepartment() {
        login("bob");
        Material existing = access.lookup(Material.class, "MAT-001", 10).getFirst();
        assertThatThrownBy(() -> access.create(Material.class, new Material("DENIED", "Отказ")))
                .isInstanceOf(AccessDeniedException.class);
        existing.setName("Не должно сохраниться");
        assertThatThrownBy(() -> access.update(Material.class, existing)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.delete(Material.class, existing.getId())).isInstanceOf(AccessDeniedException.class);
        MaterialRequest own = access.list(MaterialRequest.class, null, PageRequest.of(0, 10)).getContent().getFirst();
        assertThatThrownBy(() -> access.save(MaterialRequest.class, own)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.delete(MaterialRequest.class, own.getId())).isInstanceOf(AccessDeniedException.class);
        login("alice");
        var foreign = new MaterialRequest(); foreign.setNumber("DENIED-DEPARTMENT"); foreign.setDepartmentId(2L);
        assertThatThrownBy(() -> access.save(MaterialRequest.class, foreign)).isInstanceOf(RlsAccessDeniedException.class);
        assertThat(access.lookup(Material.class, "MAT-001", 10).getFirst().getName()).isEqualTo("Бумага");
    }
    @Test void unauthenticatedAndUnknownSubjectsCannotReadBusinessData() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> access.list(Material.class, null, PageRequest.of(0, 10)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("authenticated C5 subject");
        login("unknown");
        assertThatThrownBy(() -> access.detail(Material.class, 1L)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void fixtureSetupIsIdempotentAndRevokesItsOwnAuthority() {
        fixtures.run();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(grants.findAll()).noneMatch(g -> DemoData.SEED_SUBJECT.equals(g.getSubjectKey()));
        login("alice");
        assertThat(access.list(Material.class, null, PageRequest.of(0, 20))).hasSize(2);
        assertThat(access.list(MaterialRequest.class, null, PageRequest.of(0, 20))).hasSize(1);
    }
    @Test void flushBoundaryRejectsDirectJpaWriteEvenForEditor() {
        login("alice");
        var manager = entityManagers.createEntityManager();
        try {
            manager.getTransaction().begin();
            assertThatThrownBy(() -> {
                manager.persist(new Material("DIRECT-DENIED", "Прямая запись"));
                manager.flush();
            }).isInstanceOf(RlsAccessDeniedException.class);
        } finally {
            if (manager.getTransaction().isActive()) manager.getTransaction().rollback();
            manager.close();
        }
        assertThat(access.lookup(Material.class, "DIRECT-DENIED", 10)).isEmpty();
    }
    @Test void formStoresIsolatePersonalDataAndProtectSharedViewOwnership() {
        login("alice"); stores.put("test-key", "alice-value");
        var privateView = stores.createView("test-form", "Личное", "code", false);
        var sharedView = stores.createView("test-form", "Общее", "name", true);
        login("bob");
        assertThat(stores.get("test-key")).isEmpty();
        assertThat(stores.findById(privateView.id())).isEmpty();
        assertThat(stores.findVisibleViews("test-form")).containsExactly(sharedView);
        assertThatThrownBy(() -> stores.updateView(sharedView)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stores.deleteView(sharedView.id())).isInstanceOf(IllegalArgumentException.class);
        login("alice"); stores.remove("test-key"); stores.deleteView(privateView.id()); stores.deleteView(sharedView.id());
        assertThat(stores.get("test-key")).isEmpty();
    }
}
