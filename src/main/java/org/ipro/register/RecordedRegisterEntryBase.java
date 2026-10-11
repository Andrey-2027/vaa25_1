package org.ipro.register;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.ipro.crud.BaseEntity;

/** Shared JPA mapping for a nonperiodic information-register row owned by a recorder. */
@MappedSuperclass
@Access(AccessType.FIELD)
@EntityListeners(RecordedRegisterEntryWriteGuard.class)
public abstract class RecordedRegisterEntryBase extends BaseEntity implements RecordedRegisterEntry {
    @NotNull
    @Embedded
    private RecorderRef recorder;

    @NotNull
    @Positive
    @Column(name = "line_no", nullable = false, updatable = false)
    private Integer lineNumber;

    @NotNull
    @Positive
    @Column(name = "recording_order", nullable = false, updatable = false)
    private Long recordingOrder;

    protected RecordedRegisterEntryBase() {
    }

    @Override
    public RecorderRef getRecorder() {
        return recorder;
    }

    @Override
    public void setRecorder(RecorderRef recorder) {
        this.recorder = recorder;
    }

    @Override
    public Integer getLineNumber() {
        return lineNumber;
    }

    @Override
    public void setLineNumber(Integer lineNumber) {
        this.lineNumber = lineNumber;
    }

    @Override
    public Long getRecordingOrder() {
        return recordingOrder;
    }

    @Override
    public void setRecordingOrder(Long recordingOrder) {
        this.recordingOrder = recordingOrder;
    }
}
