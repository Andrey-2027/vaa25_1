package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Квота параллельных запусков отчётов (Фаза 2): максимум одновременно
 * выполняющихся формирований, сообщение при переполнении, освобождение слотов.
 */
class ReportRunQuotaTest {

    @Test
    void allowsUpToLimitParallelRuns() {
        ReportRunQuota quota = new ReportRunQuota(2);

        ReportRunQuota.Slot first = quota.tryAcquire(0);
        ReportRunQuota.Slot second = quota.tryAcquire(0);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(quota.activeRuns()).isEqualTo(2);
    }

    @Test
    void rejectsBeyondLimit() {
        ReportRunQuota quota = new ReportRunQuota(1);

        ReportRunQuota.Slot first = quota.tryAcquire(0);
        ReportRunQuota.Slot rejected = quota.tryAcquire(0);

        assertThat(first).isNotNull();
        assertThat(rejected).isNull();
        assertThat(quota.busyMessage()).contains("1 из 1");
    }

    @Test
    void releaseFreesSlot() {
        ReportRunQuota quota = new ReportRunQuota(1);

        ReportRunQuota.Slot first = quota.tryAcquire(0);
        assertThat(quota.tryAcquire(0)).isNull();

        first.release();
        assertThat(quota.activeRuns()).isZero();
        assertThat(quota.tryAcquire(0)).isNotNull();
    }

    @Test
    void parallelReleasesBalanceCounter() {
        ReportRunQuota quota = new ReportRunQuota(3);
        List<ReportRunQuota.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            slots.add(quota.tryAcquire(0));
        }
        assertThat(slots).doesNotContainNull();

        slots.get(0).release();
        slots.get(2).release();
        assertThat(quota.activeRuns()).isEqualTo(1);

        slots.get(1).release();
        assertThat(quota.activeRuns()).isZero();
        assertThat(quota.busyMessage()).contains("0 из 3");
    }

    @Test
    void zeroLimitIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new ReportRunQuota(0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void waitingAcquireSucceedsAfterRelease() throws InterruptedException {
        ReportRunQuota quota = new ReportRunQuota(1);
        ReportRunQuota.Slot holder = quota.tryAcquire(0);

        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(100);
                holder.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        releaser.start();

        ReportRunQuota.Slot waiting = quota.tryAcquire(500);
        assertThat(waiting).isNotNull();
        waiting.release();
        releaser.join(1000);
    }
}