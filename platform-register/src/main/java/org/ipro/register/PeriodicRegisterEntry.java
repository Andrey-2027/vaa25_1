package org.ipro.register;

import java.time.LocalDateTime;

/** Capability shared by every information-register row whose key includes a period. */
public interface PeriodicRegisterEntry {
    LocalDateTime getPeriod();

    void setPeriod(LocalDateTime period);
}
