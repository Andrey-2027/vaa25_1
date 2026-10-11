package org.ipro.rls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADR-0017 §9: финальная граница записи для типов без RLS.
 *
 * <p>Тип с canonical write handle и без измерений прежде не имел никакой финальной проверки:
 * {@code requireAuthorized} выходил сразу при {@code !protectedEntity()}, поэтому прямой
 * {@code EntityManager} в обход canonical write был незакрытым входом. Здесь проверяется, что
 * граница (а) требует зафиксированную авторизацию, (б) потребляет её ровно один раз,
 * (в) различает операции, (г) не подменяет строковую проверку типа с RLS и (д) не трогает
 * тип без canonical handle — иначе она закрывала бы владельческие пути записи.</p>
 *
 * <p>Реестр приходит параметром: граница перестала быть статикой, поэтому «инертность»
 * проверяется как {@code null}-реестр, а не как снятая установка.</p>
 */
class RlsWriteGuardBridgeTest {

    private RlsDimensionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = mock(RlsDimensionRegistry.class);
        // По умолчанию: тип не защищён измерениями и не ведётся canonical write.
        when(registry.policyOf(any())).thenAnswer(invocation ->
            new RlsPolicyDescriptor(invocation.getArgument(0), true, Map.of(), Map.of()));
        when(registry.isWriteGoverned(any())).thenReturn(false);
        RlsWriteAuthorization.clear();
    }

    @AfterEach
    void tearDown() {
        RlsWriteAuthorization.clear();
    }

    @Test
    void governedTypeWithoutRlsIsDeniedOnFlushWithoutFixedAuthorization() {
        govern(UnprotectedGoverned.class);

        assertThatThrownBy(() -> RlsWriteGuardBridge.requireAuthorized(registry,
                new UnprotectedGoverned(), RlsWriteAuthorization.Operation.WRITE))
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("without an authorization fixed by the canonical write preflight")
            .hasMessageContaining(UnprotectedGoverned.class.getName());
    }

    @Test
    void fixedAuthorizationOpensTheFlushAndIsConsumedExactlyOnce() {
        govern(UnprotectedGoverned.class);
        UnprotectedGoverned entity = new UnprotectedGoverned();

        RlsWriteGuardBridge.recordCanonicalWrite(registry, entity);

        assertThatCode(() -> RlsWriteGuardBridge.requireAuthorized(registry, entity,
                RlsWriteAuthorization.Operation.WRITE))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> RlsWriteGuardBridge.requireAuthorized(registry, entity,
                RlsWriteAuthorization.Operation.WRITE))
            .as("авторизация одноразовая: второй flush того же объекта снова обязан её предъявить")
            .isInstanceOf(RlsAccessDeniedException.class);
    }

    @Test
    void deleteFlushIsNotSatisfiedByWriteAuthorization() {
        govern(UnprotectedGoverned.class);
        UnprotectedGoverned entity = new UnprotectedGoverned();

        RlsWriteGuardBridge.recordCanonicalWrite(registry, entity);

        assertThatThrownBy(() -> RlsWriteGuardBridge.requireAuthorized(registry, entity,
                RlsWriteAuthorization.Operation.DELETE))
            .isInstanceOf(RlsAccessDeniedException.class);
    }

    @Test
    void canonicalWriteRecordsDeleteAuthorizationsSeparately() {
        govern(UnprotectedGoverned.class);
        UnprotectedGoverned entity = new UnprotectedGoverned();

        RlsWriteGuardBridge.recordCanonicalDelete(registry, entity);

        assertThatCode(() -> RlsWriteGuardBridge.requireAuthorized(registry, entity,
                RlsWriteAuthorization.Operation.DELETE))
            .doesNotThrowAnyException();
    }

    @Test
    void typeWithoutCanonicalHandleKeepsOwnerWritePathOpen() {
        UnprotectedGoverned entity = new UnprotectedGoverned();

        assertThatCode(() -> RlsWriteGuardBridge.requireAuthorized(registry, entity,
                RlsWriteAuthorization.Operation.WRITE))
            .as("у типа нет canonical handle: его пишет владелец, границы записи C5 у него нет")
            .doesNotThrowAnyException();
    }

    @Test
    void canonicalWriteDoesNotSubstituteTheRowLevelCheck() {
        govern(ProtectedCarrier.class);
        when(registry.policyOf(ProtectedCarrier.class)).thenReturn(protectedDescriptor());
        ProtectedCarrier entity = new ProtectedCarrier();

        RlsWriteGuardBridge.recordCanonicalWrite(registry, entity);

        assertThatThrownBy(() -> RlsWriteGuardBridge.requireAuthorized(registry, entity,
                RlsWriteAuthorization.Operation.WRITE))
            .as("тип с RLS обязан предъявить проверку строки, а не пустую авторизацию canonical write")
            .isInstanceOf(RlsAccessDeniedException.class)
            .hasMessageContaining("without an RLS authorization");
    }

    @Test
    void bridgeWithoutRegistryIsInert() {
        assertThatCode(() -> RlsWriteGuardBridge.requireAuthorized(null,
                new UnprotectedGoverned(), RlsWriteAuthorization.Operation.WRITE))
            .doesNotThrowAnyException();
        assertThatCode(() -> RlsWriteGuardBridge.recordCanonicalWrite(null,
                new UnprotectedGoverned()))
            .doesNotThrowAnyException();
    }

    private void govern(Class<?> type) {
        when(registry.isWriteGoverned(type)).thenReturn(true);
    }

    private static RlsPolicyDescriptor protectedDescriptor() {
        RlsPolicyDescriptor.ValueRule rule = new RlsPolicyDescriptor.ValueRule(List.of(), true, true);
        return new RlsPolicyDescriptor(ProtectedCarrier.class, true,
            Map.of("JOURNAL", RlsDimensionKind.CHECK_ONLY), Map.of("JOURNAL", rule));
    }

    /** Тип с canonical handle и без измерений: ровно тот случай, который был не закрыт. */
    static final class UnprotectedGoverned {

        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }

    /** Тип с измерениями: его запись решается строкой, а не авторизацией canonical write. */
    static final class ProtectedCarrier implements RlsDimensionValue {

        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        @Override
        public Map<String, List<RlsCheckValue>> getRlsChecks() {
            return Map.of("JOURNAL", List.of(RlsCheckValue.notApplicable()));
        }
    }
}
