package com.tenderpocket.services;

import com.tenderpocket.config.ComplianceProgressUiInjectionFilter;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import static org.junit.jupiter.api.Assertions.*;

class SpecificationSheetOutputTest {
    private String[] row(String number, String text, String type, String page) {
        return new String[]{number, text, "", "", "", "Arbitrary Pump 40 L", "-", page,
                type, "3", "Technical Specifications", "7"};
    }

    @Test
    void contentPreservesSectionsContinuationsAndConflicts() {
        List<String[]> input = List.of(
                row("3.10", "Power input: 220-240V", "requirement", "PDF p. 1"),
                row("3.10", "at 50 Hz.", "continuation", "PDF p. 2"),
                row("3.10", "Power input: 220-240V at 50 Hz.", "requirement", "PDF p. 8"),
                row("3.11", "Capacity: 40 litres.", "requirement", "PDF p. 2"),
                row("3.11", "Capacity: 50 litres.", "requirement", "PDF p. 9"));
        var product = SpecificationSheetContent.from(input).get(0);
        assertEquals("7", product.schedule());
        assertEquals(3, product.clauseCount());
        assertTrue(product.rows().get(0).heading());
        assertEquals("3.10", product.rows().get(1).reference());
        assertEquals("Power input: 220-240V at 50 Hz.", product.rows().get(1).wording());
        assertTrue(product.rows().get(1).sources().contains("PDF p. 8"));
        assertEquals(1, product.clarifications().size());
        assertTrue(product.fileStem(1).startsWith("7_Technical_Data_Sheet_"));
    }

    @Test
    void blankReferencesReceiveStableDisplayNumbersWithoutChangingSourceReferences() {
        List<String[]> input = List.of(
                row("", "General requirements", "heading", "PDF p. 1"),
                row("3.10", "Power input: 220-240V", "requirement", "PDF p. 1"),
                row("", "Ingress protection shall be IP54.", "requirement", "PDF p. 2"));

        var rows = SpecificationSheetContent.from(input).get(0).rows();

        assertEquals("1", rows.get(0).reference());
        assertEquals("3.10", rows.stream().filter(row -> row.wording().startsWith("Power input"))
                .findFirst().orElseThrow().reference());
        for (int i = 0; i < rows.size(); i++) {
            assertFalse(rows.get(i).reference().isBlank());
            if (rows.get(i).wording().startsWith("Ingress protection")) {
                assertEquals(String.valueOf(i + 1), rows.get(i).reference());
            }
        }
    }

    @Test
    void usageMetricsCalculateInrWithoutDoubleBillingReasoningTokens() {
        var metrics = new ComplianceConversionMetrics("0.20", "0.02", "1.25", "80", "2026-09-13");
        metrics.recordApiAttempt(125, true, "{\"usage\":{\"input_tokens\":1000,"
                + "\"input_tokens_details\":{\"cached_tokens\":200},\"output_tokens\":100,"
                + "\"output_tokens_details\":{\"reasoning_tokens\":40},\"total_tokens\":1100}}");
        metrics.incrementValidationRetries();
        metrics.setDocumentCounts(11, 3);
        metrics.setResultCounts(2, 25);
        metrics.finish();

        Map<String, Object> snapshot = metrics.snapshot();
        Map<?, ?> tokens = (Map<?, ?>) snapshot.get("tokens");
        assertEquals(1000L, tokens.get("input"));
        assertEquals(200L, tokens.get("cachedInput"));
        assertEquals(40L, tokens.get("reasoning"));
        assertEquals(new BigDecimal("0.0231"), snapshot.get("estimatedCostInr"));
        assertEquals("INR", snapshot.get("currency"));
    }

    @Test
    void missingExchangeConfigurationNeverFabricatesAnInrCost() {
        var metrics = new ComplianceConversionMetrics("0.20", "0.02", "1.25", "", "");
        metrics.recordUsage("{\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"total_tokens\":12}}");
        Map<String, Object> snapshot = metrics.snapshot();
        assertNull(snapshot.get("estimatedCostInr"));
        assertFalse(((List<?>) snapshot.get("warnings")).isEmpty());
    }

    @Test
    void successfulEmptyAndTruncatedResponsesAreDistinct() throws Exception {
        var ai = new AISpecificationIntelligenceService();
        Method parse = AISpecificationIntelligenceService.class.getDeclaredMethod(
                "parseLlmJsonResponse", String.class, Map.class);
        parse.setAccessible(true);
        List<String[]> complete = (List<String[]>) parse.invoke(ai,
                "{\"rows\":[],\"noApplicableRequirements\":true}", Map.of());
        assertTrue(AISpecificationIntelligenceService.isCompletedEmpty(complete));
        for (String response : List.of("[]", "{\"rows\":[],\"noApplicableRequirements\":false}",
                "{\"status\":\"incomplete\",\"output_text\":\"{\\\"rows\\\":[],\\\"noApplicableRequirements\\\":true}\"}")) {
            assertFalse(AISpecificationIntelligenceService.isCompletedEmpty(
                    (List<String[]>) parse.invoke(ai, response, Map.of())));
        }
    }

    @Test
    void progressTracksNewJobsAndProductDownloads() {
        var progress = new ComplianceProgressService();
        progress.start("1", "first.pdf");
        Object first = progress.snapshot("1").get("jobId");
        List<Map<String, Object>> products = List.of(Map.of("productName", "Pump", "scheduleNumber", "1",
                "clauseCount", 3, "pdfDownloadUrl", "/documents/1/1.pdf", "docxDownloadUrl", "/documents/1/1.docx"));
        progress.completeProducts("1", 3, products);
        assertEquals(products, progress.snapshot("1").get("products"));
        assertEquals("/documents/1/1.pdf", progress.snapshot("1").get("pdfDownloadUrl"));
        progress.start("1", "second.pdf");
        assertNotEquals(first, progress.snapshot("1").get("jobId"));
        assertEquals(List.of(), progress.snapshot("1").get("products"));
    }

    @Test
    void progressScriptIsInjectedOnceOnEveryEntryRouteOnly() throws Exception {
        for (String path : List.of("/", "/index.html", "/tenders/123")) {
            var request = new MockHttpServletRequest("GET", path);
            var response = new MockHttpServletResponse();
            new ComplianceProgressUiInjectionFilter().doFilter(request, response, (req, res) -> {
                res.setContentType("text/html");
                res.getWriter().write("<html><body>App</body></html>");
            });
            String html = response.getContentAsString();
            assertEquals(1, html.split("compliance-progress.js", -1).length - 1, path);
            assertTrue(html.contains("?v="));
        }
        var response = new MockHttpServletResponse();
        new ComplianceProgressUiInjectionFilter().doFilter(
                new MockHttpServletRequest("GET", "/api/tenders"), response, (req, res) -> {
                    res.setContentType("application/json"); res.getWriter().write("{\"ok\":true}");
                });
        assertEquals("{\"ok\":true}", response.getContentAsString());
    }

    @Test
    void bothFormatsRepeatHeadersAndRetainEveryRequirement() throws Exception {
        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i <= 75; i++) rows.add(row("3." + i,
                "Requirement " + i + ": Capacity 180-250 litres, temperature +2\u00b0C to +8\u00b0C, "
                        + "voltage 172 volts. Preserve all conditions and provide a valid test certificate.",
                "requirement", "PDF p. " + (i / 10 + 1)));
        var product = SpecificationSheetContent.from(rows).get(0);
        Map<String, String> company = Map.of("companyName", "MARK ENTERPRISES", "companyAddress",
                "Shed No. 1, Plot No. 93/2, MIDC Satpur, Nashik", "companyEmail", "info@markenworld.com",
                "companyWebsite", "www.markenworld.com", "companyContact", "09175559646");
        var generator = new DocumentGeneratorService();
        byte[] pdf = generator.generateProductSheetPdf(company, product);
        byte[] docx = generator.generateProductSheetDocx(company, product);
        Path output = Path.of("target", "specification-sheet-tests");
        Files.createDirectories(output);
        Files.write(output.resolve("layout.pdf"), pdf);
        Files.write(output.resolve("layout.docx"), docx);
        try (PDDocument document = PDDocument.load(pdf)) {
            assertTrue(document.getNumberOfPages() > 1);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String all = stripper.getText(document).replaceAll("\\s+", " ");
            for (int i = 1; i <= 75; i++) assertTrue(all.contains("Requirement " + i + ":"), "PDF requirement " + i);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text = stripper.getText(document);
                assertTrue(text.replaceAll("\\s+", "").contains("MARKENTERPRISES"), "Letterhead page " + page);
                assertTrue(text.replaceAll("\\s+", "").contains("Sr.No."), "Table header page " + page);
                assertTrue(text.contains("Requirement"), "No empty trailing page");
            }
        }
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            XWPFTable table = document.getTables().get(0);
            assertEquals(77, table.getRows().size());
            for (int i = 1; i < table.getRows().size(); i++) {
                for (int col = 2; col < 5; col++) assertEquals("", table.getRow(i).getCell(col).getText());
            }
            for (int i = 1; i <= 75; i++) assertTrue(table.getText().contains("Requirement " + i + ":"));
            assertTrue(table.getRow(0).isRepeatHeader());
        }
    }
}
