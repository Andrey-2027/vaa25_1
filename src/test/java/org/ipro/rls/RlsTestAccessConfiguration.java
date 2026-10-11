package org.ipro.rls;

import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * Test-only: связывает реестр определений операций с {@link RlsTestFixture}.
 *
 * <p>Синтетический суперпользователь тестов обязан получить те же операционные гранты, какие
 * в приложении выдаются администратору при первичной выдаче: построчный wildcard {@code "*"}
 * операции не авторизует (ADR-0017), поэтому одного wildcard для тестов записи больше
 * недостаточно. Привязка делается отдельным бином после создания реестра, чтобы не зависеть
 * от порядка обработки auto-configuration: без привязки фикстура выдаёт только построчный
 * доступ, и запись отказывает — fail-closed, а не тихое расширение прав в тестовом окружении.
 */
@AutoConfiguration(afterName = "org.ipro.rls.config.RlsAutoConfiguration")
@ConditionalOnBean(C5OperationPermissionRegistry.class)
public class RlsTestAccessConfiguration {

    @Bean
    RlsTestOperationGrantBinding rlsTestOperationGrantBinding(C5OperationPermissionRegistry registry) {
        return new RlsTestOperationGrantBinding(registry);
    }

    /** Момент привязки: создание бина уже видит определения, зарегистрированные платформой. */
    static final class RlsTestOperationGrantBinding {

        RlsTestOperationGrantBinding(C5OperationPermissionRegistry registry) {
            RlsTestFixture.bindRegisteredOperations(registry);
        }
    }
}
