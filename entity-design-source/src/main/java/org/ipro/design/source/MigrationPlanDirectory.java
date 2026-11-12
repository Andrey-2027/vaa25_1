package org.ipro.design.source;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Properties;

/** A reviewable multi-file plan with original contents, hashes and a checked diff. */
final class MigrationPlanDirectory {
    static final String MANIFEST = "form-migration.properties";
    private MigrationPlanDirectory() {}

    static void write(Path directory, FormSettingsMigration.Plan plan) {
        var values = new Properties();
        values.setProperty("format", "form-migration-v1");
        values.setProperty("entity", plan.entity());
        values.setProperty("count", Integer.toString(plan.files().size()));
        for (int i = 0; i < plan.files().size(); i++) {
            var file = plan.files().get(i);
            String key = "file." + i + ".";
            values.setProperty(key + "path", file.path());
            values.setProperty(key + "baseSha256", file.baseHash());
            values.setProperty(key + "newSha256", file.newHash());
            values.setProperty(key + "base", encode(file.before() == null ? "" : file.before()));
            values.setProperty(key + "new", encode(file.after()));
        }
        values.setProperty("planId", fingerprint(values));
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("changes.diff"), plan.diff(), StandardCharsets.UTF_8);
            var text = new StringWriter();
            values.store(text, "Form column migration; explicit composition is preserved. No schema changes.");
            Files.writeString(directory.resolve(MANIFEST), text.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) { throw invalid(directory, ex.toString()); }
    }

    static FormSettingsMigration.Plan read(Path directory) {
        try {
            var values = new Properties();
            values.load(new StringReader(Files.readString(directory.resolve(MANIFEST), StandardCharsets.UTF_8)));
            if (!"form-migration-v1".equals(values.getProperty("format"))
                    || !fingerprint(values).equals(values.getProperty("planId"))) throw invalid(directory, "Invalid plan manifest");
            int count = Integer.parseInt(values.getProperty("count"));
            if (count < 0 || count > 2) throw invalid(directory, "Expected at most two changed files");
            var files = new ArrayList<FormSettingsMigration.FileChange>();
            for (int i = 0; i < count; i++) {
                String key = "file." + i + ".";
                String before = decode(values.getProperty(key + "base"));
                String after = decode(values.getProperty(key + "new"));
                var file = new FormSettingsMigration.FileChange(values.getProperty(key + "path"),
                    "ABSENT".equals(values.getProperty(key + "baseSha256")) ? null : before, after);
                if (!file.baseHash().equals(values.getProperty(key + "baseSha256")) || !file.newHash().equals(values.getProperty(key + "newSha256")))
                    throw invalid(directory, "Content hash mismatch");
                files.add(file);
            }
            var plan = new FormSettingsMigration.Plan(values.getProperty("entity"), files);
            if (!Files.readString(directory.resolve("changes.diff"), StandardCharsets.UTF_8).equals(plan.diff()))
                throw invalid(directory, "Diff does not match plan");
            return plan;
        } catch (IOException | IllegalArgumentException | NullPointerException ex) { throw invalid(directory, ex.toString()); }
    }
    private static String fingerprint(Properties values) {
        var canonical = new StringBuilder();
        values.stringPropertyNames().stream().filter(k -> !k.equals("planId")).sorted()
            .forEach(k -> canonical.append(k.length()).append(':').append(k).append(values.getProperty(k).length()).append(':').append(values.getProperty(k)));
        return FormSettingsMigration.hash(canonical.toString());
    }
    private static String encode(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String text) { return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8); }
    private static SourceRefusal invalid(Path path, String reason) { return new SourceRefusal(SourceRefusal.Code.PLAN_INVALID, path.toString(), reason); }
}
