package org.ip.security;

import jakarta.persistence.EntityManager;
import org.ip.config.EntityClassificationConfig;
import org.ip.model.AttributeValue;
import org.ip.model.Journal;
import org.ip.model.PrdSpec;
import org.ip.repository.UserRepository;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionDecision.Reason;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.action.ActionResolver;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.ipro.form.action.ReadOnlyReason;
import org.ipro.form.coordinator.ItemFormAccessBinder;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsUiGate;
import org.ipro.rls.config.RlsPersistenceAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1.2-pilot: RLS-половина gate — на <b>реальном носителе</b>, а не на стабе гейта.
 *
 * <p>План называет носителя прямо (§E1.2-pilot): RLS-отказ недостижим на пилотных типах
 * ({@code Nomenclature}, {@code AttributeValue}, {@code SklNomOpa} не защищены) и проверяется на
 * {@code PrdSpec} — единственном защищённом типе среди списков. Стаб гейта такой отказ изображает,
 * но не доказывает: между «решение гасит кнопку, когда права отказаны» и «права действительно
 * отказаны на этом типе этим гейтом» лежит вся проводка — регистрация измерения, чтение грантов,
 * путь значения измерения, классовый отказ создания. Здесь проверяется именно проводка.</p>
 *
 * <p><b>Что настоящее.</b> Гейт — настоящий {@link RlsUiGate} поверх настоящего {@link AccessService}
 * и H2 с реальными {@code AccessGrant} (как {@code RlsUiGateTest}). Каталог дескрипторов —
 * настоящий {@link EntityDescriptorCatalog}, построенный на реальном persistence unit среза
 * ({@link ManagedEntityCatalog} из metamodel) и настоящем {@link SectionMetadataRegistry} скана
 * приложения; capability-override — настоящие бины {@link EntityClassificationConfig}. Решение —
 * настоящие {@link ActionContextProvider} → {@link ActionResolver} → чистая политика; карточка —
 * настоящий {@link ItemFormAccessBinder}. Моков нет ни одного.</p>
 *
 * <p><b>Почему это не тавтология.</b> Первая проверка утверждает, что тип защищён, а его
 * capability <b>разрешает</b> создание, изменение и удаление. Поэтому отказ в следующих проверках
 * может прийти только от прав: типовая причина («тип не поддерживает…») здесь недостижима, и
 * зелёный тест нельзя получить случайно.</p>
 *
 * <p><b>Серверная граница.</b> Обратная половина gate — «скрытая кнопка не открывает серверный
 * обход» — проверена отдельно и здесь не дублируется: {@code RlsServiceWriteBoundaryIT} на тех же
 * защищённых типах показывает, что прямой вызов сервиса отклоняется {@code RlsAccessDeniedException}.
 * UI-блокировка остаётся удобством, а не защитой.</p>
 *
 * <p><b>Что не входит.</b> «Оба отказа на одном типе» (и права, и capability) на прикладных
 * политиках недостижимо: среди защищённых типов нет типа с урезанными generic-записями, а
 * {@code AttributeValue} без RLS. Эта комбинация остаётся табличной проверкой политики
 * ({@code ActionPolicyDecisionTableTest}); здесь проверяются оба отказа по отдельности на одном
 * каталоге и одном гейте, и то, что они не подменяют друг друга в причине.</p>
 */
@DataJpaTest
// org.ip объявлен в Application#@EnableJpaRepositories (иначе дублирование бобов репозиториев в срезе)
@ImportAutoConfiguration(RlsPersistenceAutoConfiguration.class)
class RlsProtectedTypeActionDecisionTest {

    private static final String READ_ONLY_USER = "e12-bob";
    private static final String WRITER_USER = "e12-alice";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    @Autowired
    private UserRepository userRepository;

    private RlsDimensionRegistry dimensionRegistry;
    private RlsUiGate gate;
    private EntityDescriptorCatalog catalog;
    private Journal journalA;
    private PrdSpec spec;

    @BeforeEach
    void setUp() {
        dimensionRegistry = new RlsDimensionRegistry("org.ip");
        dimensionRegistry.rebuild();
        AccessService accessService = new AccessService(accessGrantRepository,
            new UserRepositoryRlsRoleResolver(userRepository), dimensionRegistry);
        gate = new RlsUiGate(accessService, dimensionRegistry,
            () -> SecurityContextHolder.getContext().getAuthentication().getName());

        MetadataResolver metadataResolver = new MetadataResolver();
        SectionMetadataRegistry sections = new SectionMetadataRegistry("org.ip", metadataResolver);
        sections.rebuild();
        EntityClassificationConfig classification = new EntityClassificationConfig();
        catalog = new EntityDescriptorCatalog(
            new ManagedEntityCatalog(entityManager.getEntityManagerFactory()),
            sections, metadataResolver, List.of(),
            List.of(classification.attributeValueIsCreateOnly(),
                classification.sklNomOpaHasNoGenericWrites()));

        journalA = new Journal();
        journalA.setCode("E12-G-A");
        journalA.setName("Журнал A");
        entityManager.persist(journalA);
        entityManager.flush();

        spec = new PrdSpec();
        spec.setId(4711L);
        spec.setJournal(journalA);
        spec.setCodeSpec("E12-1");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Носитель назван, и отказ не может прийти от типа: у него есть все generic-записи.
     * Без этой проверки остальные утверждения прошли бы и на типе, которому изменение просто
     * не выдано capabilities, — то есть доказывали бы не RLS.
     */
    @Test
    void carrierIsProtectedAndItsCapabilitiesAllowWhatRightsDeny() {
        assertThat(dimensionRegistry.policyOf(PrdSpec.class).protectedEntity())
            .as("носитель RLS-отказа: PrdSpec объявлен @RlsDimension")
            .isTrue();

        EntityDescriptor descriptor = catalog.descriptorOf(PrdSpec.class);
        assertThat(descriptor.capabilities().allows(DataOperation.CREATE)).isTrue();
        assertThat(descriptor.capabilities().allows(DataOperation.UPDATE)).isTrue();
        assertThat(descriptor.capabilities().allows(DataOperation.DELETE)).isTrue();
        assertThat(descriptor.exposure())
            .as("тип остаётся обычным корнем: ограничивает только право, не классификация")
            .isEqualTo(org.ipro.data.EntityExposure.STANDARD_ROOT);
    }

    /** Права отказаны только на чтение-запись: изменение и удаление недоступны, чтение работает. */
    @Test
    void rightsDenialTurnsRowActionsOffAndKeepsReadActionsAvailable() {
        grant(READ_ONLY_USER, journalA.getId(), true, false, false);
        loginAs(READ_ONLY_USER);

        ActionDecision edit = listDecision(CrudAction.EDIT, spec);
        assertThat(edit.visible())
            .as("право отказано — действие видно, но недоступно: причина остаётся в подсказке")
            .isTrue();
        assertThat(edit.actionable()).isFalse();
        assertThat(edit.reason()).isEqualTo(Reason.ACCESS_DENIED);
        assertThat(edit.message()).contains("изменение", "JOURNAL");

        ActionDecision delete = listDecision(CrudAction.DELETE, spec);
        assertThat(delete.actionable()).isFalse();
        assertThat(delete.reason()).isEqualTo(Reason.ACCESS_DENIED);
        assertThat(delete.message()).contains("удаление", "JOURNAL");

        assertThat(listDecision(CrudAction.REFRESH, null).actionable())
            .as("чтение списка не зависит от строковых прав")
            .isTrue();

        // Путь чтения, которого не было до решения владельца по E1.2-pilot: «Просмотр» требует
        // «изменение недоступно» (тип или права), поэтому у строки с отказом в правах на изменение
        // появляется доступный просмотр. До этого такая строка не открывалась из списка вовсе.
        ActionDecision open = listDecision(CrudAction.OPEN, spec);
        assertThat(open.visible()).isTrue();
        assertThat(open.actionable()).isTrue();
        assertThat(open.reason()).isEqualTo(Reason.NONE);
    }

    /** Создание защищённого типа: отказ классового права, а не отсутствие capability. */
    @Test
    void creationOfProtectedTypeIsRefusedByRightsAndNamesItsDimension() {
        grant(READ_ONLY_USER, journalA.getId(), true, false, false);
        loginAs(READ_ONLY_USER);

        ActionDecision create = listDecision(CrudAction.CREATE, null);
        assertThat(create.actionable()).isFalse();
        assertThat(create.reason()).isEqualTo(Reason.ACCESS_DENIED);
        assertThat(create.message()).contains("создание", "JOURNAL");

        String blockReason = new ItemFormAccessBinder()
            .blockReasonIfCannotCreate(listResolver());
        assertThat(blockReason)
            .as("прямое открытие карточки создания называет права, а не «тип не поддерживает CREATE»")
            .isNotNull()
            .contains("JOURNAL")
            .doesNotContain("не поддерживает");
    }

    /** Карточка защищённой записи: просмотр с показываемой причиной отказа в правах. */
    @Test
    void cardOfProtectedRecordOpensReadOnlyWithRightsNotice() {
        grant(READ_ONLY_USER, journalA.getId(), true, false, false);
        loginAs(READ_ONLY_USER);

        ReadOnlyReason reason = new ItemFormAccessBinder()
            .readOnlyReason(itemResolver(), spec);

        assertThat(reason.kind()).isEqualTo(ReadOnlyReason.Kind.ACCESS_DENIED);
        assertThat(reason.showsNotice()).isTrue();
        assertThat(reason.noticeText()).startsWith("Только просмотр").contains("JOURNAL");
    }

    /**
     * Двойной клик по строке с отказом в правах на изменение: изменение запрещено (причина видна),
     * но есть решение просмотра — значит путь чтения остался. Карточка при этом будет в режиме
     * просмотра с названной причиной (проверяется тем же решением о сохранении, ниже).
     */
    @Test
    void rowWithDeniedUpdateKeepsReadPathThroughOpen() {
        grant(READ_ONLY_USER, journalA.getId(), true, false, false);
        loginAs(READ_ONLY_USER);

        ActionDecision edit = listDecision(CrudAction.EDIT, spec);
        ActionDecision open = listDecision(CrudAction.OPEN, spec);

        assertThat(edit.visible()).isTrue();
        assertThat(edit.reason()).isEqualTo(Reason.ACCESS_DENIED);
        assertThat(edit.message()).contains("изменение", "JOURNAL");
        assertThat(open.actionable()).isTrue();

        ReadOnlyReason cardReason = new ItemFormAccessBinder()
            .readOnlyReason(itemResolver(), spec);
        assertThat(cardReason.kind())
            .as("открытая таким путём карточка правиться не будет: сохранение решает то же решение")
            .isEqualTo(ReadOnlyReason.Kind.ACCESS_DENIED);
    }

    /**
     * Комбинация на одном каталоге и одном гейте: отказ прав показывается, типовая read-only —
     * нет. Причины не подменяют друг друга, иначе пользователь читал бы «нет прав» там, где прав
     * никто не отказывал, — дефект, найденный в E1.0.
     */
    @Test
    void capabilityReadOnlyStaysNeutralWhileRightsDenialIsNamed() {
        grant(READ_ONLY_USER, journalA.getId(), true, false, false);
        loginAs(READ_ONLY_USER);

        AttributeValue value = new AttributeValue();
        value.setId(99L);
        ReadOnlyReason typed = new ItemFormAccessBinder()
            .readOnlyReason(itemResolver(AttributeValue.class), value);

        assertThat(typed.kind())
            .as("значение атрибута не защищено RLS: режим просмотра у него типовой")
            .isEqualTo(ReadOnlyReason.Kind.TYPE_READ_ONLY);
        assertThat(typed.noticeText()).isEmpty();

        ReadOnlyReason denied = new ItemFormAccessBinder()
            .readOnlyReason(itemResolver(), spec);
        assertThat(denied.kind()).isEqualTo(ReadOnlyReason.Kind.ACCESS_DENIED);
        assertThat(denied.noticeText()).isNotEmpty();
    }

    /** Каждое действие читает своё право: изменение разрешено, удаление — нет. */
    @Test
    void everyRightIsReadSeparatelyOnProtectedType() {
        grant(WRITER_USER, journalA.getId(), true, true, false);
        loginAs(WRITER_USER);

        assertThat(listDecision(CrudAction.EDIT, spec).actionable()).isTrue();
        assertThat(listDecision(CrudAction.CREATE, null).actionable()).isTrue();

        ActionDecision delete = listDecision(CrudAction.DELETE, spec);
        assertThat(delete.actionable()).isFalse();
        assertThat(delete.reason()).isEqualTo(Reason.ACCESS_DENIED);
        assertThat(delete.message()).contains("удаление", "JOURNAL");
    }

    /** Контроль: с полным правом и действия, и карточка разрешены — иначе отказы выше ничего не значат. */
    @Test
    void permittedUserSeesEditableCardAndAvailableActions() {
        grant(WRITER_USER, journalA.getId(), true, true, true);
        loginAs(WRITER_USER);

        assertThat(listDecision(CrudAction.EDIT, spec).actionable()).isTrue();
        assertThat(listDecision(CrudAction.DELETE, spec).actionable()).isTrue();
        assertThat(listDecision(CrudAction.CREATE, null).actionable()).isTrue();
        assertThat(listDecision(CrudAction.OPEN, spec).visible())
            .as("изменение доступно — просмотр не дублирует кнопку изменения")
            .isFalse();
        assertThat(new ItemFormAccessBinder().readOnlyReason(itemResolver(), spec)).isNull();
    }

    // --- фикстуры -------------------------------------------------------------------------------

    private void loginAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private void grant(String username, Long journalId, boolean read, boolean update, boolean delete) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(username);
        grant.setDimension("JOURNAL");
        grant.setDimensionValueId(journalId);
        grant.setCanRead(read);
        grant.setCanUpdate(update);
        grant.setCanDelete(delete);
        entityManager.persist(grant);
        entityManager.flush();
    }

    private ActionRegistry actionRegistry() {
        // Полный платформенный состав: списку нужны list-действия, карточке — crud.save.
        return new ActionRegistry(CrudAction.platformDefaults(), List.of());
    }

    private ActionContextProvider provider() {
        return new ActionContextProvider(catalog, gate);
    }

    private ActionResolver listResolver() {
        return resolver(ActionSurface.LIST_TOOLBAR, PrdSpec.class);
    }

    private ActionResolver itemResolver() {
        return itemResolver(PrdSpec.class);
    }

    private ActionResolver itemResolver(Class<?> type) {
        return resolver(ActionSurface.ITEM_FOOTER, type);
    }

    private ActionResolver resolver(ActionSurface surface, Class<?> type) {
        return new ActionResolver(actionRegistry(), provider(), ActionHandlerRegistry.empty(),
            surface, type, null);
    }

    private ActionDecision listDecision(CrudAction action, Object row) {
        return resolver(ActionSurface.LIST_TOOLBAR, PrdSpec.class)
            .decide(action, row, true);
    }
}
