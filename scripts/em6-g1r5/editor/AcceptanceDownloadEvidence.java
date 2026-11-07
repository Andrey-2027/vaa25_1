package org.ip.views.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.ipro.design.generation.EntityJavaGenerator;
import org.ipro.design.generation.GenerationManifest;
import org.ipro.design.generation.GenerationPlan;
import org.ipro.design.json.DraftJsonCodec;

/** Installed only in the disposable editor copy. Called after the real download write, under its UI lock. */
public final class AcceptanceDownloadEvidence {
    private AcceptanceDownloadEvidence() { }
    public static void capture(GenerationPlan plan) throws IOException {
        String root = System.getProperty("acceptance.evidence");
        if (root == null) throw new IOException("acceptance.evidence is required on the isolated editor");
        var directory = Path.of(root).resolve(plan.planId());
        // A repeated download cannot overwrite its first captured plan/context.
        if (Files.exists(directory.resolve("complete.json"))) return;
        Files.createDirectories(directory);
        var mapper = new ObjectMapper().writerWithDefaultPrettyPrinter();
        Files.write(directory.resolve("draft.json"), new DraftJsonCodec().encode(plan.source()));
        Files.writeString(directory.resolve("context.json"), mapper.writeValueAsString(plan.context()), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("plan.json"), mapper.writeValueAsString(plan), StandardCharsets.UTF_8);
        var manifest = GenerationManifest.from(plan, EntityJavaGenerator.fingerprintProfile(plan.recipe()));
        Files.writeString(directory.resolve("manifest.json"), mapper.writeValueAsString(manifest), StandardCharsets.UTF_8);
        for (var file : plan.files()) {
            var target=directory.resolve("source").resolve(file.relativePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target,file.content(),StandardCharsets.UTF_8);
        }
        if (plan.files().size() == 1) {
            var file=plan.files().getFirst();
            var receipt=new LinkedHashMap<String,Object>();
            receipt.put("planId",plan.planId());
            receipt.put("recipe",plan.recipe().id()+"@"+plan.recipe().version());
            receipt.put("generatorVersion",plan.generatorVersion());
            receipt.put("fingerprintProfile",manifest.fingerprintProfile());
            receipt.put("relativePath",file.relativePath());
            receipt.put("bytes",file.content().getBytes(StandardCharsets.UTF_8).length);
            receipt.put("sha256",file.sha256());
            receipt.put("lineEndings","LF");
            receipt.put("manifestSha256",manifest.manifestSha256());
            Files.writeString(directory.resolve("receipt.json"),mapper.writeValueAsString(receipt),StandardCharsets.UTF_8);
        }
        Files.writeString(directory.resolve("complete.json"),mapper.writeValueAsString(java.util.Map.of(
            "planId",plan.planId(),"origin","P8 download handler completed its write; browser-file SHA-256 comparison is still required")),StandardCharsets.UTF_8);
    }
}
