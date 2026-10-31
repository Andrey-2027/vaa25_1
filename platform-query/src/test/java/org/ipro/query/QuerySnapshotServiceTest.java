package org.ipro.query;

import org.ipro.report.data.QueryField;
import org.ipro.report.data.ReportDataset;
import org.ipro.report.data.ReportDataset.ReferenceColumn;
import org.ipro.report.data.ReportRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuerySnapshotServiceTest {

    private static final QueryField[] FIELDS = {QueryField.scalar("code", String.class)};

    @Test
    void requestsOneRowMoreThanLimitAndKeepsShortResultWhole() {
        AtomicInteger requested = new AtomicInteger();
        QuerySnapshot snapshot = service(requested, rows(2)).snapshot(assembled(), 2, 1_000);

        assertEquals(3, requested.get());
        assertEquals(2, snapshot.rowCount());
        assertFalse(snapshot.truncated());
    }

    @Test
    void marksTruncatedWhenProbeHasExtraRowAndDropsIt() {
        QuerySnapshot snapshot = service(new AtomicInteger(), rows(3)).snapshot(assembled(), 2, 1_000);

        assertTrue(snapshot.truncated());
        assertEquals(2, snapshot.rowCount());
        assertEquals("r0", snapshot.dataset().rows()[0].displayValue("code"));
        assertEquals("r1", snapshot.dataset().rows()[1].displayValue("code"));
    }

    @Test
    void exactlyLimitRowsIsNotTruncated() {
        QuerySnapshot snapshot = service(new AtomicInteger(), rows(2)).snapshot(assembled(), 2, 1_000);

        assertFalse(snapshot.truncated());
        assertEquals(2, snapshot.rowCount());
    }

    @Test
    void emptyResultIsNotTruncated() {
        QuerySnapshot snapshot = service(new AtomicInteger(), new ReportDataset(FIELDS, new ReportRow[0]))
                .snapshot(assembled(), 2, 1_000);

        assertFalse(snapshot.truncated());
        assertEquals(0, snapshot.rowCount());
    }

    @Test
    void truncationCopiesReferenceColumnsToTheSameLength() {
        ReportDataset probe = new ReportDataset(FIELDS, rows(3).rows(),
                List.of(new ReferenceColumn("id", Long.class, List.of(1L, 2L, 3L))));

        QuerySnapshot snapshot = service(new AtomicInteger(), probe).snapshot(assembled(), 2, 1_000);

        assertTrue(snapshot.truncated());
        assertEquals(2, snapshot.dataset().referenceColumns().getFirst().values().size());
        assertEquals(1L, snapshot.dataset().referenceKey("id", 0));
        assertEquals(2L, snapshot.dataset().referenceKey("id", 1));
    }

    private static QuerySnapshotService service(AtomicInteger requested, ReportDataset dataset) {
        return new QuerySnapshotService((assembled, maxRows, timeoutMs) -> {
            requested.set(maxRows);
            return dataset;
        });
    }

    private static ReportQueryAssembler assembled() {
        return new ReportQueryAssembler("select s.code from Spec s", Map.of(), List.of(FIELDS), List.of());
    }

    private static ReportDataset rows(int count) {
        ReportRow[] rows = new ReportRow[count];
        for (int i = 0; i < count; i++) {
            rows[i] = new ReportRow(FIELDS, new Object[]{"r" + i});
        }
        return new ReportDataset(FIELDS, rows);
    }
}
