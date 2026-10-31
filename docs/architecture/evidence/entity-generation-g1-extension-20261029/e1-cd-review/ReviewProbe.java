package org.ipro.design.apply;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.lang.reflect.*;
import java.io.*;
import org.ipro.design.*;
import org.ipro.design.generation.*;

public class ReviewProbe {
    static Object invoke(String name, Object... args) throws Exception {
        for (var m : ApplyHarness.class.getDeclaredMethods()) if (m.getName().equals(name)) {
            m.setAccessible(true); return m.invoke(null, args);
        }
        throw new AssertionError(name);
    }
    static Object get(Object obj, String name) throws Exception {
        var field = obj.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(obj);
    }
    public static void main(String[] args) throws Exception {
        var root=Path.of(args[0]); Files.createDirectories(root);
        for (var mode : new String[]{"changed-after-publish", "late-target", "foreign-identical-rollback", "journal-failure-after-receipt", "wrong-required-suite"}) {
            var dir=root.resolve(mode); Files.createDirectories(dir);
            var v2=invoke("v2Fixture",dir); var project=invoke("projectV2",dir,v2);
            var fixture=get(v2,"fixture"); var plan=(GenerationPlan)get(fixture,"plan");
            var draft=(EntityDraft)get(fixture,"draft"); var ctx=(ProjectDesignContext)get(fixture,"context");
            var request=(ApplyRequest)invoke("v2Request",project,v2);
            var target=request.projectRoot().resolve("src/main/java").resolve(plan.files().getFirst().relativePath());
            var journal=request.projectRoot().resolve(".g1-apply-v2/journal.json");
            if(mode.equals("wrong-required-suite")) {
                var json=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.readTree(Files.readAllBytes(request.cSummary()));
                ((com.fasterxml.jackson.databind.node.ObjectNode)json.at("/tests/suites/0")).put("name","unrelated.FakeSuite");
                Files.writeString(request.cSummary(),Json.write(json));
            }
            var hooks=new ApplyHooks(){
                public void beforePublish(Path p) throws IOException {
                    if(mode.equals("late-target")) Files.writeString(p,"foreign late target");
                }
                public void afterPublish(Path p) throws IOException {
                    if(mode.equals("changed-after-publish")) Files.writeString(p,"foreign modified bytes");
                }
                public void beforeReceipt(Path p) throws IOException {
                    if(mode.equals("foreign-identical-rollback")) {
                        var attrs=Files.readAttributes(target,BasicFileAttributes.class);
                        var bytes=Files.readAllBytes(target); Files.delete(target); Files.write(target,bytes);
                        Files.getFileAttributeView(target,BasicFileAttributeView.class).setTimes(attrs.lastModifiedTime(),attrs.lastAccessTime(),attrs.creationTime());
                        throw new IOException("injected receipt failure after foreign identical replacement");
                    }
                    if(mode.equals("journal-failure-after-receipt")) Files.setAttribute(journal,"dos:readonly",true);
                }
            };
            var result=new GenerationApplyAdapter(hooks).apply(request,plan,draft,ctx);
            var out=Json.object(); out.put("mode",mode); out.put("status",result.status().name());out.put("code",result.code());
            out.put("targetExists",Files.exists(target)); out.put("receiptExists",result.receipt()!=null && Files.exists(result.receipt()));
            out.put("targetHashMatches",Files.exists(target) && ApplyHash.sha256(target).equals(plan.files().getFirst().sha256()));
            out.put("journalExists",Files.exists(journal));
            Files.writeString(dir.resolve("result.json"),Json.write(out)); System.out.println(Json.write(out));
            if(Files.exists(journal) && Files.isRegularFile(journal)) Files.setAttribute(journal,"dos:readonly",false);
        }
    }
}
