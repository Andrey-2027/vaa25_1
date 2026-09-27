package org.ip.routes;

import com.vaadin.flow.component.UI;
import org.ip.config.DataInitializer;
import org.ip.model.Journal;
import org.ip.model.ReceivingDocument;
import org.ip.model.Workshop;
import org.ip.repository.JournalRepository;
import org.ip.repository.ReceivingDocumentRepository;
import org.ip.repository.WorkshopRepository;
import org.ipro.crud.EntityCopyService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.FieldFactory;
import org.ipro.form.TableSectionFactory;
import org.ipro.form.action.ActionContextProvider;
import org.ipro.form.action.ActionHandlerRegistry;
import org.ipro.form.action.ActionRegistry;
import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.form.coordinator.ItemFormAccessBinder;
import org.ipro.form.coordinator.ItemFormWrapperView;
import org.ipro.form.link.FormLinkResult;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.link.FormRoute;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.FormRouteCodec;
import org.ipro.form.link.FormRouteOpener;
import org.ipro.form.link.OpenResult;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.spi.FormSettingsStore;
import org.ipro.form.spi.GridViewStore;
import org.ipro.form.spi.WorkspaceGateway;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.AccessGrant;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.ipro.rls.RlsUiGate;
import org.ip.service.AccessGrantAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * E2.5: приёмка канала прямого адреса на <b>двух живых пользователях</b> (ADR-0009 §8, `DAC-22`).
 *
 * <p>Проверяется то, чего unit-тесты исходов не проверяют по построению: адрес действительно идёт
 * тем же canonical read, что список и карточка, и этот read отвечает на право доступа настоящим
 * RLS, а не заглушкой. Поэтому сервисы настоящие ({@code ServiceLocator} из контекста), а
 * двойником остаётся только рабочая область: вкладку в тесте открывать нечем, а предмет приёмки —
 * отказ или доступ, а не её байты.</p>
 *
 * <p>Матрица §3/§4 в терминах адреса: чужая (скрытая) строка и физически отсутствующая дают
 * <b>один и тот же</b> 404 — иначе адрес стал бы инструментом проверки существования чужих
 * записей; отзыв права после успешного открытия снова закрывает вход (вкладка с прошлого раза не
 * считается разрешением); классовый запрет чтения не раскрывает строку никому, включая владельца,
 * но и не блокирует тех, кому доступ выдан.</p>
 */
@SpringBootTest
class DeepLinkTwoUserAccessIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private FormRouteCatalog catalog;

    @Autowired
    private FormRouteCodec codec;

    @Autowired
    private FormLinkService links;

    @Autowired
    private FormResolver formResolver;

    @Autowired
    private MetadataResolver metadataResolver;

    @Autowired
    private ServiceLocator serviceLocator;

    @Autowired
    private JournalRepository journals;

    @Autowired
    private WorkshopRepository workshops;

    @Autowired
    private ReceivingDocumentRepository documents;

    @Autowired
    private AccessGrantRepository grants;

    @Autowired
    private AccessGrantAdminService adminGrants;

    /**
     * Стратегия контекста безопасности ИМЕННО этого контекста: {@code @PreAuthorize} читает
     * субъекта через неё, а не через статический {@code SecurityContextHolder} (у Vaadin здесь
     * {@code VaadinAwareSecurityContextHolderStrategy}). Без этого вызов админ-сервиса из теста
     * падал бы «An Authentication object was not found in the SecurityContext».
     */
    @Autowired
    private SecurityContextHolderStrategy securityContextHolderStrategy;

    private String alice;
    private String bob;
    private Long journalOfAlice;
    private Long journalOfBob;

    @BeforeEach
    void setUp() {
        UI.setCurrent(new UI());

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        alice = "e25-alice-" + suffix;
        bob = "e25-bob-" + suffix;
        journalOfAlice = createJournal("E25-A-" + suffix);
        journalOfBob = createJournal("E25-B-" + suffix);

        // Держатель нужен, чтобы измерение JOURNAL не оказалось пустым: при полном отсутствии
        // грантов включается bootstrap-режим, и «нет прав» перестало бы что-либо значить.
        grantJournal("e25-holder-" + suffix, journalOfAlice, true);
        grantJournal(alice, journalOfAlice, true);
        grantJournal(bob, journalOfBob, true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        UI.setCurrent(null);
    }

    /**
     * Ссылка, полученная программно, — это и есть адрес, открывающий ту же строку: генерация,
     * разбор, публикация и чтение проверяются одним проходом, а не тремя тестами, соединёнными
     * предположением тестировщика.
     */
    @Test
    void theLinkBuiltForTheRowIsTheAddressThatOpensIt() {
        login(alice);

        FormLinkResult link = links.linkToRecord(Journal.class, journalOfAlice);
        assertThat(link).isInstanceOf(FormLinkResult.Linkable.class);
        String address = ((FormLinkResult.Linkable) link).path();

        assertThat(opener(address))
            .as("адрес, построенный для своей строки, открывает именно её")
            .isInstanceOf(OpenResult.Opened.class);
    }

    /** Ссылка из сервиса и маршрут из каталога — одно и то же; иначе лестница адресов разъедется. */
    @Test
    void thePublishedAddressCarriesTheSameRouteTheCatalogWouldFormat() {
        login(alice);

        FormLinkResult link = links.linkToRecord(Journal.class, journalOfAlice);

        assertThat(((FormLinkResult.Linkable) link).route())
            .isEqualTo(FormRoute.record("journal", journalOfAlice));
    }

    /**
     * Скрытая строка и отсутствующая — один исход и одно сообщение. Различение было бы
     * инструментом проверки существования чужих записей, а повторение промахов — способом перебора.
     */
    @Test
    void aHiddenRowIsIndistinguishableFromAnAbsentOne() {
        login(alice);

        OpenResult hidden = open("/records/journal/" + journalOfBob);
        OpenResult absent = open("/records/journal/" + absentId());

        assertThat(hidden).isInstanceOf(OpenResult.NotFound.class);
        assertThat(absent).isInstanceOf(OpenResult.NotFound.class);
        assertThat(hidden.outcome()).isEqualTo(absent.outcome());
        assertThat(hidden.message())
            .as("сообщение не должно позволять отличить скрытую строку от несуществующей")
            .isEqualTo(absent.message())
            .doesNotContain(String.valueOf(journalOfBob));
    }

    /** Исход отказа — причина, по которой вкладка не создаётся: за ней не должно быть формы. */
    @Test
    void aRefusedAddressNeverCreatesATab() {
        WorkspaceGateway gateway = mock(WorkspaceGateway.class);
        login(alice);

        assertThat(opener("/records/journal/" + journalOfBob, gateway))
            .isInstanceOf(OpenResult.NotFound.class);

        verify(gateway, never()).open(any(), anyString(), anyString(), any());
    }

    /** Тот же адрес у разных пользователей читается по-разному: право решает чтение, а не адрес. */
    @Test
    void theSameAddressIsReadThroughEachUsersOwnAccess() {
        login(alice);
        assertThat(open("/records/journal/" + journalOfBob))
            .isInstanceOf(OpenResult.NotFound.class);

        login(bob);
        assertThat(open("/records/journal/" + journalOfBob))
            .isInstanceOf(OpenResult.Opened.class);
        assertThat(open("/records/journal/" + journalOfAlice))
            .isInstanceOf(OpenResult.NotFound.class);
    }

    /**
     * Отзыв права после успешного открытия закрывает вход снова: адрес не «помнит» прошлое
     * разрешение, а вкладка, открытая при прежнем праве, не считается достаточным основанием.
     */
    @Test
    void revokingTheGrantAfterTheFirstOpenClosesTheEntranceAgain() {
        WorkspaceGateway gateway = mock(WorkspaceGateway.class);
        login(alice);
        assertThat(opener("/records/journal/" + journalOfAlice, gateway))
            .isInstanceOf(OpenResult.Opened.class);

        revokeAlice();

        login(alice);
        assertThat(opener("/records/journal/" + journalOfAlice, gateway))
            .as("право проверяется на каждом входе, а не только на первом")
            .isInstanceOf(OpenResult.NotFound.class);
        verify(gateway, times(1)).open(eq(ItemFormWrapperView.class), anyString(), anyString(), any());
    }

    /**
     * Отсутствие authentication — не разрешённый обход и на канале адреса тоже, но отказ приходит
     * <b>иначе</b>, чем у пользователя без гранта, и это стоит знать точно.
     *
     * <p>У аутентифицированного пользователя без права на класс гейт CHECK_ONLY отдаёт пустое
     * чтение — тот же 404, что и у отсутствующей строки (см. соседний тест). Без субъекта отказ
     * громкий: граница «субъект обязателен» бросает исключение, и адрес отвечает **403**. Это не
     * раскрытие строки: исход одинаков для существующей и для заведомо отсутствующей строки,
     * потому что он не зависит от строки вовсе. Утверждение поэтому — не «404», а «один исход на
     * обе строки и ни одной вкладки»: именно это и проверяется.
     */
    @Test
    void anAddressIsNotAnAnonymousEntrance() {
        WorkspaceGateway gateway = mock(WorkspaceGateway.class);
        SecurityContextHolder.clearContext();

        OpenResult existing = opener("/records/journal/" + journalOfAlice, gateway);
        OpenResult absent = opener("/records/journal/" + absentId(), gateway);

        assertThat(existing).isInstanceOf(OpenResult.Forbidden.class);
        assertThat(absent)
            .as("отказ не зависит от строки: он про субъект, а не про запись")
            .isInstanceOf(OpenResult.Forbidden.class);
        assertThat(absent.outcome()).isEqualTo(existing.outcome());
        assertThat(absent.message()).isEqualTo(existing.message());
        verify(gateway, never()).open(any(), anyString(), anyString(), any());
    }

    /**
     * Отзыв права <b>через продукт</b> (экран «Доступ (RLS)» → {@code AccessGrantAdminService})
     * закрывает адрес так же, как отзыв через фикстуру: это проверка не админ-экрана, а того,
     * что канал адреса не зависит от пути, которым изменились права, — он читает их заново на
     * каждом входе и не помнит прошлое разрешение.
     */
    @Test
    void revokingThroughTheAdminScreenClosesTheAddressToo() {
        WorkspaceGateway gateway = mock(WorkspaceGateway.class);
        login(alice);
        assertThat(opener("/records/journal/" + journalOfAlice, gateway))
            .isInstanceOf(OpenResult.Opened.class);

        revokeThroughTheAdminScreen();

        login(alice);
        assertThat(opener("/records/journal/" + journalOfAlice, gateway))
            .as("право, снятое продуктом, закрывает вход так же, как снятое фикстурой")
            .isInstanceOf(OpenResult.NotFound.class);
        verify(gateway, times(1)).open(eq(ItemFormWrapperView.class), anyString(), anyString(), any());
    }

    /**
     * Классовый запрет чтения измеряется, а не предполагается: гейт CHECK_ONLY
     * ({@code RlsReadGate}) отдаёт <b>пустое чтение</b>, поэтому адрес отвечает 404, а не 403.
     * Направление безопасное — строка не раскрывается вместе с её существованием; 403 приходит
     * только там, где отказ бросается исключением (построчное право на изменение, сценарий
     * каталога).
     */
    @Test
    void aClassLevelReadDenyEndsAsAnEmptyReadNotAsForbidden() {
        Long documentId = createDocument();

        login("e25-doc-stranger-" + UUID.randomUUID().toString().substring(0, 8));

        assertThat(open("/records/receiving-document/" + documentId))
            .as("классовый запрет чтения не раскрывает строку — и не выдаёт её наличие")
            .isInstanceOf(OpenResult.NotFound.class);
    }

    /** Не-вакуумный контроль: тот же адрес у обладателя доступа открывается. */
    @Test
    void theClassGateStillLetsTheRowThroughForSomeoneWithAccess() {
        Long documentId = createDocument();
        String owner = "e25-doc-owner-" + UUID.randomUUID().toString().substring(0, 8);
        grantDocuments(owner, journalOfAlice);

        login(owner);

        assertThat(open("/records/receiving-document/" + documentId))
            .as("иначе предыдущая проверка доказывала бы только то, что забор запрещает всем")
            .isInstanceOf(OpenResult.Opened.class);
    }

    /** Тип, которого нет в каталоге публикации, до чтения данных не доходит вообще. */
    @Test
    void anUnpublishedKeyNeverReachesTheData() {
        WorkspaceGateway gateway = mock(WorkspaceGateway.class);
        login(alice);

        assertThat(opener("/records/nom-attribute-value/" + journalOfAlice, gateway))
            .as("строка секции адреса не получает: отказ выносит каталог, а не пустое чтение")
            .isInstanceOf(OpenResult.InvalidRoute.class);
        verify(gateway, never()).open(any(), anyString(), anyString(), any());
    }

    /** Список того же типа открывается тем же право доступа: адрес не отдельный канал данных. */
    @Test
    void theListAddressFollowsTheSameAccess() {
        login(alice);

        assertThat(open("/lists/journal")).isInstanceOf(OpenResult.Opened.class);
    }

    // ---------------------------------------------------------------------- фикстуры

    private OpenResult open(String address) {
        return opener(address);
    }

    private OpenResult opener(String address) {
        return opener(address, mock(WorkspaceGateway.class));
    }

    private OpenResult opener(String address, WorkspaceGateway gateway) {
        return new FormRouteOpener(catalog, codec, coordinator(gateway)).openAddress(address);
    }

    /**
     * Координатор настоящий: именно его порядок проверок и есть контракт канала. Заглушками
     * остаются только зависимости, которых в чтении по адресу нет.
     */
    private FormCoordinator coordinator(WorkspaceGateway gateway) {
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkspaceGateway> gateways = mock(ObjectProvider.class);
        when(gateways.getIfAvailable()).thenReturn(gateway);

        return new FormCoordinator(
            metadataResolver,
            mock(FieldFactory.class),
            mock(ApplicationContext.class),
            formResolver,
            serviceLocator,
            mock(FormSettingsStore.class),
            mock(GridViewStore.class),
            mock(RlsUiGate.class),
            mock(ItemFormAccessBinder.class),
            mock(ActionRegistry.class),
            mock(ActionContextProvider.class),
            mock(ActionHandlerRegistry.class),
            links,
            mock(EntityCopyService.class),
            mock(TableSectionFactory.class),
            gateways,
            null);
    }

    private Long createJournal(String code) {
        return RlsTestFixture.callAsSuperuser(grants, () -> {
            Journal journal = new Journal();
            journal.setCode(code);
            journal.setName(code);
            return journals.saveAndFlush(journal).getId();
        });
    }

    private Long createDocument() {
        return RlsTestFixture.callAsSuperuser(grants, () -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Workshop receiving = workshops.save(new Workshop("RW-" + suffix, "Принимающий " + suffix));
            Workshop transferring = workshops.save(new Workshop("TW-" + suffix, "Передающий " + suffix));
            ReceivingDocument document = new ReceivingDocument(
                "РН-" + suffix, LocalDate.now(), receiving, transferring);
            document.setJournal(journals.findById(journalOfAlice).orElseThrow());
            return documents.saveAndFlush(document).getId();
        });
    }

    /**
     * Тем же путём, каким отзывает экран: матрица измерения целиком, где у всех записей стоит
     * «нет доступа» — продукт удаляет строки, а не пишет нулевые флаги. Сбор значений идёт
     * ровно тем же сервисным методом, что и у экрана, — иначе тест проверял бы не продукт.
     */
    private void revokeThroughTheAdminScreen() {
        SecurityContextHolderStrategy strategy = securityContextHolderStrategy;
        var previous = strategy.getContext();
        var admin = strategy.createEmptyContext();
        admin.setAuthentication(new UsernamePasswordAuthenticationToken(CURRENT_ADMIN, "n/a",
            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        strategy.setContext(admin);
        SecurityContextHolder.setContext(admin);
        try {
            Map<Long, AccessGrantAdminService.GrantFlags> desired = new HashMap<>();
            for (AccessGrantAdminService.ValueRow value : adminGrants.allValues("JOURNAL")) {
                desired.put(value.id(), AccessGrantAdminService.GrantFlags.NONE);
            }
            adminGrants.saveGrants("JOURNAL", AccessGrant.SubjectType.USER, alice, desired);
        } finally {
            strategy.setContext(previous);
            SecurityContextHolder.setContext(previous);
        }
    }

    private void revokeAlice() {
        RlsTestFixture.callAsSuperuser(grants, () -> {
            grants.deleteAll(grants.findAll().stream()
                .filter(grant -> alice.equals(grant.getSubjectKey()))
                .toList());
            return null;
        });
    }

    private void grantJournal(String subjectKey, Long dimensionValueId, boolean canRead) {
        saveGrant(subjectKey, "JOURNAL", dimensionValueId, canRead);
    }

    private void grantDocuments(String subjectKey, Long journalId) {
        saveGrant(subjectKey, "ENTITY:ReceivingDocument", null, true);
        // Построчный доступ к самому документу — отдельное измерение: классовый гейт открывает
        // класс, но не строки в нём.
        saveGrant(subjectKey, "JOURNAL", journalId, true);
    }

    private void saveGrant(String subjectKey, String dimension, Long dimensionValueId, boolean canRead) {
        RlsTestFixture.callAsSuperuser(grants, () -> {
            AccessGrant grant = new AccessGrant();
            grant.setSubjectType(AccessGrant.SubjectType.USER);
            grant.setSubjectKey(subjectKey);
            grant.setDimension(dimension);
            grant.setDimensionValueId(dimensionValueId);
            grant.setCanRead(canRead);
            grant.setCanUpdate(canRead);
            grant.setCanDelete(false);
            return grants.saveAndFlush(grant);
        });
    }

    private void login(String username) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
    }

    private static final String CURRENT_ADMIN = "e25-admin";

    /** Заведомо отсутствующий id: отличать «нет строки» от «нет права» адрес не должен. */
    private static long absentId() {
        return 987654321L;
    }
}
