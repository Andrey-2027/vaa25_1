package org.ipro.register.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.Validator;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EntityEventPublisher;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.register.RecorderOrderClockBootstrap;
import org.ipro.register.RegisterKeyChangePermissions;
import org.ipro.register.RegisterKeyChanges;
import org.ipro.register.RegisterKeyWriteGuardInstaller;
import org.ipro.register.RegisterKeyWritePermissions;
import org.ipro.register.RegisterKeyWrites;
import org.ipro.register.RegisterNamedOperations;
import org.ipro.register.RegisterRegistry;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.RlsPolicyEnforcer;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.telemetry.api.EventSink;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * D-R S2 — конфигурация записи по ключу: команды ключа, их именованные операции, охранник
 * mapping'а и часы порядка подчинённых регистров.
 *
 * <p><b>Почему срез привёз часы порядка (развилка плана).</b> План оставил решение на старте S2:
 * либо {@code RecorderOrderClock} переезжает вместе с командой, либо мьютекс записи ждёт S3.
 * Замер среза показал ровно одну ссылку за границу — {@code RegisterWriteMutex} →
 * {@code persistence.RecorderOrderClock}, — и ссылка эта рабочая, а не декоративная:
 * {@code RegisterKeyChanges} берёт под этим мьютексом ключ подчинённого регистра, чтобы смена
 * ключа не разошлась с заменой набора владельца (S3). Оставить мьютекс в приложении означало бы
 * оставить в приложении и часть самой команды, то есть срез, который «вынесен наполовину».
 * Поэтому часы порядка и их засев переехали вместе с командой, а {@code @EntityScan} пакета
 * {@code org.ipro.register.persistence} приехал вместе с сущностью — владелец сущности не может
 * жить в другом артефакте, чем сама сущность.</p>
 *
 * <p><b>Что здесь не объявлено и почему:</b> протокол набора владельца ({@code RegisterRecords},
 * {@code RecorderSetWriter} и участники) остаётся приложению до S3. Он пользуется теми же часами
 * порядка через {@code RegisterWriteMutex} — то есть S3 не изобретает второй механизм, а приходит
 * к готовому и уже проверенному.</p>
 *
 * <p>Засев часов — не «приложение настроит само»: без строки порядка первый же набор владельца и
 * первая же смена ключа подчинённой строки отказали бы ({@code RECORDER_ORDER_CLOCK_MISSING}),
 * поэтому засев объявлен здесь и вызывается на старте. Долг S1 («засев остаётся приложению,
 * пока сущность принадлежит приложению») этим закрыт: он переехал ровно тогда, когда переехала
 * сущность, а не был тихо потерян.</p>
 */
@AutoConfiguration
@EntityScan("org.ipro.register.persistence")
public class RegisterKeyWriteConfiguration {

    /**
     * Охранник mapping'а и UPDATE-событий: поля ключа неизменяемы для обычных путей, а смена ключа
     * возможна только под разрешением команды. Сначала полная проверка деклараций — иначе Hibernate
     * упал бы на первой сломанной связи, не рассказав про остальные.
     */
    @Bean
    public RegisterKeyWriteGuardInstaller registerKeyWriteGuardInstaller(EntityManagerFactory factory,
            RegisterRegistry registers) {
        registers.initialize();
        return new RegisterKeyWriteGuardInstaller(factory);
    }

    /**
     * Подписи именованных операций регистра (ADR-0024 §2.4): имя объявлено декларацией, текст
     * собирает платформа. Бин живёт в том же срезе, что и сами операции: иначе подпись обещала бы
     * операцию, которой в артефакте нет. Приложение читает список как данные — своей строки текста
     * и своего бина на регистр у него больше нет.
     */
    @Bean
    public RegisterNamedOperations registerNamedOperations(RegisterRegistry registers) {
        return new RegisterNamedOperations(registers);
    }

    /** Именованные операции смены ключа: регистрация определения не выдаёт грант. */
    @Bean
    public RegisterKeyChangePermissions registerKeyChangePermissions(RegisterRegistry registers,
            C5OperationPermissionRegistry operations, RlsDimensionRegistry dimensions,
            C5PermissionDecisionService decisions, RlsCurrentUser currentUser, AccessService access) {
        return new RegisterKeyChangePermissions(registers, operations, dimensions, decisions, currentUser, access);
    }

    /** Команда смены ключа существующей строки (ADR-0021). */
    @Bean
    public RegisterKeyChanges registerKeyChanges(EntityManager entityManager, RegisterRegistry registers,
            RegisterKeyChangePermissions permissions, EntityDataAccess reads, RlsPolicyEnforcer policies,
            Validator validator, EntityLifecycleRegistry lifecycles, EntityEventPublisher events,
            RlsCurrentUser currentUser, java.util.Optional<EventSink> audit,
            PlatformTransactionManager transactionManager) {
        return new RegisterKeyChanges(entityManager, registers, permissions, reads, policies, validator,
            lifecycles, events, currentUser, audit, transactionManager);
    }

    /** Именованные операции записи и удаления по ключу (ADR-0023), отдельно от смены ключа. */
    @Bean
    public RegisterKeyWritePermissions registerKeyWritePermissions(RegisterRegistry registers,
            C5OperationPermissionRegistry operations, RlsDimensionRegistry dimensions,
            C5PermissionDecisionService decisions, RlsCurrentUser currentUser) {
        return new RegisterKeyWritePermissions(registers, operations, dimensions, decisions, currentUser);
    }

    /**
     * Запись по ключу (ADR-0023): автономная команда с bounded retry. Владелец повтора —
     * граница транзакции, поэтому бин не прячет чужую транзакцию и не открывает REQUIRES_NEW.
     */
    @Bean
    public RegisterKeyWrites registerKeyWrites(EntityManager entityManager, RegisterRegistry registers,
            RegisterKeyWritePermissions permissions, EntityDataAccess reads, RlsPolicyEnforcer policies,
            Validator validator, EntityLifecycleRegistry lifecycles, EntityEventPublisher events,
            RlsCurrentUser currentUser, java.util.Optional<EventSink> audit,
            PlatformTransactionManager transactionManager) {
        return new RegisterKeyWrites(entityManager, registers, permissions, reads, policies, validator,
            lifecycles, events, currentUser, audit, transactionManager);
    }

    /** Засев часов порядка: одна транзакционная строка на каждый подчинённый регистр. */
    @Bean
    public RecorderOrderClockBootstrap recorderOrderClockBootstrap(EntityManagerFactory entityManagerFactory,
            PlatformTransactionManager transactionManager) {
        return new RecorderOrderClockBootstrap(entityManagerFactory, transactionManager);
    }

    @Bean
    public SmartInitializingSingleton recorderOrderClockStartup(RegisterRegistry registers,
            RecorderOrderClockBootstrap clocks) {
        return () -> clocks.initialize(registers.descriptors());
    }
}
