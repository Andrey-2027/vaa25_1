package org.ipro.crud;

import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpec;
import org.ip.model.PrdSpecOper;
import org.ip.model.UnitOfMeasurement;
import org.ip.repository.JournalRepository;
import org.ip.repository.NomenclatureRepository;
import org.ip.repository.PrdSpecRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Integration proof of the metadata-driven section persistence slice (B3.3). */
@SpringBootTest(classes = org.ip.Application.class)
class GenericOwnedSectionServiceIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private GenericOwnedSectionService sectionService;

    @Autowired
    private SectionMetadataRegistry sectionRegistry;

    @Autowired
    private JournalRepository journalRepository;

    @Autowired
    private UnitOfMeasurementRepository unitRepository;

    @Autowired
    private NomenclatureRepository nomenclatureRepository;

    @Autowired
    private PrdSpecRepository prdSpecRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Test
    void replaceAllUsesDescriptorForLinkingLineNumbersUpdateAndDelete() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Journal journal = journalRepository.save(journal("J-" + suffix));
            UnitOfMeasurement unit = unitRepository.save(
                new UnitOfMeasurement("u" + suffix.substring(0, 3), "Unit " + suffix, "U" + suffix));
            Nomenclature nomenclature = nomenclatureRepository.save(
                new Nomenclature("N-" + suffix, "Nom " + suffix, unit));
            PrdSpec spec = new PrdSpec();
            spec.setJournal(journal);
            spec.setNomenclature(nomenclature);
            spec.setCodeSpec("SPEC-" + suffix);
            spec = prdSpecRepository.save(spec);

            TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(PrdSpecOper.class)
                .orElseThrow();
            PrdSpecOper first = sectionService.createNew(spec, descriptor);
            first.setRoute("first");
            PrdSpecOper second = sectionService.createNew(spec, descriptor);
            second.setRoute("second");

            sectionService.replaceAll(spec, List.of(first, second), descriptor);
            List<PrdSpecOper> saved = sectionService.findByParent(spec, descriptor);
            assertThat(saved).hasSize(2);
            assertThat(saved).extracting(PrdSpecOper::getOrder).containsExactly(1, 2);
            assertThat(saved).extracting(PrdSpecOper::getPrdSpec)
                .allMatch(spec::equals);

            // Aggregate version contract: sequential saves use the fresh owner state.
            PrdSpec freshSpec = prdSpecRepository.findById(spec.getId()).orElseThrow();
            freshSpec.setJournal(journal);
            PrdSpecOper retained = saved.get(1);
            retained.setRoute("updated");
            sectionService.replaceAll(freshSpec, List.of(retained), descriptor);
            List<PrdSpecOper> afterReplace = sectionService.findByParent(spec, descriptor);
            assertThat(afterReplace).hasSize(1);
            assertThat(afterReplace.getFirst().getId()).isEqualTo(retained.getId());
            assertThat(afterReplace.getFirst().getOrder()).isEqualTo(1);
            assertThat(afterReplace.getFirst().getRoute()).isEqualTo("updated");

            PrdSpec clearOwner = prdSpecRepository.findById(spec.getId()).orElseThrow();
            clearOwner.setJournal(journal);
            sectionService.deleteAll(clearOwner, descriptor);
            assertThat(sectionService.findByParent(freshSpec, descriptor)).isEmpty();
        });
    }

    @Test
    void minRowsIsEnforcedBeforePersistence() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Journal journal = journalRepository.save(journal("J-" + suffix));
            UnitOfMeasurement unit = unitRepository.save(
                new UnitOfMeasurement("u" + suffix.substring(0, 3), "Unit " + suffix, "U" + suffix));
            Nomenclature nomenclature = nomenclatureRepository.save(
                new Nomenclature("N-" + suffix, "Nom " + suffix, unit));
            PrdSpec spec = new PrdSpec();
            spec.setJournal(journal);
            spec.setNomenclature(nomenclature);
            spec.setCodeSpec("SPEC-" + suffix);
            spec = prdSpecRepository.save(spec);
            TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(PrdSpecOper.class)
                .orElseThrow();

            PrdSpec finalSpec = spec;
            assertThatThrownBy(() -> sectionService.replaceAll(finalSpec, List.of(), descriptor))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("минимум 1");
            assertThatThrownBy(() -> sectionService.replaceAll(finalSpec, null, descriptor))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("null rows");
        });
    }

    @Test
    void rowOwnedByAnotherRootCannotBeMovedThroughReplaceAll() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            PrdSpec firstSpec = createSpec("A-" + suffix);
            PrdSpec secondSpec = createSpec("B-" + suffix);
            TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(PrdSpecOper.class)
                .orElseThrow();

            PrdSpecOper row = sectionService.createNew(firstSpec, descriptor);
            row.setRoute("owned-by-first");
            sectionService.replaceAll(firstSpec, List.of(row), descriptor);
            PrdSpecOper persisted = sectionService
                .<PrdSpecOper, PrdSpec>findByParent(firstSpec, descriptor).getFirst();

            assertThatThrownBy(() -> sectionService.replaceAll(secondSpec, List.of(persisted), descriptor))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("принадлежит другому документу");
        });
    }

    @Test
    void sectionOnlySaveBumpsAggregateVersion() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            PrdSpec spec = createSpec("V-" + UUID.randomUUID().toString().substring(0, 8));
            Long initialVersion = versionOf(spec.getId());
            TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(PrdSpecOper.class)
                .orElseThrow();

            PrdSpecOper row = sectionService.createNew(spec, descriptor);
            row.setRoute("version-bump");
            sectionService.replaceAll(spec, List.of(row), descriptor);

            assertThat(versionOf(spec.getId())).isGreaterThan(initialVersion);
        });
    }

    @Test
    void staleCompositionSaveConflictsInsteadOfSilentlyDeleting() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Journal journal = journalRepository.save(journal("J-" + suffix));
            UnitOfMeasurement unit = unitRepository.save(
                new UnitOfMeasurement("u" + suffix.substring(0, 3), "Unit " + suffix,
                    "U" + suffix));
            Nomenclature nomenclature = nomenclatureRepository.save(
                new Nomenclature("N-" + suffix, "Nom " + suffix, unit));
            PrdSpec spec = new PrdSpec();
            spec.setJournal(journal);
            spec.setNomenclature(nomenclature);
            spec.setCodeSpec("SPEC-" + suffix);
            spec = prdSpecRepository.save(spec);
            final PrdSpec staleOwner = spec;
            TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(PrdSpecOper.class)
                .orElseThrow();

            PrdSpecOper first = sectionService.createNew(spec, descriptor);
            first.setRoute("first");
            sectionService.replaceAll(spec, List.of(first), descriptor);

            // Concurrent saver commits a new row on the fresh version.
            // The reloaded owner gets its materialized associations back, as a
            // detached client DTO would carry them (RLS reads journal.id).
            PrdSpec fresh = prdSpecRepository.findById(spec.getId()).orElseThrow();
            fresh.setJournal(journal);
            PrdSpecOper concurrent = sectionService.createNew(fresh, descriptor);
            concurrent.setRoute("concurrent");
            List<PrdSpecOper> current = sectionService
                .<PrdSpecOper, PrdSpec>findByParent(fresh, descriptor);
            current.add(concurrent);
            sectionService.replaceAll(fresh, current, descriptor);
            assertThat(sectionService.<PrdSpecOper, PrdSpec>findByParent(fresh, descriptor))
                .hasSize(2);

            // Stale owner (prepared before the concurrent commit) must conflict,
            // not silently delete the concurrent row.
            PrdSpecOper staleRow = sectionService
                .<PrdSpecOper, PrdSpec>findByParent(fresh, descriptor).getFirst();
            staleRow.setRoute("stale-edit");
            assertThatThrownBy(() -> sectionService.replaceAll(staleOwner, List.of(staleRow), descriptor))
                .isInstanceOf(jakarta.persistence.OptimisticLockException.class);

            List<PrdSpecOper> preserved = sectionService
                .<PrdSpecOper, PrdSpec>findByParent(fresh, descriptor);
            assertThat(preserved).hasSize(2);
            assertThat(preserved).extracting(PrdSpecOper::getRoute)
                .containsExactlyInAnyOrder("first", "concurrent");
        });
    }

    private Long versionOf(Long id) {
        return prdSpecRepository.findById(id).orElseThrow().getVersion();
    }

    private PrdSpec createSpec(String code) {
        Journal journal = journalRepository.save(journal("J-" + code));
        UnitOfMeasurement unit = unitRepository.save(
            new UnitOfMeasurement("u" + code.substring(0, 3), "Unit " + code,
                "U" + code.substring(0, Math.min(code.length(), 8))));
        Nomenclature nomenclature = nomenclatureRepository.save(
            new Nomenclature("N-" + code, "Nom " + code, unit));
        PrdSpec spec = new PrdSpec();
        spec.setJournal(journal);
        spec.setNomenclature(nomenclature);
        spec.setCodeSpec("SPEC-" + code);
        return prdSpecRepository.save(spec);
    }

    private static Journal journal(String code) {
        Journal journal = new Journal();
        journal.setCode(code);
        journal.setName("Journal " + code);
        return journal;
    }
}
