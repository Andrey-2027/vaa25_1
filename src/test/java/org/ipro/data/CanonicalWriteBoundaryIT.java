package org.ipro.data;

import org.ip.config.DataInitializer;
import org.ip.model.AttributeValue;
import org.ip.model.GridFormView;
import org.ip.model.NomAttributeValue;
import org.ip.model.Nomenclature;
import org.ip.model.SklNomOpa;
import org.ip.model.UserFormSettings;
import org.ip.repository.GridFormViewRepository;
import org.ipro.crud.BaseService;
import org.ipro.crud.ServiceLocator;
import org.ipro.crud.ValidationException;
import org.ipro.rls.AccessGrant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C4.3: write intents — enforcement-граница, а не декларация (ADR-0007 §1, §2, §5).
 *
 * <p>Проверяются три обещания среза:</p>
 * <ol>
 * <li>предметные запреты (инвентарь §2) обходятся только явной policy: generic write
 * для {@code AttributeValue.update/delete} и любого write {@code SklNomOpa} отклоняется
 * <b>до</b> RLS и SQL;</li>
 * <li>type-directed resolver отказывает owned row и internal store без read-моста, а
 * canonical handle получает только разрешённая экспозиция;</li>
 * <li>тип с собственным application service сохраняет его — canonical default не
 * подменяет domain-путь.</li>
 * </ol>
 */
@SpringBootTest(classes = org.ip.Application.class)
class CanonicalWriteBoundaryIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private EntityDataAccess access;

    @Autowired
    private EntityDataAccessResolver resolver;

    @Autowired
    private ServiceLocator serviceLocator;

    @Autowired
    private GridFormViewRepository gridFormViewRepository;

    @Autowired
    private org.ipro.rls.AccessGrantRepository accessGrantRepository;

    @Test
    void attributeValueGenericUpdateAndDeleteAreRejectedBeforeRlsAndSql() {
        // C5.6: отказ называет владельца политики, а не прежнее слово «бессмертно».
        assertThatThrownBy(() -> access.update(AttributeValue.class, new AttributeValue()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UPDATE")
            .hasMessageContaining("AttributeValueService.getOrCreate");

        assertThatThrownBy(() -> access.delete(AttributeValue.class, 1L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DELETE");
    }

    @Test
    void sklNomOpaHasNoGenericWriteIntentAtAll() {
        SklNomOpa instance = new SklNomOpa(null, "canon", "Canon");

        // C5.6: отказ приходит от capability-границы до сервиса, и причина обязана назвать
        // владельца канонизации, а не пересказывать его слово «канонизация».
        assertThatThrownBy(() -> access.create(SklNomOpa.class, instance))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CREATE")
            .hasMessageContaining("SklNomOpaService.findOrCreate");
        assertThatThrownBy(() -> access.update(SklNomOpa.class, instance))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UPDATE");
        assertThatThrownBy(() -> access.delete(SklNomOpa.class, 1L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DELETE");
    }

    @Test
    void createIntentCannotPerformAnUpdateAndViceVersa() {
        // P0: capability проверяется по intent, и intent же обязан совпасть с фактическим
        // состоянием. Иначе create существующего AttributeValue (только CREATE) делал бы
        // запрещённый merge, а update без id — insert.
        //
        // C5.6: решение об операции стоит сразу за capability (ADR-0017 §5), а несоответствие
        // intent'а состоянию — следующая структурная проверка. Поэтому у пробника есть
        // операции на оба типа: иначе отказ был бы по правам и проверял бы не то.
        authenticateAs("intent-probe", AttributeValue.class, org.ip.model.Workshop.class);
        AttributeValue existing = new AttributeValue();
        existing.setId(42L);

        assertThatThrownBy(() -> access.create(AttributeValue.class, existing))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("существующее состояние");

        assertThatThrownBy(() -> access.update(org.ip.model.Workshop.class, new org.ip.model.Workshop()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("требуется create");
    }

    /**
     * C4.6 волна F: ownership вида больше не сужает capability, а исполняется внутри
     * canonical write pipeline ({@code GridFormViewLifecycle}). Проверка ведётся на живой
     * строке через публичный facade: именно этот путь раньше обходил правило, и именно
     * поэтому handle типа был ограничен одним {@code CREATE}.
     */
    @Test
    void gridFormViewOwnershipIsEnforcedInsideTheCanonicalWritePipeline() {
        authenticateAs("view-owner", GridFormView.class);
        GridFormView personal = gridFormViewRepository.saveAndFlush(
            new GridFormView("ownIt.formKey", "Личный вид", "[]", false));
        Long id = personal.getId();
        assertThat(personal.getCreatedBy()).isEqualTo("view-owner");
        try {
            // Чужой пользователь не может ни изменить, ни удалить личный вид.
            authenticateAs("other-user", GridFormView.class);
            GridFormView payload = gridFormViewRepository.findById(id).orElseThrow();
            payload.setName("Переименовано чужим");
            assertThatThrownBy(() -> access.update(GridFormView.class, payload))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("личный");
            assertThatThrownBy(() -> access.delete(GridFormView.class, id))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("личный");

            // Автор сохраняет своё право: сужение capability не должно было стать
            // блокировкой законного редактирования.
            authenticateAs("view-owner", GridFormView.class);
            GridFormView own = gridFormViewRepository.findById(id).orElseThrow();
            own.setName("Переименовано автором");
            assertThat(access.update(GridFormView.class, own).getName())
                .isEqualTo("Переименовано автором");
        } finally {
            SecurityContextHolder.clearContext();
            gridFormViewRepository.deleteById(id);
        }
    }

    /**
     * Субъект с read и операционными правами на перечисленные типы: владелец вида проверяется
     * внутри pipeline, то есть после решения C5 (ADR-0017), а не вместо него.
     */
    private void authenticateAs(String username, Class<?>... entityTypes) {
        org.ipro.rls.RlsTestFixture.grantC5EntityRead(accessGrantRepository, username, entityTypes);
        for (Class<?> entityType : entityTypes) {
            org.ipro.rls.AccessGrant operations = new org.ipro.rls.AccessGrant();
            operations.setSubjectType(AccessGrant.SubjectType.USER);
            operations.setSubjectKey(username);
            operations.setDimension(
                org.ipro.rls.c5.C5PermissionKeys.entityOperations(entityType));
            operations.setCanUpdate(true);
            operations.setCanDelete(true);
            accessGrantRepository.save(operations);
        }
        accessGrantRepository.flush();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
            username, "n/a", java.util.List.of()));
        SecurityContextHolder.setContext(context);
    }

    /**
     * C4.6 волна F: владелец обслуживает свой storage сам, поэтому canonical handle у типа нет
     * даже на чтение. Отказ приходит из resolver'а — то есть до capability, RLS и SQL, а не
     * глубоко внутри запроса без metadata-плана.
     */
    @Test
    void internalStoreHasNoCanonicalHandleAtAll() {
        assertThat(resolver.find(UserFormSettings.class)).isEmpty();

        assertThatThrownBy(() -> resolver.resolve(UserFormSettings.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("INTERNAL_STORE");
        // Тип вне IdentifiableEntity даже не выражается generic write intent'ом:
        // внутреннее хранилище остаётся без canonical handle во всех операциях.
    }

    @Test
    void aggregateRootWithOwnedSectionsRejectsDirectWriteIntent() {
        // C4.6 (ADR-0007 §5): прямой create/update не несёт графа секций, поэтому молча
        // сохранил бы только шапку. Отказ обязан быть явным и до RLS, валидации и событий,
        // а сохранение агрегата — идти через aggregate boundary.
        Nomenclature root = new Nomenclature();
        root.setCode("AGG-1");
        root.setName("Агрегат");

        assertThatThrownBy(() -> access.create(Nomenclature.class, root))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("owned-секциями")
            .hasMessageContaining("Nomenclature.NomAttributeValue")
            .hasMessageContaining("CREATE")
            .hasMessageContaining("aggregate boundary");

        assertThatThrownBy(() -> access.update(Nomenclature.class, root))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UPDATE")
            .hasMessageContaining("Nomenclature.NomAttributeValue");
    }

    @Test
    void resolverRejectsOwnedRowAndInternalStoreWithTheRealReason() {
        assertThatThrownBy(() -> resolver.resolve(NomAttributeValue.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("owned-секции");
        assertThat(resolver.find(NomAttributeValue.class)).isEmpty();

        assertThatThrownBy(() -> resolver.resolve(UserFormSettings.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("INTERNAL_STORE");
        assertThat(resolver.find(UserFormSettings.class)).isEmpty();
    }

    @Test
    void standardRootWithTypedServiceKeepsItsOwnService() {
        // Волна C: {@code Workshop} мигрирован; волна E: мигрированы также {@code AttributeType}
        // и {@code PrdSpec} — как пример «root с собственным сервисом» остаётся
        // {@code AttributeValue}, чей сервис держит доменную канонизацию значений.
        BaseService<?, ?> resolved = serviceLocator.findService(org.ip.model.AttributeValue.class);

        assertThat(resolved)
            .as("canonical default не подменяет типизированный application service")
            .isSameAs(serviceLocator.findService(org.ip.model.AttributeValue.class));
        assertThat(resolved).isNotInstanceOf(CanonicalEntityService.class);
        assertThat(resolver.resolve(org.ip.model.Workshop.class)).isSameAs(access);
    }

    @Test
    void resolverReasonsAreReadableForDiagnostics() {
        assertThat(resolver.resolutionReason(org.ip.model.Workshop.class))
            .contains("canonical generic path");
        // Волна A: Branch мигрирован — типизированного сервиса нет, ServiceLocator отдаёт
        // canonical handle, а не прежний bean-name convention.
        assertThat(serviceLocator.findService(org.ip.model.Branch.class))
            .isInstanceOf(CanonicalEntityService.class);
    }
}
