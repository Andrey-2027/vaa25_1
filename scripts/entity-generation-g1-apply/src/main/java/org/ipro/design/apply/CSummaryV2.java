package org.ipro.design.apply;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.ipro.design.generation.GenerationFile;
import org.ipro.design.generation.GenerationManifest;
import org.ipro.design.generation.GenerationPlan;

/** Strict read-only adapter for the extension C summary v2. */
final class CSummaryV2 {
    static final String KIND = "g1-c-summary-v2";
    static final String REQUIRED_SUITE = "com.example.generated.harness.GenerationScenarioTest";
    static final String MULTI_REQUIRED_SUITE = "com.example.generated.harness.MultiFileScenarioTest";
    private static final long MAX_BYTES = 4 * 1_048_576;

    private final byte[] bytes;
    private final String sha256;
    private final JsonNode root;
    private final GenerationManifest manifest;
    private final List<SummaryDependency> dependencies;

    record SummaryDependency(String id, String sha256) {
    }

    private CSummaryV2(byte[] bytes, JsonNode root, GenerationManifest manifest,
                       List<SummaryDependency> dependencies) {
        this.bytes = bytes;
        this.sha256 = ApplyHash.sha256(bytes);
        this.root = root;
        this.manifest = manifest;
        this.dependencies = dependencies;
    }

    static CSummaryV2 read(Path input) throws ApplyFailure {
        Path path = input.toAbsolutePath().normalize();
        if (path.getRoot() == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            fail("C_SUMMARY_MISSING", "C summary not found: " + path);
        }
        ApplyPaths.assertNoLinks(path, "C_SUMMARY_LINKED");
        ApplyPaths.assertRegularFile(path, "C_SUMMARY_NOT_FILE");
        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_BYTES) {
                fail("C_SUMMARY_SIZE", "C summary size is outside the supported range: " + size);
            }
            byte[] bytes = Files.readAllBytes(path);
            JsonNode root = Json.MAPPER.readTree(bytes);
            if (root == null || !root.isObject()) {
                fail("C_SUMMARY_INVALID", "C summary root must be a JSON object");
            }
            JsonNode manifestNode = root.get("manifest");
            if (manifestNode == null || !manifestNode.isObject()) {
                fail("C_SUMMARY_INVALID", "C summary manifest must be an object");
            }
            GenerationManifest manifest;
            try {
                manifest = Json.MAPPER.treeToValue(manifestNode, GenerationManifest.class);
            } catch (IOException | IllegalArgumentException ex) {
                throw new ApplyFailure(ApplyStatus.REJECTED, "C_SUMMARY_INVALID",
                        "C summary manifest is invalid: " + ex.getMessage(), ex);
            }
            return new CSummaryV2(bytes, root, manifest, readDependencies(root));
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "C_SUMMARY_INVALID",
                    "Cannot read C summary: " + path, ex);
        }
    }

    private static List<SummaryDependency> readDependencies(JsonNode root) throws ApplyFailure {
        JsonNode node = root.get("dependencies");
        if (node == null || !node.isArray() || node.isEmpty()) {
            fail("C_SUMMARY_INVALID", "C summary dependencies must be a non-empty array");
        }
        List<SummaryDependency> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        String previous = null;
        for (JsonNode item : node) {
            if (!item.isObject()) {
                fail("C_SUMMARY_INVALID", "Each C dependency must be an object");
            }
            String id = textValue(item, "id");
            String hash = textValue(item, "sha256");
            if (!ApplyHash.isSha256(hash)) {
                fail("C_SUMMARY_INVALID", "C dependency sha256 is invalid: " + id);
            }
            if (!ids.add(id)) {
                fail("C_SUMMARY_INVALID", "C dependency id is duplicated: " + id);
            }
            if (previous != null && previous.compareTo(id) >= 0) {
                fail("C_SUMMARY_INVALID", "C dependencies must be sorted strictly by id: " + id);
            }
            previous = id;
            result.add(new SummaryDependency(id, hash));
        }
        return List.copyOf(result);
    }

    String sha256() {
        return sha256;
    }

    GenerationManifest manifest() {
        return manifest;
    }

    List<SummaryDependency> dependencies() {
        return dependencies;
    }

    void verify(GenerationPlan plan, List<GenerationFile> files, String fingerprintProfile)
            throws ApplyFailure {
        boolean register = org.ipro.design.RecipeCatalog.isRegisterRecipe(plan.recipe());
        String requiredSuite = register ? "com.example.generated.harness.RegisterScenarioTest"
                : !plan.source().sections().isEmpty()
                ? "com.example.generated.harness.SectionsScenarioTest"
                : files.size() > 1
                ? MULTI_REQUIRED_SUITE
                : plan.source().owner() != null ? "com.example.generated.harness.OwnerScenarioTest"
                : plan.source().fields().stream().anyMatch(f -> "REFERENCE".equals(f.type()))
                        ? "com.example.generated.harness.ReferenceScenarioTest" : REQUIRED_SUITE;
        text(root, "kind", KIND);
        text(root, "mode", "generated-acceptance");
        text(root, "status", "success");
        if (register && dependencies.stream().filter(dependency ->
                dependency.id().startsWith("org.ipro:platform-register:jar:")).count() != 1) {
            fail("C_REGISTER_DEPENDENCY_MISSING", "Register C must record the effective platform-register JAR");
        }

        GenerationManifest expected;
        try {
            expected = GenerationManifest.from(plan, fingerprintProfile);
        } catch (IllegalArgumentException ex) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "C_SUMMARY_MISMATCH",
                    "The reviewed plan cannot produce a matching manifest: " + ex.getMessage(), ex);
        }
        if (!expected.equals(manifest)) {
            fail("C_SUMMARY_MISMATCH", "C summary manifest does not match the reviewed plan");
        }
        if (!manifest.planId().equals(plan.planId())
                || !manifest.recipe().equals(plan.recipe())
                || !manifest.generatorVersion().equals(plan.generatorVersion())) {
            fail("C_SUMMARY_MISMATCH", "C summary plan binding does not match the reviewed plan");
        }
        if (manifest.entries().size() != files.size()) {
            fail("C_SUMMARY_MISMATCH",
                    "C summary manifest entry count does not match the reviewed plan");
        }
        for (int index = 0; index < files.size(); index++) {
            GenerationFile file = files.get(index);
            GenerationManifest.Entry entry = manifest.entries().get(index);
            if (!entry.relativePath().equals(file.relativePath())
                    || !entry.sha256().equals(file.sha256())
                    || entry.bytes() != file.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length) {
                fail("C_SUMMARY_MISMATCH", "C summary manifest entry does not match the reviewed file: "
                        + file.relativePath());
            }
        }
        verifySourceSet(files);

        JsonNode scenario = object(root, "scenario");
        text(scenario, "id");
        hash(scenario, "specSha256");

        JsonNode supportSources = array(root, "supportSources");
        for (JsonNode source : supportSources) {
            if (!source.isObject()) fail("C_SUMMARY_INVALID", "Each support source must be an object");
            text(source, "relativePath");
            hash(source, "sha256");
            nonNegative(source, "bytes");
        }

        JsonNode compile = object(root, "compile");
        if (!bool(compile, "attempted") || integer(compile, "exitCode") != 0) {
            fail("C_COMPILE_INCOMPLETE", "C summary compile must be attempted with exitCode 0");
        }

        JsonNode runtime = object(root, "runtime");
        if (!bool(runtime, "attempted") || !"success".equals(textValue(runtime, "startup"))) {
            fail("C_RUNTIME_INCOMPLETE", "C summary runtime must be attempted with startup success");
        }

        JsonNode tests = object(root, "tests");
        if (integer(tests, "reports") <= 0 || integer(tests, "run") <= 0
                || integer(tests, "failures") != 0 || integer(tests, "errors") != 0
                || integer(tests, "skipped") != 0) {
            fail("C_TESTS_INCOMPLETE", "C summary tests must have run without failures, errors or skips");
        }
        JsonNode suites = array(tests, "suites");
        Set<String> requiredNames = new HashSet<>(Set.of(requiredSuite));
        if (org.ipro.design.RecipeCatalog.INDEPENDENT_REGISTER_RLS.equals(plan.recipe()))
            requiredNames.add("com.example.generated.harness.RegisterRlsSecurityTest");
        Set<String> completedRequired = new HashSet<>();
        if (suites.isEmpty()) {
            fail("C_TESTS_INCOMPLETE", "C summary tests.suites must not be empty");
        }
        int run = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        boolean required = false;
        Set<String> names = new HashSet<>();
        for (JsonNode suite : suites) {
            if (!suite.isObject()) fail("C_SUMMARY_INVALID", "Each suite must be an object");
            String name = textValue(suite, "name");
            if (!names.add(name)) fail("C_SUMMARY_INVALID", "Suite name is duplicated: " + name);
            boolean isRequired = bool(suite, "required");
            if (isRequired && !requiredNames.contains(name)) {
                fail("C_TESTS_INCOMPLETE",
                        "Required suite must be " + requiredSuite + ": " + name);
            }
            int suiteRun = integer(suite, "run");
            int suiteFailures = integer(suite, "failures");
            int suiteErrors = integer(suite, "errors");
            int suiteSkipped = integer(suite, "skipped");
            if (suiteRun < 0 || suiteFailures < 0 || suiteErrors < 0 || suiteSkipped < 0) {
                fail("C_SUMMARY_INVALID", "Suite counts must be non-negative: " + name);
            }
            if (isRequired && (suiteRun <= 0 || suiteFailures != 0 || suiteErrors != 0 || suiteSkipped != 0)) {
                fail("C_TESTS_INCOMPLETE", "Required suite did not pass without skips: " + name);
            }
            required |= isRequired;
            if (isRequired) completedRequired.add(name);
            run += suiteRun;
            failures += suiteFailures;
            errors += suiteErrors;
            skipped += suiteSkipped;
        }
        if (!required || !completedRequired.containsAll(requiredNames)) {
            fail("C_TESTS_INCOMPLETE", "C summary has no required suite");
        }
        if (run != integer(tests, "run") || failures != integer(tests, "failures")
                || errors != integer(tests, "errors") || skipped != integer(tests, "skipped")) {
            fail("C_SUMMARY_INVALID", "C summary test totals do not equal the suite sum");
        }
    }

    private void verifySourceSet(List<GenerationFile> files) throws ApplyFailure {
        JsonNode sources = root.get("sources");
        if (sources == null) {
            if (files.size() > 1) {
                fail("C_SUMMARY_MISMATCH", "A multi-file C summary must list every generated source");
            }
            return;
        }
        if (!sources.isArray() || sources.size() != manifest.entries().size()) {
            fail("C_SUMMARY_MISMATCH", "C summary sources must equal the manifest entries");
        }
        for (int index = 0; index < sources.size(); index++) {
            JsonNode source = sources.get(index);
            if (!source.isObject()) fail("C_SUMMARY_INVALID", "Each generated source must be an object");
            GenerationManifest.Entry entry = manifest.entries().get(index);
            if (!entry.relativePath().equals(textValue(source, "relativePath"))
                    || !entry.sha256().equals(textValue(source, "sha256"))
                    || entry.bytes() != integer(source, "bytes")) {
                fail("C_SUMMARY_MISMATCH",
                        "C summary generated source does not match manifest entry: " + entry.relativePath());
            }
        }
        String setHash = textValue(root, "sourceSetSha256");
        if (!ApplyHash.isSha256(setHash) || !setHash.equals(manifest.manifestSha256())) {
            fail("C_SUMMARY_MISMATCH", "C summary sourceSetSha256 does not match the manifest hash");
        }
    }

    void verifyUnchanged(Path input, ApplyStatus failureStatus) throws ApplyFailure {
        byte[] again;
        try {
            again = Files.readAllBytes(input.toAbsolutePath().normalize());
        } catch (IOException ex) {
            throw new ApplyFailure(failureStatus, "C_SUMMARY_CHANGED",
                    "C summary became unreadable during apply", ex);
        }
        if (!ApplyHash.sha256(again).equals(sha256)) {
            throw new ApplyFailure(failureStatus, "C_SUMMARY_CHANGED",
                    "C summary changed during apply");
        }
    }

    private void text(JsonNode parent, String field, String expected) throws ApplyFailure {
        String value = textValue(parent, field);
        if (!value.equals(expected)) {
            fail("C_SUMMARY_MISMATCH", "C field " + field + " is " + value + ", expected " + expected);
        }
    }

    private static String textValue(JsonNode parent, String field) throws ApplyFailure {
        JsonNode value = parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            fail("C_SUMMARY_INVALID", "C field is required: " + field);
        }
        return value.asText();
    }

    private String text(JsonNode parent, String field) throws ApplyFailure {
        return textValue(parent, field);
    }

    private void hash(JsonNode parent, String field) throws ApplyFailure {
        String value = textValue(parent, field);
        if (!ApplyHash.isSha256(value)) {
            fail("C_SUMMARY_INVALID", "C field must be a sha256: " + field);
        }
    }

    private boolean bool(JsonNode parent, String field) throws ApplyFailure {
        JsonNode value = parent.get(field);
        if (value == null || !value.isBoolean()) {
            fail("C_SUMMARY_INVALID", "C field must be a boolean: " + field);
        }
        return value.asBoolean();
    }

    private int integer(JsonNode parent, String field) throws ApplyFailure {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            fail("C_SUMMARY_INVALID", "C field must be an integer: " + field);
        }
        return value.asInt();
    }

    private void nonNegative(JsonNode parent, String field) throws ApplyFailure {
        if (integer(parent, field) < 0) {
            fail("C_SUMMARY_INVALID", "C field must be non-negative: " + field);
        }
    }

    private JsonNode object(JsonNode parent, String field) throws ApplyFailure {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            fail("C_SUMMARY_INVALID", "C field must be an object: " + field);
        }
        return value;
    }

    private JsonNode array(JsonNode parent, String field) throws ApplyFailure {
        JsonNode value = parent.get(field);
        if (value == null || !value.isArray()) {
            fail("C_SUMMARY_INVALID", "C field must be an array: " + field);
        }
        return value;
    }

    private static void fail(String code, String message) throws ApplyFailure {
        throw new ApplyFailure(ApplyStatus.REJECTED, code, message);
    }
}
