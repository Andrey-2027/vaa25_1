package org.ipro.design.apply;

import java.io.IOException;
import java.nio.file.Path;

/** Test-only observation points. The public adapter always uses the no-op instance. */
interface ApplyHooks {
    ApplyHooks NONE = new ApplyHooks() { };

    default void beforePublish(Path target) throws IOException {
    }

    default void afterPublish(Path target) throws IOException {
    }

    default void beforeReceipt(Path receipt) throws IOException {
    }

    default void afterReceipt(Path receipt) throws IOException {
    }
}
