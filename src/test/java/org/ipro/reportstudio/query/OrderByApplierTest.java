package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderByApplierTest {

    @Test
    void noOrderByAppendsPrefix() {
        assertEquals(
            "select s.code from PrdSpec s order by s.journal.code",
            OrderByApplier.withGroupOrderBy("select s.code from PrdSpec s", List.of("s.journal.code")));
    }

    @Test
    void existingOrderByGetsPrefixBefore() {
        assertEquals(
            "select s.code from PrdSpec s order by s.journal.code, s.code",
            OrderByApplier.withGroupOrderBy(
                "select s.code from PrdSpec s order by s.code", List.of("s.journal.code")));
    }

    @Test
    void nestedGroupsOuterFirst() {
        assertEquals(
            "select s from S s order by s.journal.code, s.nomenclature.code",
            OrderByApplier.withGroupOrderBy("select s from S s",
                List.of("s.journal.code", "s.nomenclature.code")));
    }

    @Test
    void emptyGroupFieldsLeavesJpqlUntouched() {
        String jpql = "select s from S s order by s.code";
        assertEquals(jpql, OrderByApplier.withGroupOrderBy(jpql, List.of()));
        assertEquals(jpql, OrderByApplier.withGroupOrderBy(jpql, null));
    }

    @Test
    void nullAndBlankGroupFieldsSkipped() {
        assertEquals(
            "select s from S s",
            OrderByApplier.withGroupOrderBy("select s from S s",
                new java.util.ArrayList<>(java.util.Arrays.asList(null, "  "))));
        assertEquals(
            "select s from S s order by s.journal.code",
            OrderByApplier.withGroupOrderBy("select s from S s",
                new java.util.ArrayList<>(java.util.Arrays.asList(null, "  ", "s.journal.code"))));
    }

    @Test
    void duplicateGroupFieldsDeduplicated() {
        assertEquals(
            "select s from S s order by s.journal.code",
            OrderByApplier.withGroupOrderBy("select s from S s", List.of("s.journal.code", "s.journal.code")));
    }

    @Test
    void caseInsensitiveOrderByRecognized() {
        assertEquals(
            "select s from S s ORDER BY s.journal.code, s.code",
            OrderByApplier.withGroupOrderBy("select s from S s ORDER BY s.code", List.of("s.journal.code")));
    }

    @Test
    void orderByInsideFunctionCallIgnored() {
        String jpql = "select string_agg(s.code, ',' order by s.code) from S s";
        assertEquals(jpql + " order by s.journal.code",
            OrderByApplier.withGroupOrderBy(jpql, List.of("s.journal.code")));
    }

    @Test
    void orderByInsideStringLiteralIgnored() {
        String jpql = "select 'order by' from S s";
        assertEquals(jpql + " order by s.journal.code",
            OrderByApplier.withGroupOrderBy(jpql, List.of("s.journal.code")));
    }

    @Test
    void findOrderByReturnsPosition() {
        assertEquals(-1, OrderByApplier.findOrderBy("select s from S s"));
        assertEquals(27, OrderByApplier.findOrderBy("select s from S s order by s.code"));
        assertEquals(-1, OrderByApplier.findOrderBy("select s from S s where s.note = 'order by x'"));
        assertEquals(-1, OrderByApplier.findOrderBy("select string_agg(s.code, ',' order by s.code) from S s"));
    }

    @Test
    void userSortsAppendAfterGroupFields() {
        assertEquals(
            "select s from S s order by s.journal.code, s.code desc, s.qty",
            OrderByApplier.withOrderBy("select s from S s", List.of("s.journal.code"),
                List.of(new OrderByApplier.OrderSpec("s.code", true),
                        new OrderByApplier.OrderSpec("s.qty", false))));
    }

    @Test
    void userSortForGroupFieldIsSkipped() {
        assertEquals(
            "select s from S s order by s.journal.code, s.qty",
            OrderByApplier.withOrderBy("select s from S s", List.of("s.journal.code"),
                List.of(new OrderByApplier.OrderSpec("s.journal.code", true),
                        new OrderByApplier.OrderSpec("s.qty", false))));
    }

    @Test
    void firstOrderOnDuplicateWins() {
        assertEquals(
            "select s from S s order by s.code",
            OrderByApplier.withOrderBy("select s from S s", List.of(),
                List.of(new OrderByApplier.OrderSpec("s.code", false),
                        new OrderByApplier.OrderSpec("s.code", true))));
    }

    @Test
    void existingOrderByKeepsUserSortsPrefix() {
        assertEquals(
            "select s from S s order by s.journal.code, s.code desc, s.extra",
            OrderByApplier.withOrderBy("select s from S s order by s.extra", List.of("s.journal.code"),
                List.of(new OrderByApplier.OrderSpec("s.code", true))));
    }

    @Test
    void nullOrBlankOrderSpecSkipped() {
        assertEquals(
            "select s from S s",
            OrderByApplier.withOrderBy("select s from S s", List.of(),
                java.util.Arrays.asList(null, new OrderByApplier.OrderSpec("", true),
                        new OrderByApplier.OrderSpec(null, false))));
    }
}