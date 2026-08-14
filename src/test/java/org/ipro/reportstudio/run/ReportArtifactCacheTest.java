package org.ipro.reportstudio.run;

import net.sf.jasperreports.engine.JasperPrint;
import org.ipro.reportstudio.dom.ReportTemplate;
import org.ipro.reportstudio.param.ReportContext;
import org.ipro.reportstudio.param.ResolvedParams;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ReportArtifactCacheTest {

    private final ReportTemplate template = new ReportTemplate();

    @Test
    void putAndGet() {
        ReportArtifactCache cache = new ReportArtifactCache();
        JasperPrint print = mock(JasperPrint.class);
        String key = "k1";
        assertTrue(cache.get(key).isEmpty());
        cache.put(key, print);
        assertEquals(print, cache.get(key).orElseThrow());
        assertEquals(1, cache.size());
    }

    @Test
    void evictsEldestWhenOverCapacity() {
        ReportArtifactCache cache = new ReportArtifactCache(2);
        cache.put("a", mock(JasperPrint.class));
        cache.put("b", mock(JasperPrint.class));
        cache.put("c", mock(JasperPrint.class));
        assertEquals(2, cache.size());
        assertTrue(cache.get("a").isEmpty());
        assertTrue(cache.get("b").isPresent());
        assertTrue(cache.get("c").isPresent());
    }

    @Test
    void lruKeepsRecentlyUsed() {
        ReportArtifactCache cache = new ReportArtifactCache(2);
        cache.put("a", mock(JasperPrint.class));
        cache.put("b", mock(JasperPrint.class));
        cache.get("a");
        cache.put("c", mock(JasperPrint.class));
        assertTrue(cache.get("a").isPresent());
        assertTrue(cache.get("b").isEmpty());
    }

    @Test
    void keyChangesWithUser() {
        template.setId(1L);
        template.setVersion(1L);
        String base = ReportArtifactCache.key(template, ResolvedParams.ok(Map.of()),
            ReportContext.empty("alice"), "ru", "UTC");
        String other = ReportArtifactCache.key(template, ResolvedParams.ok(Map.of()),
            ReportContext.empty("bob"), "ru", "UTC");
        assertNotEquals(base, other);
    }

    @Test
    void keyChangesWithBindings() {
        template.setId(1L);
        template.setVersion(1L);
        String base = ReportArtifactCache.key(template, ResolvedParams.ok(Map.of("p1", 1L)),
            ReportContext.empty("alice"), "ru", "UTC");
        String other = ReportArtifactCache.key(template, ResolvedParams.ok(Map.of("p1", 2L)),
            ReportContext.empty("alice"), "ru", "UTC");
        assertNotEquals(base, other);
    }

    @Test
    void keyChangesWithTemplateVersion() {
        template.setId(1L);
        template.setVersion(1L);
        String base = ReportArtifactCache.key(template, ResolvedParams.ok(Map.of()),
            ReportContext.empty("alice"), "ru", "UTC");
        ReportTemplate updated = new ReportTemplate();
        updated.setId(1L);
        updated.setVersion(2L);
        String other = ReportArtifactCache.key(updated, ResolvedParams.ok(Map.of()),
            ReportContext.empty("alice"), "ru", "UTC");
        assertNotEquals(base, other);
    }
}