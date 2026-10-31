package org.ipro.design.apply;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import org.ipro.design.generation.GenerationFile;
import org.ipro.design.generation.GenerationPlan;

/**
 * Read-only adapter for the documented subset of the current C summary. Unknown fields are
 * allowed; missing or mistyped required fields are refusals. The hash pins the exact evidence
 * bytes but is not a signature.
 */
final class CSummary {
    static final String CONTRACT_VERSION = "g1-c-summary-v1";
    private static final long MAX_BYTES = 1_048_576;

    private final byte[] bytes;
    private final String sha256;
    private final JsonNode root;

    private CSummary(byte[] bytes, JsonNode root) {
        this.bytes = bytes;
        this.sha256 = ApplyHash.sha256(bytes);
        this.root = root;
    }

    static CSummary read(Path input) throws ApplyFailure {
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
            return new CSummary(bytes, root);
        } catch (IOException ex) {
            throw new ApplyFailure(ApplyStatus.REJECTED, "C_SUMMARY_INVALID",
                    "Cannot read C summary: " + path, ex);
        }
    }

    String sha256() {
        return sha256;
    }

    void verify(GenerationPlan plan, GenerationFile file) throws ApplyFailure {
        text("mode", "generated-acceptance");
        text("status", "success");
        text("plan.planId", plan.planId());
        text("plan.recipe", plan.recipe().id() + "@" + plan.recipe().version());
        text("plan.generatorVersion", plan.generatorVersion());
        text("source.relativePath", file.relativePath());
        text("source.sha256", file.sha256());

        JsonNode maven = object("maven");
        bool(maven, "attempted", true);
        integer(maven, "exitCode", 0);

        JsonNode tests = object("tests");
        positive(tests, "reports");
        positive(tests, "run");
        zero(tests, "failures");
        zero(tests, "errors");
        zero(tests, "skipped");

        byte[] content = file.content().getBytes(StandardCharsets.UTF_8);
        JsonNode source = object("source");
        JsonNode expected = source.get("expectedSha256");
        if (expected != null) {
            if (!expected.isTextual() || !ApplyHash.isSha256(expected.asText())
                    || !expected.asText().equals(file.sha256())) {
                fail("C_EXPECTED_HASH_MISMATCH", "C source.expectedSha256 does not match the plan content");
            }
        }
        JsonNode bytesNode = source.get("bytes");
        if (bytesNode != null) {
            if (!bytesNode.isIntegralNumber() || bytesNode.asLong() != content.length) {
                fail("C_BYTES_MISMATCH", "C source.bytes does not match the plan content");
            }
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

    private JsonNode object(String name) throws ApplyFailure {
        JsonNode value = resolve(name);
        if (!value.isObject()) {
            fail("C_SUMMARY_TYPE", "C field must be an object: " + name);
        }
        return value;
    }

    private void text(String name, String expected) throws ApplyFailure {
        JsonNode value = resolve(name);
        if (!value.isTextual()) {
            fail("C_SUMMARY_TYPE", "C field must be a string: " + name);
        }
        if (!Objects.equals(value.asText(), expected)) {
            fail("C_SUMMARY_MISMATCH", "C field " + name + " is " + value.asText()
                    + ", expected " + expected);
        }
    }

    private void bool(JsonNode parent, String name, boolean expected) throws ApplyFailure {
        JsonNode value = parent.get(name);
        if (value == null) {
            fail("C_SUMMARY_MISSING", "C field is required: maven." + name);
        }
        if (!value.isBoolean()) {
            fail("C_SUMMARY_TYPE", "C field must be a boolean: maven." + name);
        }
        if (value.asBoolean() != expected) {
            fail("C_SUMMARY_MISMATCH", "C field maven." + name + " must be " + expected);
        }
    }

    private void integer(JsonNode parent, String name, int expected) throws ApplyFailure {
        JsonNode value = parent.get(name);
        if (value == null) {
            fail("C_SUMMARY_MISSING", "C field is required: " + name);
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            fail("C_SUMMARY_TYPE", "C field must be an integer: " + name);
        }
        if (value.asInt() != expected) {
            fail("C_SUMMARY_MISMATCH", "C field " + name + " must be " + expected);
        }
    }

    private void positive(JsonNode parent, String name) throws ApplyFailure {
        JsonNode value = parent.get(name);
        if (value == null) {
            fail("C_SUMMARY_MISSING", "C field is required: tests." + name);
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            fail("C_SUMMARY_TYPE", "C field must be an integer: tests." + name);
        }
        if (value.asInt() <= 0) {
            fail("C_TESTS_INCOMPLETE", "C field tests." + name + " must be positive");
        }
    }

    private void zero(JsonNode parent, String name) throws ApplyFailure {
        JsonNode value = parent.get(name);
        if (value == null) {
            fail("C_SUMMARY_MISSING", "C field is required: tests." + name);
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            fail("C_SUMMARY_TYPE", "C field must be an integer: tests." + name);
        }
        if (value.asInt() != 0) {
            fail("C_TESTS_INCOMPLETE", "C field tests." + name + " must be zero");
        }
    }

    private JsonNode resolve(String dotted) throws ApplyFailure {
        JsonNode current = root;
        for (String part : dotted.split("\\.")) {
            current = current == null ? null : current.get(part);
            if (current == null) {
                fail("C_SUMMARY_MISSING", "C summary field is required: " + dotted);
            }
        }
        return current;
    }

    private static void fail(String code, String message) throws ApplyFailure {
        throw new ApplyFailure(ApplyStatus.REJECTED, code, message);
    }

}
