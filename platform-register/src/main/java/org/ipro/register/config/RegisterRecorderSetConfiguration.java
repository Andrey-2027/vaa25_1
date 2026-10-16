package org.ipro.register.config;

import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.ipro.crud.AggregateSaveContributionParticipant;
import org.ipro.data.CanonicalDeleteParticipant;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.register.RecorderSetContributionParticipant;
import org.ipro.register.RecorderSetDeleteParticipant;
import org.ipro.register.RecorderSetWriteAuthorizer;
import org.ipro.register.RecorderTypeRegistry;
import org.ipro.register.RegisterRecords;
import org.ipro.register.RegisterRegistry;
import org.ipro.register.RlsRecorderSetWriteAuthorizer;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * D-R S3 — протокол набора владельца: процедуры записи подчинённых регистров, участие в
 * canonical сохранении и удалении агрегата-владельца и шов авторизации этой записи.
 *
 * <p>Последний срез выноса закрывает прикладную конфигурацию целиком: после него у приложения не
 * остаётся ни одной регистрации регистра, а запись в {@code AutoConfiguration.imports} исчезает —
 * не потому что «так планировалось», а потому что привозить больше нечего: чтение (S1), запись по
 * ключу с часами порядка (S2) и набор владельца (S3) приехали своими артефактами, и у каждого
 * свой {@code @AutoConfiguration}.</p>
 *
 * <p>Почему набор владельца не мог уехать раньше: он держится за canonical-сохранение
 * документа-владельца ({@code RegisterRecords.forRecorder} вызывается только из {@code onSave} под
 * callback-токеном агрегата или canonical-сохранения) и участвует в удалении агрегата как
 * {@code CanonicalDeleteParticipant}. Оба шва платформенные, поэтому срез — перемещение, а не
 * переписывание протокола.</p>
 *
 * <p>Шов авторизации набора ({@code RecorderSetWriteAuthorizer}) остаётся заменяемым
 * ({@code @ConditionalOnMissingBean}): ADR-0019 требует, чтобы решение об операции строки принимал
 * владелец, и платформенная реализация здесь — рабочая, а не единственная возможная.</p>
 *
 * <p><b>ADR-0025.</b> Все три бина получают {@link EntityLifecycleRegistry}: путь набора больше не
 * обходит lifecycle строки. Реестр — обязательный параметр, а не необязательный: «нет реестра»
 * означало бы возврат к тихому обходу правил, ради устранения которого решение и принималось.</p>
 */
@AutoConfiguration
public class RegisterRecorderSetConfiguration {

    @Bean
    @ConditionalOnMissingBean(RecorderSetWriteAuthorizer.class)
    public RecorderSetWriteAuthorizer recorderSetWriteAuthorizer(RlsPolicyEnforcer rlsPolicyEnforcer,
            C5PermissionDecisionService permissions, RlsCurrentUser currentUser) {
        return new RlsRecorderSetWriteAuthorizer(rlsPolicyEnforcer, permissions, currentUser);
    }

    @Bean
    public RegisterRecords registerRecords(EntityManager entityManager, RegisterRegistry registers,
            RecorderTypeRegistry recorderTypes, Validator validator, EntityLifecycleRegistry lifecycles,
            RlsFilterActivator filters,
            RecorderSetWriteAuthorizer authorizer, PlatformTransactionManager transactionManager) {
        return new RegisterRecords(entityManager, registers, recorderTypes, validator, lifecycles, filters,
            authorizer, transactionManager);
    }

    @Bean
    public AggregateSaveContributionParticipant recorderSetContributionParticipant(
            EntityManager entityManager, RegisterRegistry registers,
            RecorderTypeRegistry recorderTypes, Validator validator,
            EntityLifecycleRegistry lifecycles,
            RlsFilterActivator rlsFilterActivator, RecorderSetWriteAuthorizer writeAuthorizer) {
        return new RecorderSetContributionParticipant(entityManager, registers, recorderTypes,
            validator, lifecycles, rlsFilterActivator, writeAuthorizer);
    }

    @Bean
    public CanonicalDeleteParticipant recorderSetDeleteParticipant(EntityManager entityManager,
            RegisterRegistry registers, RecorderTypeRegistry recorderTypes,
            Validator validator, EntityLifecycleRegistry lifecycles,
            RlsFilterActivator rlsFilterActivator, RecorderSetWriteAuthorizer writeAuthorizer) {
        return new RecorderSetDeleteParticipant(entityManager, registers, recorderTypes,
            validator, lifecycles, rlsFilterActivator, writeAuthorizer);
    }
}
