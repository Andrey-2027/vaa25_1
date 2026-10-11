package org.ip.application.catalog;

import jakarta.persistence.EntityManager;
import org.ip.config.DataInitializer;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.model.Role;
import org.ip.repository.AttributeTypeRepository;
import org.ipro.data.EntityDataAccess;
import org.ipro.form.FormSaveResult;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.registry.FormResolver;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsAccessDeniedException;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C5.6 (ADR-0017 §9): финальная граница записи вооружена для {@code AttributeType}, а запись
 * этого типа идёт тем каналом, которым её делает человек, — item-формой типа атрибута.
 *
 * <p>Прямых записей {@code AttributeType} в production-коде нет: форму пишет generic
 * metadata-driven путь, а {@code AttributeTypeLifecycle} только читает текущее состояние.
 * Поэтому предмет теста — не «перевести вызов», а закрепить: у типа есть canonical write
 * handle, граница для него обязательна, и <b>включение границы ничего не ломает</b> ни на
 * платформенном шве ({@link EntityDataAccess}), ни в UI-канале формы. Прямая запись
 * repository мимо preflight — то, что граница обязана отклонять на flush.</p>
 *
 * <p>Вооружён ровно один тип: соседние {@code Role}/{@code User} остаются снятыми, и это видно
 * в {@code isWriteGoverned}, а не подразумевается.</p>
 */
@SpringBootTest(classes = org.ip.Application.class, properties = {
    "rls.write-boundary.enforced-types=org.ip.model.AttributeType"})
class AttributeTypeWriteBoundaryIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private EntityDataAccess access;

    @Autowired
    private AccessGrantRepository grants;

    @Autowired
    private AttributeTypeRepository types;

    @Autowired
    private RlsDimensionRegistry registry;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private FormResolver forms;

    @Autowired
    private org.ip.application.form.ItemFormSaveDispatcher formSaves;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Вооружён ровно названный тип: пошаговость видна, а не подразумевается. */
    @Test
    void onlyTheNamedTypeIsArmed() {
        assertThat(registry.isWriteGoverned(AttributeType.class)).isTrue();
        assertThat(registry.isWriteGoverned(Role.class))
            .as("неназванный тип остаётся снятым: это следующий шаг миграции, а не забытый")
            .isFalse();
    }

    /** Платформенный шов: canonical create и update проходят вооружённую границу. */
    @Test
    void canonicalCreateAndUpdatePassTheBoundary() {
        login();

        AttributeType created = access.create(AttributeType.class,
            new AttributeType(code("CANON"), "Тип " + code("N"), AttributeValueType.STRING));
        assertThat(created.getId()).isNotNull();

        created.setName("Переименовано canonical path");
        AttributeType updated = access.update(AttributeType.class, created);
        assertThat(updated.getName()).isEqualTo("Переименовано canonical path");
    }

    /**
     * UI-канал: форма типа атрибута сохраняется через тот же metadata-driven адаптер, что и в
     * приложении. Тест держит границу на настоящем пользовательском входе, а не только на
     * прямом вызове {@code access}: generic-путь обязан остаться открытым после вооружения.
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void theItemFormChannelWritesThroughTheArmedBoundary() {
        login();
        String code = code("UI");

        ItemForm<AttributeType> form = forms.resolveItemForm(AttributeType.class, null, null, null);
        form.initializeNewEntity(Map.of("code", code, "name", "Тип " + code,
            "valueType", AttributeValueType.STRING));
        form.setSaveHandler(value -> (FormSaveResult) formSaves.save((ItemForm) value));

        assertThat(form.save()).isInstanceOf(FormSaveResult.Success.class);
        assertThat(form.getEntity().getId()).isNotNull();
        assertThat(types.findById(form.getEntity().getId())).isPresent();
    }

    /** Прямая запись мимо canonical path: авторизации нет, поэтому отказ на flush. */
    @Test
    void aDirectRepositoryWriteIsDeniedAtFlush() {
        login();
        String code = code("DN");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            entityManager.persist(new AttributeType(code, "Прямая запись " + code,
                AttributeValueType.STRING));
            entityManager.flush();
        }))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("canonical write preflight")
            .hasMessageContaining(AttributeType.class.getName());

        assertThat(types.findAll()).noneMatch(type -> code.equals(type.getCode()));
    }

    private String login() {
        String actor = RlsTestFixture.authenticateAsSuperuser(grants);
        // Форма читает значения типа: без read-ключа этот канал откажет на чтении, а не на
        // записи, и тест проверял бы не то.
        RlsTestFixture.grantC5EntityRead(grants, actor, AttributeType.class, AttributeValue.class);
        return actor;
    }

    /** Код ограничен двадцатью символами ({@code @Size(max = 20)}), поэтому суффикс короткий. */
    private static String code(String tag) {
        return tag + UUID.randomUUID().toString().substring(0, 6);
    }
}
