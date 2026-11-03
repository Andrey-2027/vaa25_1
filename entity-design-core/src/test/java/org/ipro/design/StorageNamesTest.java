package org.ipro.design;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageNamesTest {
    @Test void shortNameIsUnchanged() {
        assertEquals("uk_region_rate_key", StorageNames.constraint("uk_region_rate_key"));
    }
    @Test void nameOfExactlyTheLimitIsUnchanged() {
        String name = "u".repeat(StorageNames.MAX_LENGTH);
        assertSame(name, StorageNames.constraint(name));
    }
    @Test void longNameFitsTheLimitAndKeepsItsStart() {
        String name = "uk_" + "t".repeat(63) + "_key";
        String result = StorageNames.constraint(name);
        assertEquals(StorageNames.MAX_LENGTH, result.length());
        assertTrue(result.startsWith(name.substring(0, 54) + "_"));
        assertTrue(result.matches("[a-z0-9_]{63}"));
    }
    @Test void longNamesWithACommonStartDiffer() {
        String common = "uk_" + "t".repeat(58);
        assertNotEquals(StorageNames.constraint(common + "a_key"), StorageNames.constraint(common + "b_key"));
    }
    @Test void resultIsDeterministic() {
        String name = "uk_" + "x".repeat(70) + "_key";
        assertEquals(StorageNames.constraint(name), StorageNames.constraint(name));
        // Pinned: the suffix is the first 8 hex digits of SHA-256 over the full UTF-8 name.
        assertEquals("uk_" + "x".repeat(51) + "_6e904798", StorageNames.constraint(name));
    }
}
