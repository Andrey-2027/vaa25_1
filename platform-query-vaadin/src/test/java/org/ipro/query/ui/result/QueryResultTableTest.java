package org.ipro.query.ui.result;

import com.vaadin.flow.data.provider.SortDirection;
import org.ipro.query.QuerySnapshot;
import org.ipro.report.data.QueryField;
import org.ipro.report.data.ReportDataset;
import org.ipro.report.data.ReportRow;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryResultTableTest {

    private static final QueryField CODE = QueryField.scalar("code", String.class);
    private static final QueryField NAME = QueryField.scalar("name", String.class);
    private static final QueryField[] FIELDS = {CODE, NAME};

    @Test
    void showsShortSnapshotWithoutTruncationClaim() {
        QueryResultTable table = new QueryResultTable();
        table.show(new QuerySnapshot(dataset(2), false, 50));

        assertEquals(2, table.grid().getListDataView().getItems().count());
        assertEquals("Получено 2 строк. Сортировка и фильтр действуют на снимок, а не на запрос.",
                table.summaryText());
        assertTrue(table.filterField().isVisible());
    }

    @Test
    void showsEmptySnapshotAsZeroRows() {
        QueryResultTable table = new QueryResultTable();
        table.show(new QuerySnapshot(dataset(0), false, 50));

        assertEquals(0, table.grid().getListDataView().getItems().count());
        assertTrue(table.summaryText().startsWith("Получено 0 строк."));
    }

    @Test
    void truncatedSnapshotSaysHowManyRowsAreShownAndWhatTheLimitIs() {
        QueryResultTable table = new QueryResultTable();
        table.show(new QuerySnapshot(dataset(3), true, 3));

        assertEquals(3, table.grid().getListDataView().getItems().count());
        assertTrue(table.summaryText().startsWith("Показано 3 строк: результат больше лимита 3."));
    }

    @Test
    void filterAppliesOnlyToTheReceivedSnapshotAndSaysSo() {
        QueryResultTable table = new QueryResultTable();
        table.show(new QuerySnapshot(dataset(3), true, 3));

        table.filterField().setValue("Al");

        assertEquals(1, table.grid().getListDataView().getItems().count());
        assertEquals("r2", table.grid().getListDataView().getItems().findFirst().orElseThrow().displayValue("code"));
        assertTrue(table.summaryText().contains("Фильтр по снимку: 1 из 3."));
        assertTrue(table.summaryText().contains("а не на запрос"));
    }

    @Test
    void clearingFilterRestoresTheWholeSnapshot() {
        QueryResultTable table = new QueryResultTable();
        table.show(new QuerySnapshot(dataset(3), false, 50));
        table.filterField().setValue("r2");

        table.filterField().clear();

        assertEquals(3, table.grid().getListDataView().getItems().count());
        assertFalse(table.summaryText().contains("Фильтр по снимку"));
    }

    @Test
    void sortingUsesTheTypedValueNotTheDisplayString() {
        QueryField amount = QueryField.scalar("amount", Long.class);
        QueryField[] fields = {amount};
        ReportRow ten = new ReportRow(fields, new Object[]{10L});
        ReportRow nine = new ReportRow(fields, new Object[]{9L});
        QueryResultTable table = new QueryResultTable();

        table.show(new QuerySnapshot(new ReportDataset(fields, new ReportRow[]{ten, nine}), false, 50));
        Comparator<ReportRow> order = table.grid().getColumnByKey("amount")
                .getComparator(SortDirection.ASCENDING);

        assertThat(order.compare(nine, ten)).isNegative();
        assertThat(order.compare(ten, nine)).isPositive();
    }

    private static ReportDataset dataset(int count) {
        ReportRow[] rows = new ReportRow[count];
        for (int i = 0; i < count; i++) {
            rows[i] = new ReportRow(FIELDS, new Object[]{"r" + i, i == 2 ? "Alpha" : "Beta"});
        }
        return new ReportDataset(FIELDS, rows);
    }
}
