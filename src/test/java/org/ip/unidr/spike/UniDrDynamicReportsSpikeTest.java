package org.ip.unidr.spike;

import net.sf.jasperreports.engine.JasperPrint;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class UniDrDynamicReportsSpikeTest {
    @BeforeAll static void fonts() {
        System.setProperty("net.sf.jasperreports.default.fontname","DejaVu Sans");
        System.setProperty("net.sf.jasperreports.pdf.embedded","true");
    }

    @ParameterizedTest
    @CsvSource({"0,false","1,false","2,false","3,false","0,true","1,true","2,true","3,true"})
    void nativeTotalsAreIndependentOfVisibleDetailAndGroupDepth(int depth,boolean details) throws Exception {
        JasperPrint print=UniDrRenderSpike.compile(UniDrRenderSpike.fixture(),depth,details);
        List<UniDrRenderSpike.Line> lines=UniDrRenderSpike.lines(print);
        assertThat(lines).filteredOn(line->line.label().equals("Всего")).singleElement()
            .satisfies(line->assertThat(normalize(line.cells())).containsExactly("Всего","36,00","6,00","2,00","10,00","6"));
        assertThat(lines.stream().filter(line->line.label().startsWith("Позиция ")).count())
            .isEqualTo(details?6:0);
        if(depth>=1) {
            String prefix=details?"Итого: ":"";
            assertThat(lines).filteredOn(line->line.label().equals(prefix+"Цех A")).singleElement()
                .satisfies(line->assertThat(normalize(line.cells())).containsExactly(prefix+"Цех A","15,00","5,00","2,00","9,00","3"));
            assertThat(lines).filteredOn(line->line.label().equals(prefix+"Цех B")).singleElement()
                .satisfies(line->assertThat(normalize(line.cells())).containsExactly(prefix+"Цех B","21,00","7,00","5,00","10,00","3"));
        }
        if(depth>=2) {
            String prefix=details?"Итого: ":"";
            assertThat(lines).filteredOn(line->line.label().equals(prefix+"Деталь X"))
                .extracting(line->normalize(line.cells())).containsExactly(
                    List.of(prefix+"Деталь X","6,00","3,00","2,00","4,00","2"),
                    List.of(prefix+"Деталь X","15,00","7,50","5,00","10,00","2"));
        }
        if(depth>=3) assertThat(lines).anyMatch(line->line.label().endsWith("02.10.2026") && line.cells().size()==6);
        UniDrRenderSpike.artifacts("groups-"+depth+(details?"-details":"-summary"),print);
    }

    @Test void identityKeysDoNotMergeEqualCaptionsAndNeverAppearAsColumns() throws Exception {
        var rows=List.of(
            UniDrRenderSpike.row(919191L,"Одинаковая подпись",11L,"X",1L,"День","Позиция 1","2"),
            UniDrRenderSpike.row(929292L,"Одинаковая подпись",11L,"X",1L,"День","Позиция 2","4"));
        JasperPrint print=UniDrRenderSpike.compile(rows,1,false);
        assertThat(UniDrRenderSpike.lines(print)).filteredOn(line->line.label().equals("Одинаковая подпись"))
            .extracting(line->normalize(line.cells()).get(1)).containsExactly("2,00","4,00");
        String html=UniDrRenderSpike.html(print);
        assertThat(html).doesNotContain("919191","929292","key0","row_marker");
    }

    @Test void countIncludesNullAndZeroWhileNumericAggregationsSkipNull() throws Exception {
        var rows=List.of(
            UniDrRenderSpike.row(null,null,11L,"X",1L,"День","Позиция 1",null),
            UniDrRenderSpike.row(null,null,11L,"X",1L,"День","Позиция 2","0"),
            UniDrRenderSpike.row(null,null,11L,"X",1L,"День","Позиция 3","6"));
        var print=UniDrRenderSpike.compile(rows,1,false);
        assertThat(UniDrRenderSpike.lines(print)).filteredOn(line->line.label().equals("Не заполнено"))
            .singleElement().satisfies(line->assertThat(normalize(line.cells()))
                .containsExactly("Не заполнено","6,00","3,00","0,00","6,00","3"));
        var allNull=UniDrRenderSpike.compile(List.of(rows.getFirst()),1,false);
        assertThat(UniDrRenderSpike.lines(allNull)).filteredOn(line->line.label().equals("Всего"))
            .singleElement().satisfies(line->assertThat(normalize(line.cells())).containsExactly("Всего","1"));
    }

    @Test void theSameJasperPrintProvidesEscapedHtmlEmbeddedCyrillicPdfAndNumericXlsx() throws Exception {
        var data=List.of(UniDrRenderSpike.row(1L,"Цех <script>alert('x')</script>",11L,"X",1L,
            "День","Позиция 1","12.5"));
        JasperPrint print=UniDrRenderSpike.compile(data,1,false);
        String html=UniDrRenderSpike.html(print);
        assertThat(html).contains("&lt;script&gt;").doesNotContain("<script>alert('x')</script>");
        byte[] pdf=net.sf.jasperreports.engine.JasperExportManager.exportReportToPdf(print);
        try(var document=Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("Цех","Только итоги","12,50");
        }
        assertThat(new String(pdf,StandardCharsets.ISO_8859_1)).contains("/FontFile2");
        String worksheet="";
        try(var zip=new ZipInputStream(new ByteArrayInputStream(UniDrRenderSpike.xlsx(print)))) {
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) if(entry.getName().equals("xl/worksheets/sheet1.xml"))
                worksheet=new String(zip.readAllBytes(),StandardCharsets.UTF_8);
        }
        assertThat(worksheet).contains("<v>12.5</v>");
    }

    @Test void pageBreaksPreserveEveryDetailAndTheSingleGrandTotal() throws Exception {
        var rows=java.util.stream.LongStream.rangeClosed(1,90).mapToObj(index->
            UniDrRenderSpike.row(1L,"Цех A",11L,"Деталь X",1L,"День","Позиция "+index,"1")).toList();
        JasperPrint print=UniDrRenderSpike.compile(rows,2,true);
        assertThat(print.getPages()).hasSizeGreaterThan(1);
        var lines=UniDrRenderSpike.lines(print);
        assertThat(lines.stream().filter(line->line.label().startsWith("Позиция ")).count()).isEqualTo(90);
        assertThat(lines).filteredOn(line->line.label().equals("Всего")).singleElement()
            .satisfies(line->assertThat(normalize(line.cells())).containsExactly("Всего","90,00","1,00","1,00","1,00","90"));
        String html=UniDrRenderSpike.html(print);
        for(int page=1;page<=print.getPages().size();page++) assertThat(html).contains("JR_PAGE_ANCHOR_0_"+page);
    }

    private List<String> normalize(List<String> cells) {
        return cells.stream().map(text->text.replace('\u00a0',' ').replace('\u202f',' ').strip()).toList();
    }
}
