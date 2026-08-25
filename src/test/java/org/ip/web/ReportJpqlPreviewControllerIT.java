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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ReportJpqlPreviewControllerIT {

    private static final String URL = "/api/report-jpql/preview";

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
            createUser("admin", "admin-pass");
            createUser("bob", "bob-pass");
            // dave: право на эндпоинт + wildcard-чтение JOURNAL -> guard пропускает
            createUser("dave", "dave-pass");
            persistGrant("dave", "REPORTS:JPQL_PREVIEW", null);
            persistGrant("dave", "JOURNAL", null);
            // bob: право на эндпоинт есть, RLS-грантов на JOURNAL нет
            persistGrant("bob", "REPORTS:JPQL_PREVIEW", null);
            entityManager.flush();
        });
    }

    private org.springframework.transaction.support.TransactionTemplate txTemplate() {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    @AfterEach
    void tearDown() {
        txTemplate().executeWithoutResult(status -> {
            userRepository.findByUsername("carol").ifPresent(userRepository::delete);
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
        txTemplate().executeWithoutResult(status -> createUser("carol", "carol-pass"));
        mockMvc.perform(post(URL)
                        .header("Authorization", basic("carol", "carol-pass"))
                        .contentType("application/json")
                        .content("{\"jpql\":\"select j.id from Journal j\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(
                        "Нет права " + ReportJpqlPreviewController.JPQL_PREVIEW_DIMENSION));
    }

    @Test
    void returnsColumnsAndRowsForAllowedUser() throws Exception {
        mockMvc.perform(post(URL)
                        .header("Authorization", basic("dave", "dave-pass"))
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
                        .header("Authorization", basic("bob", "bob-pass"))
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
