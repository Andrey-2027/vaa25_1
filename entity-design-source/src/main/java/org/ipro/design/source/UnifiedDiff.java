package org.ipro.design.source;

import java.util.ArrayList;
import java.util.List;

/**
 * A unified diff of two texts, meant for a reviewer. Lines are compared without their line ending, so
 * the diff does not show whether a file uses LF or CRLF. The output is deterministic and always uses LF.
 */
public final class UnifiedDiff {
    private UnifiedDiff() {}

    private static final int CONTEXT = 3;
    /** Above this size of the changed middle the diff shows it as one removal and one addition instead of searching for common lines. */
    private static final long MAX_CELLS = 4_000_000L;

    private record Op(char kind, String line) {}

    /** An empty string when the texts have the same lines. */
    public static String diff(String path, String before, String after) {
        var a = lines(before);
        var b = lines(after);
        if (a.equals(b)) return "";
        var ops = operations(a, b);
        var out = new StringBuilder("--- a/").append(path).append('\n').append("+++ b/").append(path).append('\n');
        int index = 0;
        int oldLine = 1, newLine = 1;
        // positions[i] = old and new line number at operation i
        int[] oldAt = new int[ops.size() + 1], newAt = new int[ops.size() + 1];
        for (int i = 0; i < ops.size(); i++) {
            oldAt[i] = oldLine; newAt[i] = newLine;
            if (ops.get(i).kind() != '+') oldLine++;
            if (ops.get(i).kind() != '-') newLine++;
        }
        oldAt[ops.size()] = oldLine; newAt[ops.size()] = newLine;
        while (index < ops.size()) {
            if (ops.get(index).kind() == ' ') { index++; continue; }
            int start = Math.max(0, index - CONTEXT);
            int end = index;
            int last = index;
            while (end < ops.size()) {
                if (ops.get(end).kind() != ' ') last = end;
                else if (end - last > 2 * CONTEXT) break;
                end++;
            }
            end = Math.min(ops.size(), last + 1 + CONTEXT);
            int oldCount = 0, newCount = 0;
            for (int i = start; i < end; i++) {
                if (ops.get(i).kind() != '+') oldCount++;
                if (ops.get(i).kind() != '-') newCount++;
            }
            int oldStart = oldCount == 0 ? oldAt[start] - 1 : oldAt[start];
            int newStart = newCount == 0 ? newAt[start] - 1 : newAt[start];
            out.append("@@ -").append(oldStart).append(',').append(oldCount).append(" +").append(newStart).append(',').append(newCount).append(" @@\n");
            for (int i = start; i < end; i++) out.append(ops.get(i).kind()).append(ops.get(i).line()).append('\n');
            index = end;
        }
        return out.toString();
    }

    private static List<String> lines(String text) {
        var result = new ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            String line = end < 0 ? text.substring(start) : text.substring(start, end);
            result.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            if (end < 0) break;
            start = end + 1;
        }
        return result;
    }

    private static List<Op> operations(List<String> a, List<String> b) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
        var x = a.subList(prefix, a.size() - suffix);
        var y = b.subList(prefix, b.size() - suffix);
        var ops = new ArrayList<Op>();
        for (int i = 0; i < prefix; i++) ops.add(new Op(' ', a.get(i)));
        if ((long) (x.size() + 1) * (y.size() + 1) > MAX_CELLS) {
            for (String line : x) ops.add(new Op('-', line));
            for (String line : y) ops.add(new Op('+', line));
        } else {
            int[][] lcs = new int[x.size() + 1][y.size() + 1];
            for (int i = x.size() - 1; i >= 0; i--)
                for (int j = y.size() - 1; j >= 0; j--)
                    lcs[i][j] = x.get(i).equals(y.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            int i = 0, j = 0;
            while (i < x.size() || j < y.size()) {
                if (i < x.size() && j < y.size() && x.get(i).equals(y.get(j))) { ops.add(new Op(' ', x.get(i))); i++; j++; }
                else if (i < x.size() && (j == y.size() || lcs[i + 1][j] >= lcs[i][j + 1])) { ops.add(new Op('-', x.get(i))); i++; }
                else { ops.add(new Op('+', y.get(j))); j++; }
            }
        }
        for (int i = a.size() - suffix; i < a.size(); i++) ops.add(new Op(' ', a.get(i)));
        return ops;
    }
}
