package org.ipro.telemetry.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Фрейм — один сервисный вызов внутри операции. Дерево фреймов:
 * операция → сервисы → (дочерние фреймы, SQL-статистика).
 */
public class Frame {

    private final String name;
    private final long startNanos;
    private long durationNanos;
    private boolean failed;
    private int sqlCount;
    private long sqlTotalNanos;
    private final List<Frame> children = new ArrayList<>();

    public Frame(String name) {
        this.name = name;
        this.startNanos = System.nanoTime();
    }

    public String getName() {
        return name;
    }

    public void setDurationNanos(long durationNanos) {
        this.durationNanos = durationNanos;
    }

    public long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    public long getDurationNanos() {
        return durationNanos;
    }

    public boolean isFailed() {
        return failed;
    }

    public void setFailed(boolean failed) {
        this.failed = failed;
    }

    public void addChild(Frame child) {
        children.add(child);
    }

    public List<Frame> getChildren() {
        return children;
    }

    public void addSql(long executionNanos) {
        sqlCount++;
        sqlTotalNanos += executionNanos;
    }

    public int getSqlCount() {
        return sqlCount;
    }

    public long getSqlTotalNanos() {
        return sqlTotalNanos;
    }

    public int nodeCount() {
        int count = 1;
        for (Frame child : children) {
            count += child.nodeCount();
        }
        return count;
    }
}