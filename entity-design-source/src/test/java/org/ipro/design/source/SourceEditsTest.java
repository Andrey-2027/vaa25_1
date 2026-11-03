package org.ipro.design.source;

import java.util.List;
import org.ipro.design.source.SourceEdits.Edit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceEditsTest {
    private static final String TEXT = "package a;\npublic class Foo {\n    private int x;\n}\n";

    private static SourceRefusal.Code refusal(List<Edit> edits) {
        return assertThrows(SourceRefusal.class, () -> SourceEdits.apply("Foo.java", TEXT, edits)).code();
    }

    @Test void editsAreAppliedAtTheirPositionsWhateverTheirOrder() {
        int field = TEXT.indexOf("private");
        int end = TEXT.indexOf("}\n", field);
        String result = SourceEdits.apply("Foo.java", TEXT, List.of(
            SourceEdits.insert(end, "    private int y;\n"), SourceEdits.insert(field, "@Deprecated ")));
        assertEquals("package a;\npublic class Foo {\n    @Deprecated private int x;\n    private int y;\n}\n", result);
    }

    @Test void insertionAtTheStartAndAtTheEnd() {
        assertEquals("// head\n" + TEXT + "// tail\n", SourceEdits.apply("Foo.java", TEXT, List.of(
            SourceEdits.insert(0, "// head\n"), SourceEdits.insert(TEXT.length(), "// tail\n"))));
    }

    @Test void aReplacementKeepsEverythingElse() {
        int at = TEXT.indexOf("int");
        assertEquals(TEXT.replace("int x", "long x"), SourceEdits.apply("Foo.java", TEXT, List.of(SourceEdits.replace(at, at + 3, "long"))));
    }

    @Test void adjacentEditsAreAllowed() {
        int at = TEXT.indexOf("int");
        assertEquals(TEXT.replace("int", "AB"), SourceEdits.apply("Foo.java", TEXT, List.of(
            SourceEdits.replace(at, at + 1, "A"), SourceEdits.replace(at + 1, at + 3, "B"))));
    }

    @Test void overlappingEditsAreRefused() {
        int at = TEXT.indexOf("int");
        assertEquals(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, refusal(List.of(SourceEdits.replace(at, at + 3, "long"), SourceEdits.replace(at + 1, at + 2, "x"))));
    }

    @Test void twoEditsAtTheSamePositionAreRefusedBecauseTheirOrderIsAmbiguous() {
        int at = TEXT.indexOf("private");
        assertEquals(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, refusal(List.of(SourceEdits.insert(at, "a"), SourceEdits.insert(at, "b"))));
    }

    @Test void anEditBeyondTheTextIsRefused() {
        assertEquals(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, refusal(List.of(SourceEdits.insert(TEXT.length() + 1, "x"))));
    }

    @Test void aResultThatDoesNotParseIsCaughtBySelfCheck() {
        int at = TEXT.indexOf("private");
        var refused = assertThrows(SourceRefusal.class, () -> SourceEdits.apply("Foo.java", TEXT, List.of(SourceEdits.insert(at, "}}"))));
        assertEquals(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, refused.code());
        assertTrue(refused.reason().contains("does not parse"), refused.reason());
    }

    @Test void aTamperedResultIsCaughtBySelfCheck() {
        int at = TEXT.indexOf("private");
        var edits = List.of(SourceEdits.insert(at, "@Deprecated "));
        String good = SourceEdits.apply("Foo.java", TEXT, edits);
        SourceEdits.verify("Foo.java", TEXT, edits, good);
        String tampered = good.replace("int x", "int z");
        assertEquals(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, assertThrows(SourceRefusal.class, () -> SourceEdits.verify("Foo.java", TEXT, edits, tampered)).code());
        String lostTail = good.substring(0, good.length() - 2);
        assertEquals(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, assertThrows(SourceRefusal.class, () -> SourceEdits.verify("Foo.java", TEXT, edits, lostTail)).code());
        String wrongEdit = good.replace("@Deprecated ", "@Deprecate  ");
        assertEquals(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, assertThrows(SourceRefusal.class, () -> SourceEdits.verify("Foo.java", TEXT, edits, wrongEdit)).code());
    }

    @Test void noEditsLeaveTheTextUnchanged() {
        assertEquals(TEXT, SourceEdits.apply("Foo.java", TEXT, List.of()));
    }
}
