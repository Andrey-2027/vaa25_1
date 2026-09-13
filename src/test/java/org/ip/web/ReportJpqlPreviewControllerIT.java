package org.ip.web;

import jakarta.persistence.EntityManager;
import org.ip.Application;
import org.ip.model.User;
import org.ip.repository.UserRepository;
import org.ipro.rls.AccessGrant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ф3 плана ReportJR-Jpql-Plan: эндпоинт /api/report-jpql/preview.
 * Проверки (все обязательные): 401 без аутентификации, 403 без гранта
 * REPORTS:JPQL_PREVIEW, 200 + данные с грантом, 400 при отказе guard'а.
 *
 * <p>Транспорт — HTTP Basic с реальной цепочкой безопасности
 * ({@link FilterChainProxy} подключается явно: в Boot 4 аннотационного
 * AutoConfigureMockMvc нет в базовом стартере тестов).</p>
 *
 * <p>Изоляция random-order: собственный ключ Spring-контекста (уникальное test
 * property), принадлежащие тесту usernames и детерминированное удаление своих
 * users/grants — данные не протекают в общую БД и не сталкиваются с другими
 * классами; принудительный порядок тестов не используется.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "ip.test.isolation=report-jpql-preview")
class ReportJpqlPreviewControllerIT {

    private static final String URL = "/api/report-jpql/preview";

    /** Пользователи принадлежат только этому классу (никаких bob/dave/admin). */
    private static final String ADMIN_USER = "jpql-admin";
    private static final String ALLOWED_USER = "jpql-dave";
    private static final String DENIED_USER = "jpql-bob";
    private static final String GRANTLESS_USER = "jpql-carol";

    private static final List<String> OWN_USERS =
            List.of(ADMIN_USER, ALLOWED_USER, DENIED_USER, GRANTLESS_USER);

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        // тест не транзакционный (контроллер работает в своих транзакциях и
        // видит только закоммиченные данные) — сеем через явный commit
        txTemplate().executeWithoutResult(status -> {
            createUser(ADMIN_USER, "admin-pass");
            createUser(DENIED_USER, "bob-pass");
            // dave: право на эндпоинт + wildcard-чтение JOURNAL -> guard пропускает
            createUser(ALLOWED_USER, "dave-pass");
            persistGrant(ALLOWED_USER, "REPORTS:JPQL_PREVIEW", null);
            persistGrant(ALLOWED_USER, "JOURNAL", null);
            // bob: право на эндпоинт есть, RLS-грантов на JOURNAL нет
            persistGrant(DENIED_USER, "REPORTS:JPQL_PREVIEW", null);
            entityManager.flush();
        });
    }

    private org.springframework.transaction.support.TransactionTemplate txTemplate() {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        txTemplate().executeWithoutResult(status -> {
            entityManager.createQuery(
                    "delete from AccessGrant g where g.subjectKey in :keys")
                .setParameter("keys", OWN_USERS)
                .executeUpdate();
            for (String username : OWN_USERS) {
                userRepository.findByUsername(username).ifPresent(userRepository::delete);
            }
            entityManager.flush();
        });
    }

    @Test
    void unauthorizedWithoutCredentials() throws Exception {
        mockMvc.perform(post(URL)
                        .contentType("application/json")
                        .content("{\"jpql\":\"select j.id from Journal j\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forbiddenWithoutJpqlPreviewGrant() throws Exception {
        txTemplate().executeWithoutResult(status -> createUser(GRANTLESS_USER, "carol-pass"));
        mockMvc.perform(post(URL)
                        .header("Authorization", basic(GRANTLESS_USER, "carol-pass"))
                        .contentType("application/json")
                        .content("{\"jpql\":\"select j.id from Journal j\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(
                        "Нет права " + ReportJpqlPreviewController.JPQL_PREVIEW_DIMENSION));
    }

    @Test
    void returnsColumnsAndRowsForAllowedUser() throws Exception {
        mockMvc.perform(post(URL)
                        .header("Authorization", basic(ALLOWED_USER, "dave-pass"))
                        .contentType("application/json")
                        .content("""
                                {"jpql":"select j.id as id from Journal j",
                                 "maxRows":100}""")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].name").value("id"))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void guardDenialIs400WithReadableMessage() throws Exception {
        // bob: право на эндпоинт есть, но RLS-доступ к Journal закрыт -> отказ guard'а
        String content = mockMvc.perform(post(URL)
                        .header("Authorization", basic(DENIED_USER, "bob-pass"))
                        .contentType("application/json")
                        .content("{\"jpql\":\"select j.id as id from Journal j\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content).contains("отклонён");
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private void createUser(String username, String rawPassword) {
        if (userRepository.findByUsername(username).isPresent()) {
            return;
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setEnabled(true);
        user.setRoles(Set.of());
        userRepository.save(user);
    }

    private void persistGrant(String subjectKey, String dimension, Long dimensionValueId) {
        AccessGrant grant = new AccessGrant();
        grant.setSubjectType(AccessGrant.SubjectType.USER);
        grant.setSubjectKey(subjectKey);
        grant.setDimension(dimension);
        grant.setDimensionValueId(dimensionValueId);
        grant.setCanRead(true);
        grant.setCanUpdate(false);
        grant.setCanDelete(false);
        entityManager.persist(grant);
    }
}
