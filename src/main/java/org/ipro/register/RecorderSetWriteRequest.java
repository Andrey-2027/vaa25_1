package org.ipro.register;

import org.ipro.crud.BaseEntity;

import java.util.List;
import java.util.Objects;

/** Complete before/after view used to authorize one recorder-set replacement. */
public record RecorderSetWriteRequest(Class<?> registerType,
                                      BaseEntity recorderDocument,
                                      RecorderRef recorder,
                                      List<? extends RecordedRegisterEntry> existingRows,
                                      List<? extends RecordedRegisterEntry> proposedRows) {
    public RecorderSetWriteRequest {
        Objects.requireNonNull(registerType, "registerType");
        Objects.requireNonNull(recorderDocument, "recorderDocument");
        Objects.requireNonNull(recorder, "recorder");
        existingRows = List.copyOf(Objects.requireNonNull(existingRows, "existingRows"));
        proposedRows = List.copyOf(Objects.requireNonNull(proposedRows, "proposedRows"));
    }
}
