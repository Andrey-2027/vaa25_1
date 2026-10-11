package org.ipro.crud;

/** Version state observed by aggregate contribution adapters around root/section persistence. */
public record AggregateSaveParticipantContext(boolean creating,
                                              Long originalVersion,
                                              boolean versionAdvanceRequested) {
    public AggregateSaveParticipantContext(boolean creating, Long originalVersion) {
        this(creating, originalVersion, false);
    }

    public AggregateSaveParticipantContext {
        if (creating && originalVersion != null) {
            throw new IllegalArgumentException("A new aggregate cannot have an original version");
        }
        if (!creating && originalVersion == null) {
            originalVersion = 0L;
        }
        if (originalVersion != null && originalVersion < 0) {
            throw new IllegalArgumentException("originalVersion must be non-negative");
        }
    }
}
