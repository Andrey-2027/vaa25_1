package org.ipro.vaadin.explorer.ui;

import org.ipro.vaadin.explorer.EntitySummary;
import java.util.List;

/** Optional host port. The host authorizes and confirms symbols on every request. No paths are accepted. */
@FunctionalInterface
public interface ExplorerSourcePort {
    record Target(String className, String method) {
        public String symbol() { return className + (method == null ? "" : "#" + method); }
    }
    record Source(Target target, List<String> lines, int firstLine, int lastLine, String reason) {
        public Source { lines = List.copyOf(lines); reason = reason == null ? "" : reason; }
        public boolean available() { return !lines.isEmpty(); }
    }
    Source read(EntitySummary summary, Target target);
    static ExplorerSourcePort unavailable() {
        return (summary, target) -> new Source(target, List.of(), 0, 0, "Просмотр исходников не подключён приложением");
    }
}
