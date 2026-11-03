package org.ipro.design.source;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedDiffTest {
    private static String lines(int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(i -> "line" + i + "\n").collect(Collectors.joining());
    }

    private static long hunks(String diff) { return diff.lines().filter(line -> line.startsWith("@@")).count(); }

    @Test void equalTextsHaveNoDiff() {
        assertEquals("", UnifiedDiff.diff("f", "a\nb\n", "a\nb\n"));
    }

    @Test void aReplacedLineIsShownWithItsContext() {
        assertEquals("--- a/f\n+++ b/f\n@@ -1,3 +1,3 @@\n a\n-b\n+X\n c\n", UnifiedDiff.diff("f", "a\nb\nc\n", "a\nX\nc\n"));
    }

    @Test void anInsertionAtTheStartAndInAnEmptyFile() {
        assertEquals("--- a/f\n+++ b/f\n@@ -1,1 +1,2 @@\n+n\n a\n", UnifiedDiff.diff("f", "a\n", "n\na\n"));
        assertEquals("--- a/f\n+++ b/f\n@@ -0,0 +1,1 @@\n+a\n", UnifiedDiff.diff("f", "", "a\n"));
        assertEquals("--- a/f\n+++ b/f\n@@ -1,1 +0,0 @@\n-a\n", UnifiedDiff.diff("f", "a\n", ""));
    }

    @Test void onlyThreeLinesOfContextAreShown() {
        String diff = UnifiedDiff.diff("f", lines(1, 20), lines(1, 10) + "added\n" + lines(11, 20));
        assertEquals("--- a/f\n+++ b/f\n@@ -8,6 +8,7 @@\n line8\n line9\n line10\n+added\n line11\n line12\n line13\n", diff);
    }

    @Test void changesFarApartAreSeparateHunksAndNearOnesAreOne() {
        String before = lines(1, 30);
        assertEquals(2, hunks(UnifiedDiff.diff("f", before, before.replace("line3\n", "x\n").replace("line27\n", "y\n"))));
        assertEquals(1, hunks(UnifiedDiff.diff("f", before, before.replace("line3\n", "x\n").replace("line9\n", "y\n"))));
        assertEquals(2, hunks(UnifiedDiff.diff("f", before, before.replace("line3\n", "x\n").replace("line11\n", "y\n"))));
    }

    @Test void theLineEndingIsNotPartOfTheComparison() {
        assertEquals("", UnifiedDiff.diff("f", "a\r\nb\r\n", "a\nb\n"));
        String diff = UnifiedDiff.diff("f", "a\r\nb\r\nc\r\n", "a\r\nX\r\nc\r\n");
        assertEquals("--- a/f\n+++ b/f\n@@ -1,3 +1,3 @@\n a\n-b\n+X\n c\n", diff);
        assertFalse(diff.contains("\r"));
    }

    @Test void nonLatinTextIsKept() {
        assertTrue(UnifiedDiff.diff("f", "Код\n", "Номер\n").contains("-Код\n+Номер\n"));
    }

    @Test void theResultIsTheSameEveryTime() {
        String before = lines(1, 50), after = before.replace("line20\n", "a\nb\n").replace("line40\n", "");
        assertEquals(UnifiedDiff.diff("f", before, after), UnifiedDiff.diff("f", before, after));
    }
}
