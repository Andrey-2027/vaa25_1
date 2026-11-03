package org.ipro.design.source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The only way to change a source text: a list of non-overlapping edits. After applying them the
 * result is checked: every byte outside the edits equals the original, and the text still parses.
 * A failed check is a refusal, never a partial result, and there is no way to skip the check.
 */
public final class SourceEdits {
    private SourceEdits() {}

    /** Replaces {@code [start, end)} of the original with {@code text}; an insertion has {@code start == end}. */
    public record Edit(int start, int end, String text) {
        public Edit {
            if (start < 0 || end < start) throw new IllegalArgumentException("Bad edit range " + start + ".." + end);
            if (text == null) throw new IllegalArgumentException("Edit text is required");
        }
    }

    public static Edit insert(int at, String text) { return new Edit(at, at, text); }
    public static Edit replace(int start, int end, String text) { return new Edit(start, end, text); }

    /** Applies the edits to {@code original}; {@code fileName} only names the file in refusals. */
    public static String apply(String fileName, String original, List<Edit> edits) {
        var sorted = new ArrayList<>(edits);
        sorted.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
        for (int i = 0; i < sorted.size(); i++) {
            var edit = sorted.get(i);
            if (edit.end() > original.length())
                throw new SourceRefusal(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, fileName, "edit beyond the end of the text: " + edit.start() + ".." + edit.end());
            if (i > 0) {
                var before = sorted.get(i - 1);
                if (before.end() > edit.start() || before.start() == edit.start())
                    throw new SourceRefusal(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, fileName,
                        "edits overlap or share a position: " + before.start() + ".." + before.end() + " and " + edit.start() + ".." + edit.end());
            }
        }
        var result = new StringBuilder(original);
        for (int i = sorted.size() - 1; i >= 0; i--) {
            var edit = sorted.get(i);
            result.replace(edit.start(), edit.end(), edit.text());
        }
        verify(fileName, original, sorted, result.toString());
        return result.toString();
    }

    /** The self-check; package-visible so that a tampered result can be shown to be caught. */
    static void verify(String fileName, String original, List<Edit> sortedEdits, String result) {
        int from = 0, at = 0;
        for (var edit : sortedEdits) {
            int length = edit.start() - from;
            if (!result.regionMatches(at, original, from, length))
                throw new SourceRefusal(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, fileName, "bytes outside the edits changed before offset " + edit.start());
            at += length;
            if (!result.regionMatches(at, edit.text(), 0, edit.text().length()))
                throw new SourceRefusal(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, fileName, "edit text is not in the result at offset " + edit.start());
            at += edit.text().length();
            from = edit.end();
        }
        int tail = original.length() - from;
        if (result.length() - at != tail || !result.regionMatches(at, original, from, tail))
            throw new SourceRefusal(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, fileName, "bytes after the last edit changed");
        try {
            JavaSources.parse(fileName, result);
        } catch (SourceRefusal parse) {
            throw new SourceRefusal(SourceRefusal.Code.PATCH_SELF_CHECK_FAILED, fileName, "the result does not parse: " + parse.reason());
        }
    }
}
