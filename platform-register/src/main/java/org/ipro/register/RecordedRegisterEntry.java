package org.ipro.register;

/** Capability shared by rows owned by a recorder document. */
public interface RecordedRegisterEntry {
    RecorderRef getRecorder();

    void setRecorder(RecorderRef recorder);

    Integer getLineNumber();

    void setLineNumber(Integer lineNumber);

    Long getRecordingOrder();

    void setRecordingOrder(Long recordingOrder);
}
