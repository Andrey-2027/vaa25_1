package org.ipro.reportstudio.transfer;

import org.ipro.reportstudio.dom.ReportBandKind;
import org.ipro.reportstudio.dom.ReportComputedValue;
import org.ipro.reportstudio.dom.ReportFieldAggregation;
import org.ipro.reportstudio.dom.ReportFieldAlignment;
import org.ipro.reportstudio.dom.ReportFieldKind;
import org.ipro.reportstudio.dom.ReportOrderDirection;
import org.ipro.reportstudio.dom.ReportPageOrientation;
import org.ipro.reportstudio.dom.ReportPageSize;
import org.ipro.reportstudio.dom.ReportParamKind;
import org.ipro.reportstudio.dom.ReportParamSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Версионированный обменный формат шаблона отчёта.
 *
 * <p>Формат намеренно не содержит JPA id, version, template_id, пользователей
 * и иных локальных технических полей. Для связей бэндов используются локальные
 * ключи {@code key}/{@code parentKey}, пригодные для переноса между БД.</p>
 */
public class ReportTemplateExchange {

    public static final String FORMAT = "ipro-report-template";
    public static final int SCHEMA_VERSION = 1;

    private String format = FORMAT;
    private int schemaVersion = SCHEMA_VERSION;
    private Template template = new Template();

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Template getTemplate() {
        return template;
    }

    public void setTemplate(Template template) {
        this.template = template;
    }

    public static class Template {
        private String name;
        private String description;
        private String targetEntityClass;
        private String jpql;
        private int maxRows;
        private int timeoutMs;
        private boolean advanced;
        private ReportPageSize pageSize;
        private ReportPageOrientation pageOrientation;
        private Integer baseFontSize;
        private Boolean gridEnabled;
        private Boolean stripeRows;
        private List<Param> params = new ArrayList<>();
        private List<Band> bands = new ArrayList<>();
        private List<Order> orders = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getTargetEntityClass() {
            return targetEntityClass;
        }

        public void setTargetEntityClass(String targetEntityClass) {
            this.targetEntityClass = targetEntityClass;
        }
        public String getJpql() {
            return jpql;
        }

        public void setJpql(String jpql) {
            this.jpql = jpql;
        }

        public int getMaxRows() {
            return maxRows;
        }

        public void setMaxRows(int maxRows) {
            this.maxRows = maxRows;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public boolean isAdvanced() {
            return advanced;
        }

        public void setAdvanced(boolean advanced) {
            this.advanced = advanced;
        }

        public ReportPageSize getPageSize() {
            return pageSize;
        }

        public void setPageSize(ReportPageSize pageSize) {
            this.pageSize = pageSize;
        }

        public ReportPageOrientation getPageOrientation() {
            return pageOrientation;
        }

        public void setPageOrientation(ReportPageOrientation pageOrientation) {
            this.pageOrientation = pageOrientation;
        }

        public Integer getBaseFontSize() {
            return baseFontSize;
        }

        public void setBaseFontSize(Integer baseFontSize) {
            this.baseFontSize = baseFontSize;
        }

        public Boolean getGridEnabled() {
            return gridEnabled;
        }

        public void setGridEnabled(Boolean gridEnabled) {
            this.gridEnabled = gridEnabled;
        }

        public Boolean getStripeRows() {
            return stripeRows;
        }

        public void setStripeRows(Boolean stripeRows) {
            this.stripeRows = stripeRows;
        }

        public List<Param> getParams() {
            return params;
        }

        public void setParams(List<Param> params) {
            this.params = params;
        }

public List<Band> getBands() {
            return bands;
        }

        public void setBands(List<Band> bands) {
            this.bands = bands;
        }

        public List<Order> getOrders() {
            return orders;
        }

        public void setOrders(List<Order> orders) {
            this.orders = orders;
        }
    }

    public static class Order {
        private String columnName;
        private ReportOrderDirection direction;
        private int position;

        public String getColumnName() {
            return columnName;
        }

        public void setColumnName(String columnName) {
            this.columnName = columnName;
        }

        public ReportOrderDirection getDirection() {
            return direction;
        }

        public void setDirection(ReportOrderDirection direction) {
            this.direction = direction;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }
    }

    public static class Param {
        private String name;
        private String caption;
        private ReportParamKind kind;
        private String entityClass;
        private ReportParamSource valueSource;
        private boolean required;
        private boolean showOnForm;
        private String defaultValue;
        private ReportComputedValue computed;
        private int position;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCaption() { return caption; }
        public void setCaption(String caption) { this.caption = caption; }
        public ReportParamKind getKind() { return kind; }
        public void setKind(ReportParamKind kind) { this.kind = kind; }
        public String getEntityClass() { return entityClass; }
        public void setEntityClass(String entityClass) { this.entityClass = entityClass; }
        public ReportParamSource getValueSource() { return valueSource; }
        public void setValueSource(ReportParamSource valueSource) { this.valueSource = valueSource; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public boolean isShowOnForm() { return showOnForm; }
        public void setShowOnForm(boolean showOnForm) { this.showOnForm = showOnForm; }
        public String getDefaultValue() { return defaultValue; }
        public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
        public ReportComputedValue getComputed() { return computed; }
        public void setComputed(ReportComputedValue computed) { this.computed = computed; }
        public int getPosition() { return position; }
        public void setPosition(int position) { this.position = position; }
    }

public static class Band {
        private String key;
        private String parentKey;
        private ReportBandKind kind;
        private int position;
        private String groupField;
        private Boolean startNewPage;
        private List<Field> fields = new ArrayList<>();

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getParentKey() { return parentKey; }
        public void setParentKey(String parentKey) { this.parentKey = parentKey; }
        public ReportBandKind getKind() { return kind; }
        public void setKind(ReportBandKind kind) { this.kind = kind; }
        public int getPosition() { return position; }
        public void setPosition(int position) { this.position = position; }
        public String getGroupField() { return groupField; }
        public void setGroupField(String groupField) { this.groupField = groupField; }
        public Boolean getStartNewPage() { return startNewPage; }
        public void setStartNewPage(Boolean startNewPage) { this.startNewPage = startNewPage; }
        public List<Field> getFields() { return fields; }
        public void setFields(List<Field> fields) { this.fields = fields; }
    }

    public static class Field {
        private ReportFieldKind kind;
        private String queryField;
        private String text;
        private String caption;
        private Integer width;
        private String format;
        private Boolean border;
        private boolean visible;
        private ReportFieldAggregation aggregation;
        private ReportFieldAlignment alignment;
        private int position;

        public ReportFieldKind getKind() { return kind; }
        public void setKind(ReportFieldKind kind) { this.kind = kind; }
        public String getQueryField() { return queryField; }
        public void setQueryField(String queryField) { this.queryField = queryField; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getCaption() { return caption; }
        public void setCaption(String caption) { this.caption = caption; }
        public Integer getWidth() { return width; }
        public void setWidth(Integer width) { this.width = width; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public Boolean getBorder() { return border; }
        public void setBorder(Boolean border) { this.border = border; }
        public boolean isVisible() { return visible; }
        public void setVisible(boolean visible) { this.visible = visible; }
        public ReportFieldAggregation getAggregation() { return aggregation; }
        public void setAggregation(ReportFieldAggregation aggregation) { this.aggregation = aggregation; }
        public ReportFieldAlignment getAlignment() { return alignment; }
        public void setAlignment(ReportFieldAlignment alignment) { this.alignment = alignment; }
        public int getPosition() { return position; }
        public void setPosition(int position) { this.position = position; }
    }
}
