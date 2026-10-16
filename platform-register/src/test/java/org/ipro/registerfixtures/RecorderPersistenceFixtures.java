package org.ipro.registerfixtures;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.Check;
import org.ipro.crud.BaseEntity;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSections;
import org.ipro.register.PeriodicRecordedRegisterEntryBase;
import org.ipro.register.PeriodicRegisterEntryBase;
import org.ipro.register.RecorderRef;
import org.ipro.register.RecorderType;
import org.ipro.register.RecordedRegisterEntryBase;
import org.ipro.register.RegisterMetadata;
import org.ipro.register.RegisterPeriodicity;

import java.time.LocalDateTime;

/** Small concrete entities used to exercise each supported register persistence shape. */
public final class RecorderPersistenceFixtures {
    private RecorderPersistenceFixtures() { }

    @Entity
    @Table(name = "recorder_fixture_document_a")
    @EntityMetadata(kind = EntityKind.DOCUMENT)
    @RecorderType("fixture-document-a")
    @TableSections(RecorderFixtureDocumentASection.class)
    public static class RecorderFixtureDocumentA extends BaseEntity {
        @Column(name = "document_note")
        private String note;

        public RecorderFixtureDocumentA() { }

        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }

    @Entity
    @Table(name = "recorder_fixture_document_b")
    @EntityMetadata(kind = EntityKind.DOCUMENT)
    @RecorderType("fixture-document-b")
    public static class RecorderFixtureDocumentB extends BaseEntity {
        public RecorderFixtureDocumentB() { }
    }

    @Entity
    @Table(name = "register_nonperiodic_fixture",
        uniqueConstraints = @UniqueConstraint(name = "uk_register_nonperiodic_fixture_key", columnNames = "item_key"))
    @RegisterMetadata(dimensions = "itemKey", resources = "amount")
    public static class NonperiodicRegisterFixture extends BaseEntity {
        @NotNull
        @Column(name = "item_key", nullable = false, updatable = false, length = 64)
        private String itemKey;

        @NotNull
        @Column(name = "amount", nullable = false)
        private Integer amount;

        protected NonperiodicRegisterFixture() { }

        public NonperiodicRegisterFixture(String itemKey, Integer amount) {
            this.itemKey = itemKey;
            this.amount = amount;
        }

        public String getItemKey() { return itemKey; }
        public Integer getAmount() { return amount; }
        public void setAmount(Integer amount) { this.amount = amount; }
    }

    @Entity
    @Table(name = "register_periodic_fixture",
        uniqueConstraints = @UniqueConstraint(name = "uk_register_periodic_fixture_key_period",
            columnNames = {"item_key", "period_date"}))
    @RegisterMetadata(dimensions = "itemKey", period = "period", resources = "amount",
        periodicity = RegisterPeriodicity.DAY)
    public static class PeriodicRegisterFixture extends PeriodicRegisterEntryBase {
        @NotNull
        @Column(name = "item_key", nullable = false, updatable = false, length = 64)
        private String itemKey;

        @NotNull
        @Column(name = "amount", nullable = false)
        private Integer amount;

        protected PeriodicRegisterFixture() { }

        public PeriodicRegisterFixture(String itemKey, LocalDateTime period, Integer amount) {
            this.itemKey = itemKey;
            setPeriod(period);
            this.amount = amount;
        }

        public String getItemKey() { return itemKey; }
        public Integer getAmount() { return amount; }
    }

    @Entity
    @Table(name = "register_recorded_fixture",
        uniqueConstraints = @UniqueConstraint(name = "uk_register_recorded_fixture_recorder_line",
            columnNames = {"recorder_type", "recorder_id", "line_no"}))
    @RegisterMetadata(dimensions = "itemKey", resources = "amount",
        recorderTypes = {RecorderFixtureDocumentA.class, RecorderFixtureDocumentB.class})
    public static class RecordedRegisterFixture extends RecordedRegisterEntryBase {
        @NotNull
        @Column(name = "item_key", nullable = false, updatable = false, length = 64)
        private String itemKey;

        @NotNull
        @Column(name = "amount", nullable = false)
        private Integer amount;

        protected RecordedRegisterFixture() { }

        public RecordedRegisterFixture(String itemKey, RecorderRef recorder, Integer lineNumber,
                                       Long recordingOrder, Integer amount) {
            this.itemKey = itemKey;
            setRecorder(recorder);
            setLineNumber(lineNumber);
            setRecordingOrder(recordingOrder);
            this.amount = amount;
        }

        public String getItemKey() { return itemKey; }
        public Integer getAmount() { return amount; }
        public void setAmount(Integer amount) { this.amount = amount; }
    }

    @Entity
    @Check(constraints = "amount >= 0")
    @Table(name = "register_recorded_periodic_fixture",
        uniqueConstraints = @UniqueConstraint(name = "uk_register_recorded_periodic_fixture_recorder_line",
            columnNames = {"recorder_type", "recorder_id", "line_no"}))
    @RegisterMetadata(dimensions = "itemKey", period = "period", resources = "amount",
        recorderTypes = {RecorderFixtureDocumentA.class, RecorderFixtureDocumentB.class})
    public static class RecordedPeriodicRegisterFixture extends PeriodicRecordedRegisterEntryBase {
        @NotNull
        @Column(name = "item_key", nullable = false, updatable = false, length = 64)
        private String itemKey;

        @NotNull
        @Column(name = "amount", nullable = false)
        private Integer amount;

        protected RecordedPeriodicRegisterFixture() { }

        public RecordedPeriodicRegisterFixture(String itemKey, RecorderRef recorder, Integer lineNumber,
                                               Long recordingOrder, LocalDateTime period, Integer amount) {
            this.itemKey = itemKey;
            setRecorder(recorder);
            setLineNumber(lineNumber);
            setRecordingOrder(recordingOrder);
            setPeriod(period);
            this.amount = amount;
        }

        public String getItemKey() { return itemKey; }
        public Integer getAmount() { return amount; }
        public void setAmount(Integer amount) { this.amount = amount; }
    }
}
