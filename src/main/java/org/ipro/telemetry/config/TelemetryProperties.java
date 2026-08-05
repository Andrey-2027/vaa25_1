package org.ipro.telemetry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "ipro.telemetry")
public class TelemetryProperties {

    private boolean enabled = true;
    private boolean dbJournal = true;
    private long methodThresholdMs = 200;
    private long sqlThresholdMs = 100;
    private int n1Threshold = 5;
    private long l0WindowSeconds = 60;
    private int traceDefaultMinutes = 10;
    private boolean includeParams = false;
    private boolean entityDataEnabled = true;
    private int queueSize = 10_000;
    private int frameLimit = 500;
    private String fileDir = "logs";
    private String traceDir = "logs/traces";
    private String appName = "app";

    @NestedConfigurationProperty
    private Sql sql = new Sql();

    @NestedConfigurationProperty
    private Retention retention = new Retention();

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

    public int getN1Threshold() {
        return n1Threshold;
    }

    public void setN1Threshold(int n1Threshold) {
        this.n1Threshold = n1Threshold;
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

    public boolean isEntityDataEnabled() {
        return entityDataEnabled;
    }

    public void setEntityDataEnabled(boolean entityDataEnabled) {
        this.entityDataEnabled = entityDataEnabled;
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

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getTraceDir() {
        return traceDir;
    }

    public void setTraceDir(String traceDir) {
        this.traceDir = traceDir;
    }

    public Sql getSql() {
        return sql;
    }

    public void setSql(Sql sql) {
        this.sql = sql;
    }

    public Retention getRetention() {
        return retention;
    }

    public void setRetention(Retention retention) {
        this.retention = retention;
    }

    public static class Sql {
        private String redactFields = "password,token,secret";

        public String getRedactFields() {
            return redactFields;
        }

        public void setRedactFields(String redactFields) {
            this.redactFields = redactFields;
        }
    }

    public static class Retention {
        private int eventsDays = 90;
        private int securityDays = 365;
        private int statsDays = 365;
        private int traceHours = 72;

        public int getEventsDays() {
            return eventsDays;
        }

        public void setEventsDays(int eventsDays) {
            this.eventsDays = eventsDays;
        }

        public int getSecurityDays() {
            return securityDays;
        }

        public void setSecurityDays(int securityDays) {
            this.securityDays = securityDays;
        }

        public int getStatsDays() {
            return statsDays;
        }

        public void setStatsDays(int statsDays) {
            this.statsDays = statsDays;
        }

        public int getTraceHours() {
            return traceHours;
        }

        public void setTraceHours(int traceHours) {
            this.traceHours = traceHours;
        }
    }
}