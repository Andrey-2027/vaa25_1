package org.ipro.design.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resolves a type name written in a file to a fully qualified name without a classpath: explicit
 * import, same package, java.lang, then wildcard imports checked against the project index and the
 * known platform and JDK types. A name that cannot be resolved with certainty is not resolved.
 */
public final class NameResolver {
    /** JDK types an entity file commonly names; the only ones accepted through a wildcard import. */
    static final Set<String> KNOWN_JDK = Set.of(
        "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Boolean", "java.lang.Double",
        "java.lang.Float", "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.lang.Object",
        "java.math.BigDecimal", "java.math.BigInteger", "java.time.LocalDate", "java.time.LocalDateTime",
        "java.time.LocalTime", "java.time.Instant", "java.time.OffsetDateTime", "java.util.UUID", "java.util.List",
        "java.util.Set", "java.util.Map", "java.util.Collection");

    private final String packageName;
    private final List<String> singleImports = new ArrayList<>(), wildcardImports = new ArrayList<>();
    private final Predicate<String> projectType;

    /**
     * @param imports     imports as written, without {@code import} and {@code ;}; static imports are ignored
     * @param projectType whether a fully qualified name is a type of the project or a known platform type
     */
    public NameResolver(String packageName, List<String> imports, Predicate<String> projectType) {
        this.packageName = packageName; this.projectType = projectType;
        for (String name : imports) {
            if (name.startsWith("static ")) continue;
            if (name.endsWith(".*")) wildcardImports.add(name.substring(0, name.length() - 2));
            else singleImports.add(name);
        }
    }

    public Optional<String> resolve(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        if (name.indexOf('.') >= 0) {
            // A package-qualified name is already complete; Outer.Inner is not resolved.
            return Character.isLowerCase(name.charAt(0)) ? Optional.of(name) : Optional.empty();
        }
        for (String imported : singleImports)
            if (imported.endsWith("." + name)) return Optional.of(imported);
        String samePackage = packageName.isEmpty() ? name : packageName + "." + name;
        if (projectType.test(samePackage)) return Optional.of(samePackage);
        var candidates = new ArrayList<String>();
        for (String wildcard : wildcardImports) {
            String candidate = wildcard + "." + name;
            if (projectType.test(candidate) || KNOWN_JDK.contains(candidate)) candidates.add(candidate);
        }
        if (KNOWN_JDK.contains("java.lang." + name)) candidates.add("java.lang." + name);
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }
}
