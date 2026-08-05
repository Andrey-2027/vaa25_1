package org.ipro.telemetry.core;

import java.util.List;

/** Человекочитаемое текстовое представление дерева фреймов для лога/UI. */
public final class TreeRenderer {

    private TreeRenderer() {
    }

    public static String render(Operation operation) {
        StringBuilder sb = new StringBuilder();
        renderNode(sb, operation, 0);
        return sb.toString();
    }

    private static void renderNode(StringBuilder sb, Frame frame, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(frameName(frame))
          .append(": ")
          .append(String.format("%.2f", frame.getDurationNanos() / 1_000_000.0))
          .append(" ms");
        if (frame.getSqlCount() > 0) {
            sb.append(" [").append(frame.getSqlCount()).append(" sql, ")
              .append(String.format("%.2f", frame.getSqlTotalNanos() / 1_000_000.0))
              .append(" ms]");
        }
        if (frame.isFailed()) {
            sb.append(" [FAILED]");
        }
        sb.append('\n');
        List<Frame> children = frame.getChildren();
        for (Frame child : children) {
            renderNode(sb, child, depth + 1);
        }
    }

    private static String frameName(Frame frame) {
        return frame instanceof Operation ? "OP " + frame.getName() : frame.getName();
    }
}