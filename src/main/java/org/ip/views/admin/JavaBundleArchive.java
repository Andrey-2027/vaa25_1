package org.ip.views.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.ipro.design.RecipeCatalog;
import org.ipro.design.generation.EntityJavaGenerator;
import org.ipro.design.generation.GenerationFile;
import org.ipro.design.generation.GenerationManifest;
import org.ipro.design.generation.GenerationPlan;

/** IO/UI adapter for exact single-file delivery or a reviewed N-file Java bundle. */
final class JavaBundleArchive {
    static final String MANIFEST_ENTRY = "generation-manifest.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JavaBundleArchive() {
    }

    static String fileName(GenerationPlan plan) {
        return plan.files().size() == 1
                ? plan.source().entity().className() + ".java"
                : plan.source().entity().className()
                    + (RecipeCatalog.CUSTOM_PLAIN_V2.equals(plan.recipe()) ? "-java-ddl.zip" : "-java.zip");
    }

    static String contentType(GenerationPlan plan) {
        return plan.files().size() == 1
                ? "text/x-java-source; charset=UTF-8"
                : "application/zip";
    }

    /** A single file stays byte-for-byte Java; a set is a ZIP rooted at the manifest. */
    static byte[] bytes(GenerationPlan plan) throws IOException {
        if (plan.files().size() == 1) {
            return plan.files().getFirst().content().getBytes(StandardCharsets.UTF_8);
        }
        GenerationManifest manifest;
        try {
            manifest = GenerationManifest.from(plan,
                    EntityJavaGenerator.fingerprintProfile(plan.recipe()));
        } catch (IllegalArgumentException ex) {
            throw new IOException("The reviewed plan cannot produce a manifest", ex);
        }
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, MANIFEST_ENTRY, manifestBytes(manifest));
            for (GenerationFile file : plan.files()) {
                put(zip, file.relativePath(), file.content().getBytes(StandardCharsets.UTF_8));
            }
        }
        return out.toByteArray();
    }

    private static byte[] manifestBytes(GenerationManifest manifest) throws IOException {
        byte[] json = MAPPER.writeValueAsBytes(manifest);
        String text = new String(json, StandardCharsets.UTF_8);
        if (text.indexOf('\r') >= 0) throw new IOException("Manifest JSON must use LF");
        if (!text.endsWith("\n")) text += "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        var entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }
}
