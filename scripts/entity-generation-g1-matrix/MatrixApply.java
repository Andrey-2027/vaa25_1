package org.ipro.design.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Arrays;
import org.ipro.design.*;
import org.ipro.design.generation.*;
import org.ipro.design.json.DraftJsonCodec;
import org.ipro.design.apply.*;

/** Scenario caller: rebuild from current owner inputs; never deserialize a trusted GenerationPlan. */
public final class MatrixApply {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]), project = Path.of(args[1]), summary = Path.of(args[2]), output = Path.of(args[3]);
        String expected = args[4];
        var mapper = new ObjectMapper();
        var draft = new DraftJsonCodec().decode(Files.readAllBytes(input.resolve("current-draft.json")));
        var context = mapper.readValue(input.resolve("current-context.json").toFile(), ProjectDesignContext.class);
        var validated = new DraftValidator().validate(draft, context);
        var checked = validated.checkedStructure().orElseThrow(() -> new IllegalStateException("Current draft rejected: " + validated.problems()));
        var plan = new CatalogJavaGenerator().generate(checked, draft, context).plan().orElseThrow();
        var reviewed = mapper.readTree(input.resolve("plan-receipt.json").toFile());
        var file = plan.files().getFirst();
        if (!plan.planId().equals(reviewed.path("planId").asText())
                || !file.sha256().equals(reviewed.path("sha256").asText())
                || !file.relativePath().equals(reviewed.path("relativePath").asText())
                || !Arrays.equals(Files.readAllBytes(input.resolve(file.relativePath())), file.content().getBytes(StandardCharsets.UTF_8)))
            throw new IllegalStateException("Current input no longer matches the reviewed P8 download");
        Path target = project.resolve("src/main/java").resolve(file.relativePath());
        byte[] changed = null;
        if (expected.equals("CONFLICT")) {
            changed = (file.content() + "// User edit retained by conflict probe\n").getBytes(StandardCharsets.UTF_8);
            Files.write(target, changed);
        }
        var result = new GenerationApplyAdapter().apply(new ApplyRequest(project, "src/main/java", summary), plan, draft, context);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("status", result.status().name()); evidence.put("code", result.code()); evidence.put("message", result.message());
        evidence.put("planId", result.planId()); evidence.put("contentSha256", result.contentSha256()); evidence.put("cSummarySha256", result.cSummarySha256());
        evidence.put("target", String.valueOf(result.target())); evidence.put("receipt", String.valueOf(result.receipt()));
        evidence.put("journal", String.valueOf(result.journal())); evidence.put("applyId", result.applyId());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n");
        if (!result.status().name().equals(expected)) throw new IllegalStateException("Expected " + expected + ", got " + result);
        if (changed != null && !Arrays.equals(changed, Files.readAllBytes(target))) throw new IllegalStateException("Conflict overwrote user content");
        if (changed == null && !Arrays.equals(file.content().getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target)))
            throw new IllegalStateException("Applied bytes differ from reviewed source");
        System.out.println(result.status() + " " + result.code() + " " + plan.planId());
    }
}
