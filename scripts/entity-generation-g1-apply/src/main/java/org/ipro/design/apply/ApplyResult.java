package org.ipro.design.apply;

import java.nio.file.Path;
import java.util.Objects;

/** Result of one apply attempt; paths are diagnostic values, never new trust inputs. */
public record ApplyResult(ApplyStatus status, String code, String message,
                          String applyId, String planId, Path target, Path receipt, Path journal,
                          String contentSha256, String cSummarySha256) {
    public ApplyResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    public int exitCode() {
        return status.exitCode();
    }
}
