package org.ipro.telemetry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ipro.telemetry")
public class TelemetryProperties {

    private boolean enabled = true;
    private boolean dbJournal = true;
    private long methodThresholdMs = 200;
    private long sqlThresholdMs = 100;
    private long l0WindowSeconds = 60;
    private int traceDefaultMinutes = 10;
    private boolean includeParams = false;
    private int queueSize = 10_000;
    private int frameLimit = 500;
    private String fileDir = "logs";
    private String redactFields = "password,token,secret";
    private int retentionEventsDays = 90;
    private int retentionSecurityDays = 365;
    private int retentionStatsDays = 365;
    private int retentionTraceHours = 72;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDbJournal() {
        return dbJournal;
    }

    public void setDbJournal(boolean dbJournal) {
        this.dbJournal = dbJournal;
    }

    public long getMethodThresholdMs() {
        return methodThresholdMs;
    }

    public void setMethodThresholdMs(long methodThresholdMs) {
        this.methodThresholdMs = methodThresholdMs;
    }

    public long getSqlThresholdMs() {
        return sqlThresholdMs;
    }

    public void setSqlThresholdMs(long sqlThresholdMs) {
        this.sqlThresholdMs = sqlThresholdMs;
    }

    public long getL0WindowSeconds() {
        return l0WindowSeconds;
    }

    public void setL0WindowSeconds(long l0WindowSeconds) {
        this.l0WindowSeconds = l0WindowSeconds;
    }

    public int getTraceDefaultMinutes() {
        return traceDefaultMinutes;
    }

    public void setTraceDefaultMinutes(int traceDefaultMinutes) {
        this.traceDefaultMinutes = traceDefaultMinutes;
    }

    public boolean isIncludeParams() {
        return includeParams;
    }

    public void setIncludeParams(boolean includeParams) {
        this.includeParams = includeParams;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public void setQueueSize(int queueSize) {
        this.queueSize = queueSize;
    }

    public int getFrameLimit() {
        return frameLimit;
    }

    public void setFrameLimit(int frameLimit) {
        this.frameLimit = frameLimit;
    }

    public String getFileDir() {
        return fileDir;
    }

    public void setFileDir(String fileDir) {
        this.fileDir = fileDir;
    }

    public String getRedactFields() {
        return redactFields;
    }

    public void setRedactFields(String redactFields) {
        this.redactFields = redactFields;
    }

    public int getRetentionEventsDays() {
        return retentionEventsDays;
    }

    public void setRetentionEventsDays(int retentionEventsDays) {
        this.retentionEventsDays = retentionEventsDays;
    }

    public int getRetentionSecurityDays() {
        return retentionSecurityDays;
    }

    public void setRetentionSecurityDays(int retentionSecurityDays) {
        this.retentionSecurityDays = retentionSecurityDays;
    }

    public int getRetentionStatsDays() {
        return retentionStatsDays;
    }

    public void setRetentionStatsDays(int retentionStatsDays) {
        this.retentionStatsDays = retentionStatsDays;
    }

    public int getRetentionTraceHours() {
        return retentionTraceHours;
    }

    public void setRetentionTraceHours(int retentionTraceHours) {
        this.retentionTraceHours = retentionTraceHours;
    }
}