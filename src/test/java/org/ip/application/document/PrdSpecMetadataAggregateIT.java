package org.ip.application.document;

import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecMtr;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.JournalRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.PrdSpecRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.crud.ValidationException;
import org.ipro.events.EventSource;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.crud.ServiceLocator;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behavioural parity of the PrdSpec pilot on the metadata-driven path. */
@SpringBootTest
class PrdSpecMetadataAggregateIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private MetadataDrivenAggregateSaveService aggregateSaveService;

    @Autowired
    private GenericOwnedSectionService sectionService;

    @Autowired
    private SectionMetadataRegistry sectionRegistry;

    @Autowired
    private PrdSpecRepository prdSpecRepository;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private UnitOfMeasurementRepository unitRepository;

    @Autowired
    private NomenclatureRepository nomenclatureRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    /** C4.6 волна E: у {@code PrdSpec} больше нет typed-сервиса — форма идёт canonical handle. */
    @Autowired
    private ServiceLocator serviceLocator;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void absentLeavesRowsUntouchedAndMinRowsRejectsExplicitClear() {
        String suffix = suffix();
        PrdSpec spec = newSpecWithReferences(suffix);
        PrdSpecMtr row = material(1);

        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> aggregateSaveService.save(spec,
            List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                PrdSpecMtr.class, List.of(row))), EventSource.SYSTEM));
        Long existingRowId = rowsFor(spec).getFirst().getId();

        RlsTestFixture.runAsSuperuser(accessGrantRepository,
            () -> aggregateSaveService.save(spec, List.of(), EventSource.SYSTEM));
        assertThat(rowsFor(spec)).singleElement()
            .extracting(PrdSpecMtr::getId).isEqualTo(existingRowId);

        assertThatThrownBy(() -> RlsTestFixture.runAsSuperuser(accessGrantRepository,
            () -> aggregateSaveService.save(spec,
                List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    PrdSpecMtr.class, List.of())), EventSource.SYSTEM)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("минимум 1");
        assertThat(rowsFor(spec)).singleElement()
            .extracting(PrdSpecMtr::getId).isEqualTo(existingRowId);
    }

    @Test
    void rlsStillProtectsAggregateSaveAndDelete() {
        String suffix = suffix();
        PrdSpec unsaved = newSpecWithReferences(suffix + "s");
        Journal allowedJournal = persistJournal("J-ALLOWED-" + suffix);
        persistGrant("metadata-save-user", allowedJournal.getId(), true, true, false);
        loginAs("metadata-save-user");

        assertThatThrownBy(() -> aggregateSaveService.save(
                unsaved, List.of(), EventSource.UI))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Нет прав на изменение")
            .hasMessageContaining("JOURNAL");
        assertThat(prdSpecRepository.findAll())
            .noneMatch(saved -> unsaved.getCodeSpec().equals(saved.getCodeSpec()));

        SecurityContextHolder.clearContext();
        PrdSpec persisted = RlsTestFixture.callAsSuperuser(accessGrantRepository, () ->
            aggregateSaveService.save(newSpecWithReferences(suffix + "d"),
                List.of(), EventSource.SYSTEM).aggregate());
        persistGrant("metadata-delete-user", persisted.getJournal().getId(), true, true, false);
        loginAs("metadata-delete-user");

        assertThatThrownBy(() -> serviceLocator.findService(PrdSpec.class).delete(persisted.getId()))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("Нет прав на удаление")
            .hasMessageContaining("JOURNAL");
        assertThat(prdSpecRepository.findById(persisted.getId())).isPresent();
    }

    @Test
    void crossValidationListenerVetoesBeforePersistence() {
        String suffix = suffix();
        PrdSpec spec = newSpecWithReferences(suffix);
        PrdSpecMtr selfComponent = material(1);
        selfComponent.setNomenclature(spec.getNomenclature());

        assertThatThrownBy(() -> RlsTestFixture.runAsSuperuser(accessGrantRepository,
            () -> aggregateSaveService.save(spec,
                List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    PrdSpecMtr.class, List.of(selfComponent))), EventSource.UI)))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("собираемой единицей");
        List<PrdSpec> persistedSpecs = RlsTestFixture.callAsSuperuser(
            accessGrantRepository, () -> prdSpecRepository.findAll());
        assertThat(persistedSpecs)
            .noneMatch(saved -> spec.getCodeSpec().equals(saved.getCodeSpec()));
    }

    private PrdSpecMtr material(int type) {
        PrdSpecMtr row = new PrdSpecMtr();
        row.setTypeMtr(type);
        return row;
    }

    private List<PrdSpecMtr> rowsFor(PrdSpec spec) {
        TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(PrdSpecMtr.class)
            .orElseThrow();
        return RlsTestFixture.callAsSuperuser(accessGrantRepository,
            () -> sectionService.findByParent(spec, descriptor));
    }

    private PrdSpec newSpecWithReferences(String suffix) {
        return RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            Journal journal = persistJournal("J-" + suffix);
            // Keep the generated code within the domain's length limit while
            // retaining the distinguishing suffix character used by tests
            // that create several reference sets from one base token.
            String token = suffix.substring(0, Math.min(6, suffix.length()))
                + suffix.substring(Math.max(0, suffix.length() - 1));
            UnitOfMeasurement unit = new UnitOfMeasurement();
            unit.setCode("U" + token);
            unit.setShortCode(token);
            unit.setName("Unit " + suffix);
            unitRepository.save(unit);

            Nomenclature nomenclature = new Nomenclature();
            nomenclature.setCode("N-" + suffix);
            nomenclature.setName("Part " + suffix);
            nomenclature.setUnitOfMeasurement(unit);
            nomenclatureRepository.save(nomenclature);

            PrdSpec spec = new PrdSpec();
            spec.setJournal(journal);
            spec.setNomenclature(nomenclature);
            spec.setCodeSpec("SPEC-" + suffix);
            return spec;
        });
    }

    private Journal persistJournal(String code) {
        return RlsTestFixture.callAsSuperuser(accessGrantRepository, () -> {
            Journal journal = new Journal();
            journal.setCode(code);
            journal.setName("Journal " + code);
            return journalRepository.save(journal);
        });
    }

    private void persistGrant(String username, Long journalId,
                              boolean read, boolean update, boolean delete) {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            AccessGrant grant = new AccessGrant();
            grant.setSubjectType(AccessGrant.SubjectType.USER);
            grant.setSubjectKey(username);
            grant.setDimension("JOURNAL");
            grant.setDimensionValueId(journalId);
            grant.setCanRead(read);
            grant.setCanUpdate(update);
            grant.setCanDelete(delete);
            accessGrantRepository.save(grant);
        });
    }

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
