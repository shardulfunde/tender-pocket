package com.tenderpocket.services;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComplianceSheetConversionTest {

    private final DocumentGeneratorService generator = new DocumentGeneratorService();

    @Test
    void successfulNativePdfBatchDoesNotInvokeOcr() throws Exception {
        java.util.concurrent.atomic.AtomicInteger ocrCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger nativeCalls = new java.util.concurrent.atomic.AtomicInteger();
        DocumentGeneratorService directGenerator = new DocumentGeneratorService() {
            @Override
            String extractOcrFallbackText(byte[] pdfBytes, int physicalPageOffset) {
                ocrCalls.incrementAndGet();
                return "[SOURCE_PAGE pdf=\"1\"]\nRecovered OCR text\n[/SOURCE_PAGE]";
            }
        };
        AISpecificationIntelligenceService stub = new AISpecificationIntelligenceService() {
            @Override
            List<String> identifyProductsForConversion(String text) { return List.of("Test Product"); }

            @Override
            List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                    Map<String, String> data, List<String> products) {
                assertNotNull(bytes, "the first extraction call must carry the PDF batch");
                nativeCalls.incrementAndGet();
                return java.util.Collections.singletonList(row("1", "Native PDF requirement",
                        "Test Product", "PDF p. 1"));
            }
        };
        setAi(directGenerator, stub);

        List<String[]> rows = directGenerator.parseSpecificationClauses(blankPdf(1), "native.pdf", baseData());

        assertEquals(1, rows.size());
        assertEquals(1, nativeCalls.get());
        assertEquals(0, ocrCalls.get());
    }

    @Test
    void failedNativePdfBatchInvokesOcrThenRetriesAsText() throws Exception {
        java.util.concurrent.atomic.AtomicInteger ocrCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger nativeCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger textCalls = new java.util.concurrent.atomic.AtomicInteger();
        DocumentGeneratorService fallbackGenerator = new DocumentGeneratorService() {
            @Override
            String extractOcrFallbackText(byte[] pdfBytes, int physicalPageOffset) {
                ocrCalls.incrementAndGet();
                return "[SOURCE_PAGE pdf=\"1\"]\n1.1 Capacity shall be 100 litres and include "
                        + "a certified temperature controller.\n[/SOURCE_PAGE]";
            }
        };
        AISpecificationIntelligenceService stub = new AISpecificationIntelligenceService() {
            @Override
            List<String> identifyProductsForConversion(String text) { return List.of("Test Product"); }

            @Override
            List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                    Map<String, String> data, List<String> products) {
                if (bytes != null) {
                    nativeCalls.incrementAndGet();
                    return List.of();
                }
                textCalls.incrementAndGet();
                return java.util.Collections.singletonList(row("1.1", "Capacity shall be 100 litres.",
                        "Test Product", "PDF p. 1"));
            }
        };
        setAi(fallbackGenerator, stub);

        List<String[]> rows = fallbackGenerator.parseSpecificationClauses(blankPdf(1), "fallback.pdf", baseData());

        assertEquals(1, rows.size());
        assertEquals(1, nativeCalls.get());
        assertEquals(1, ocrCalls.get());
        assertEquals(1, textCalls.get());
    }

    @Test
    void unclearNativeReadingGetsOneOcrAssistedRetry() throws Exception {
        java.util.concurrent.atomic.AtomicInteger ocrCalls = new java.util.concurrent.atomic.AtomicInteger();
        DocumentGeneratorService fallbackGenerator = new DocumentGeneratorService() {
            @Override
            String extractOcrFallbackText(byte[] pdfBytes, int physicalPageOffset) {
                ocrCalls.incrementAndGet();
                return "[SOURCE_PAGE pdf=\"1\"]\n2.4 Holdover time shall be 24 hours at 43 C.\n[/SOURCE_PAGE]";
            }
        };
        AISpecificationIntelligenceService stub = new AISpecificationIntelligenceService() {
            @Override
            List<String> identifyProductsForConversion(String text) { return List.of("Test Product"); }

            @Override
            List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                    Map<String, String> data, List<String> products) {
                String[] result = row("2.4", bytes == null
                        ? "Holdover time shall be 24 hours at 43 C."
                        : "Holdover time shall be 24 hours at unclear temperature.",
                        "Test Product", "PDF p. 1");
                result[6] = bytes == null ? "-" : "Unclear / Requires Clarification.";
                return java.util.Collections.singletonList(result);
            }
        };
        setAi(fallbackGenerator, stub);

        List<String[]> rows = fallbackGenerator.parseSpecificationClauses(blankPdf(1), "unclear.pdf", baseData());

        assertEquals(1, rows.size());
        assertEquals("Holdover time shall be 24 hours at 43 C.", rows.get(0)[1]);
        assertEquals("-", rows.get(0)[6]);
        assertEquals(1, ocrCalls.get());
    }

    @Test
    void successfullyReadCoverBatchDoesNotTriggerFailureOrRetries() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AISpecificationIntelligenceService stub = new AISpecificationIntelligenceService() {
            @Override
            List<String> identifyProductsForConversion(String text) { return List.of("Test Product"); }

            @Override
            List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                    Map<String, String> data, List<String> products) {
                calls.incrementAndGet();
                if (text.contains("pdf=\"1\"")) return completedEmptyRows();
                return java.util.Collections.singletonList(row("1", "Actual specification",
                        "Test Product", "PDF p. 5"));
            }
        };
        java.lang.reflect.Field ai = DocumentGeneratorService.class.getDeclaredField("aiSpecificationIntelligenceService");
        ai.setAccessible(true); ai.set(generator, stub);
        List<String[]> rows = generator.parseSpecificationClauses(testPdf(8), "cover.pdf", baseData());
        assertEquals(1, rows.size());
        assertEquals(2, calls.get());
    }

    @Test
    void exactAndExplicitSharedProductNamesDoNotTriggerFallback() throws Exception {
        AISpecificationIntelligenceService service = new AISpecificationIntelligenceService();
        Method normalize = AISpecificationIntelligenceService.class.getDeclaredMethod(
                "normalizeKnownProductNames", List.class, List.class);
        normalize.setAccessible(true);
        String controller = "4- Reader Access controller, model IBAC, No. of reader inputs- 4 nos";
        String reader = "Mifare Card Reader, Compatible For 4- Reader Access Controller, Model IBAC";
        List<String> products = List.of(controller, reader);
        List<String[]> rows = new ArrayList<>();
        rows.add(row("1", "Controller requirement", controller, "PDF p. 1"));
        rows.add(row("2", "Shared warranty requirement", controller + " | " + reader + ".", "PDF p. 2"));
        assertTrue((boolean) normalize.invoke(service, rows, products));
        assertEquals(3, rows.size());
        assertEquals(controller, rows.get(1)[5]);
        assertEquals(reader, rows.get(2)[5]);
        assertEquals(rows.get(1)[1], rows.get(2)[1]);
        assertEquals(rows.get(1)[7], rows.get(2)[7]);
        rows.add(row("3", "Unknown", controller + " | Unknown Product", "PDF p. 3"));
        assertFalse((boolean) normalize.invoke(service, rows, products));

        List<String[]> overlapping = new ArrayList<>();
        overlapping.add(row("1", "Exact wins", "Access Controller", "PDF p. 1"));
        assertTrue((boolean) normalize.invoke(service, overlapping,
                List.of("Access Controller", "Reader Compatible With Access Controller")));
        assertEquals("Access Controller", overlapping.get(0)[5]);
    }

    @Test
    void annexureHeadingUpdatesTheActiveProductContext() {
        AISpecificationIntelligenceService service = new AISpecificationIntelligenceService();
        assertEquals("Diesel Generating Set", service.productForSourceHeading(
                "ANNEXURE-1: Diesel Generating Set",
                List.of("Voltage Stabilizer", "Diesel Generating Set")));
    }

    @Test
    void pdfExtractionRunsFiveConcurrentBatchesAndMergesInPageOrder() throws Exception {
        java.util.concurrent.CountDownLatch firstFive = new java.util.concurrent.CountDownLatch(5);
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maximum = new java.util.concurrent.atomic.AtomicInteger();
        AISpecificationIntelligenceService stub = new AISpecificationIntelligenceService() {
            @Override
            List<String> identifyProductsForConversion(String text) { return List.of("Test Product"); }

            @Override
            List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                    Map<String, String> data, List<String> products) {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                firstFive.countDown();
                try {
                    assertTrue(firstFive.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    java.util.regex.Matcher marker = java.util.regex.Pattern.compile("pdf=\"(\\d+)\"").matcher(text);
                    assertTrue(marker.find());
                    int page = Integer.parseInt(marker.group(1));
                    assertNull(data.put("workerOnly", "page" + page));
                    Thread.sleep(page == 1 ? 150 : 10);
                    return java.util.Collections.singletonList(row(String.valueOf(page),
                            "Requirement " + page, "Test Product", "PDF p. " + page));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return List.of();
                } finally {
                    active.decrementAndGet();
                }
            }
        };
        java.lang.reflect.Field ai = DocumentGeneratorService.class.getDeclaredField("aiSpecificationIntelligenceService");
        ai.setAccessible(true);
        ai.set(generator, stub);
        Map<String, String> data = baseData();
        List<String[]> rows = generator.parseSpecificationClauses(testPdf(24), "parallel.pdf", data);
        assertEquals(5, maximum.get());
        assertEquals(6, rows.size());
        for (int i = 0; i < 6; i++) assertEquals("Requirement " + (i * 4 + 1), rows.get(i)[1]);
        assertFalse(data.containsKey("workerOnly"));
    }

    @Test
    void parallelFailedBatchNeverReturnsPartialSheet() throws Exception {
        AISpecificationIntelligenceService stub = new AISpecificationIntelligenceService() {
            @Override
            List<String> identifyProductsForConversion(String text) { return List.of("Test Product"); }

            @Override
            List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                    Map<String, String> data, List<String> products) {
                if (text.contains("pdf=\"5\"")) return List.of();
                return java.util.Collections.singletonList(row("1", "Valid requirement",
                        "Test Product", "PDF p. 1"));
            }
        };
        java.lang.reflect.Field ai = DocumentGeneratorService.class.getDeclaredField("aiSpecificationIntelligenceService");
        ai.setAccessible(true);
        ai.set(generator, stub);
        assertTrue(generator.parseSpecificationClauses(testPdf(8), "failed.pdf", baseData()).isEmpty());
    }

    private byte[] testPdf(int pageCount) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= pageCount; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("Technical requirement on page " + i + " for Test Product, including supported warranty terms.");
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] blankPdf(int pageCount) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pageCount; i++) document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    private void setAi(DocumentGeneratorService target, AISpecificationIntelligenceService service)
            throws Exception {
        java.lang.reflect.Field ai = DocumentGeneratorService.class
                .getDeclaredField("aiSpecificationIntelligenceService");
        ai.setAccessible(true);
        ai.set(target, service);
    }

    @Test
    void complianceProgressRetainsAnEventTimelineAndDownloadLinks() {
        ComplianceProgressService progress = new ComplianceProgressService();
        progress.start("T-1", "sample.pdf");
        progress.update("T-1", "EXTRACTING", "Sending batch 1/2 to Gemini.", 25, 0, 2, 0);
        progress.update("T-1", "VALIDATING", "Batch 1 validated.", 50, 1, 2, 12);
        progress.complete("T-1", 24, "/documents/T-1/result.pdf", "/documents/T-1/result.docx");

        Map<String, Object> snapshot = progress.snapshot("T-1");
        assertEquals("COMPLETED", snapshot.get("status"));
        assertEquals(100, snapshot.get("percent"));
        assertEquals(24, snapshot.get("clauses"));
        assertEquals(4, ((List<?>) snapshot.get("events")).size());
        assertEquals("/documents/T-1/result.pdf", snapshot.get("pdfDownloadUrl"));
    }

    @Test
    void preservesExplicitClauseReferencesWithoutTenderSpecificLimits() {
        assertEquals("42.3", AISpecificationIntelligenceService.cleanClauseNumber("Clause 42.3"));
        assertEquals("A-7.2", AISpecificationIntelligenceService.cleanClauseNumber("A-7.2"));
    }

    @Test
    void htmlUsesReviewLanguageAndPreservesConflictingRequirements() {
        List<String[]> rows = new ArrayList<>();
        rows.add(row("3.1", "Capacity shall be 100 litres.", "Pump Alpha", "PDF p. 2 (Printed p. 84)"));
        rows.add(row("3.1", "Capacity shall be 100 litres.", "Pump Alpha", "PDF p. 3 (Printed p. 85)"));
        rows.add(row("3.1", "Capacity shall be 120 litres.", "Pump Alpha", "PDF p. 4 (Printed p. 86)"));
        rows.add(row("1", "Operating range shall be -20 C to +50 C.", "Custom Device Beta", "PDF p. 5"));

        String html = generator.generateTechSpecHtml(baseData(), rows);

        assertTrue(html.contains("Sr. No."));
        assertTrue(html.contains("Compliance (Yes/No)"));
        assertFalse(html.contains("To Be Assessed During Bid Evaluation"));
        assertFalse(html.contains("Not provided"));
        assertTrue(html.contains("Source Clarifications"));
        assertTrue(html.contains("Capacity shall be <strong>100 litres</strong>."));
        assertTrue(html.contains("Capacity shall be <strong>120 litres</strong>."));
        assertTrue(html.contains("Pump Alpha"));
        assertTrue(html.contains("Custom Device Beta"));
        assertFalse(html.contains("comply fully"));
        assertTrue(html.contains("Model No.: __________"));
    }

    @Test
    void docxContainsTheSameReviewFields() throws Exception {
        List<String[]> rows = java.util.Collections.singletonList(row("8.2",
                "A valid test certificate is required.", "Arbitrary Product X",
                "PDF p. 9 (Printed p. 91)"));

        byte[] bytes = generator.generateTechSpecDocx(baseData(), rows);
        StringBuilder text = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            document.getTables().forEach(table -> table.getRows().forEach(tableRow ->
                    tableRow.getTableCells().forEach(cell -> text.append(cell.getText()).append('\n'))));
            assertEquals(5, document.getTables().get(0).getRow(0).getTableCells().size());
            for (int col = 2; col < 5; col++) assertEquals("",
                    document.getTables().get(0).getRow(1).getCell(col).getText());
            assertFalse(document.getHeaderList().isEmpty());
            assertTrue(document.getTables().get(0).getRow(0).isRepeatHeader());
        }

        assertTrue(text.toString().contains("Sr. No."));
        assertTrue(text.toString().contains("A valid test certificate is required."));
        assertFalse(text.toString().contains("PDF p. 9 (Printed p. 91)"));
        assertFalse(text.toString().contains("To Be Assessed During Bid Evaluation"));
        assertFalse(text.toString().contains("comply fully"));

        byte[] pdf = generator.generateTechSpecPdf(baseData(), rows);
        try (PDDocument document = PDDocument.load(pdf)) {
            String pdfText = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
            assertFalse(pdfText.isBlank());
            assertFalse(pdfText.contains("comply fully"));
        }
    }

    @Test
    void pageContextKeepsPhysicalAndPrintedPageNumbers() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(50, 700);
                content.showText("Page 206 of 278");
                content.newLineAtOffset(0, -20);
                content.showText("Technical requirement text for the supplied equipment.");
                content.endText();
            }
            document.save(out);
            pdf = out.toByteArray();
        }

        Method method = DocumentGeneratorService.class.getDeclaredMethod(
                "extractPdfText", byte[].class, boolean.class, int.class);
        method.setAccessible(true);
        String context = (String) method.invoke(generator, pdf, false, 42);

        assertTrue(context.contains("[SOURCE_PAGE pdf=\"43\" printed=\"206\"]"));
        assertTrue(context.contains("Technical requirement text"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sourceValidationCanonicalizesPrintedPageAndRejectsUnknownPage() throws Exception {
        AISpecificationIntelligenceService service = new AISpecificationIntelligenceService();
        Method method = AISpecificationIntelligenceService.class.getDeclaredMethod(
                "validateEvidenceRows", List.class, String.class);
        method.setAccessible(true);
        String context = "[SOURCE_PAGE pdf=\"2\" printed=\"84\"]\ntext\n[/SOURCE_PAGE]";
        List<String[]> rows = new ArrayList<>();
        rows.add(row("1.1", "Supported requirement", "Product", "Page 84"));
        rows.add(row("1.2", "Printed page mislabeled as PDF page", "Product", "PDF p. 84"));
        rows.add(row("1.2", "Unsupported reference", "Product", "Page 999"));

        List<String[]> validated = (List<String[]>) method.invoke(service, rows, context);

        assertEquals(2, validated.size());
        assertEquals("PDF p. 2 (Printed p. 84)", validated.get(0)[7]);
        assertEquals("PDF p. 2 (Printed p. 84)", validated.get(1)[7]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sectionReferencesCountAsPreservedNumberedHeadings() throws Exception {
        AISpecificationIntelligenceService service = new AISpecificationIntelligenceService();
        Method method = AISpecificationIntelligenceService.class.getDeclaredMethod(
                "coversNumberedSourceClauses", List.class, String.class);
        method.setAccessible(true);
        String context = "12.0 Warranty\n12.1 Provide a five year warranty.";
        String[] requirement = java.util.Arrays.copyOf(
                row("12.1", "Provide a five year warranty.", "Product", "PDF p. 1"), 12);
        requirement[9] = "12.0";
        requirement[10] = "Warranty";

        assertTrue((boolean) method.invoke(service,
                java.util.Collections.singletonList(requirement), context));
    }

    @Test
    void nativePdfBatchesCoverEveryPageInOrder() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= 17; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("Page " + (82 + i) + " of 278");
                    content.endText();
                }
            }
            document.save(out);
            pdf = out.toByteArray();
        }

        Method method = DocumentGeneratorService.class.getDeclaredMethod("createPdfBatches", byte[].class, int.class);
        method.setAccessible(true);
        List<?> batches = (List<?>) method.invoke(generator, pdf, 1);

        assertEquals(5, batches.size());
        int covered = 0;
        int[] expectedStarts = {1, 5, 9, 13, 17};
        for (int i = 0; i < batches.size(); i++) {
            Object batch = batches.get(i);
            java.lang.reflect.Field first = batch.getClass().getDeclaredField("firstPhysicalPage");
            java.lang.reflect.Field count = batch.getClass().getDeclaredField("pageCount");
            java.lang.reflect.Field context = batch.getClass().getDeclaredField("sourceContext");
            first.setAccessible(true);
            count.setAccessible(true);
            context.setAccessible(true);
            assertEquals(expectedStarts[i], first.getInt(batch));
            covered += count.getInt(batch);
            assertTrue(((String) context.get(batch)).contains("pdf=\"" + expectedStarts[i] + "\""));
        }
        assertEquals(17, covered);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unresolvedPropertyPlaceholderIsNotUsedAsAnApiKey() throws Exception {
        AISpecificationIntelligenceService service = new AISpecificationIntelligenceService();
        Method method = AISpecificationIntelligenceService.class.getDeclaredMethod("getAllApiKeys");
        method.setAccessible(true);

        List<String> keys = (List<String>) method.invoke(service);

        assertTrue(keys.stream().noneMatch(key -> key.startsWith("${")));
        Method effective = AISpecificationIntelligenceService.class.getDeclaredMethod("getEffectiveApiKey");
        effective.setAccessible(true);
        String environmentKey = System.getenv("GEMINI_API_KEY");
        if (environmentKey == null || environmentKey.isBlank()) {
            assertNull(effective.invoke(service));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void azureResponsesPayloadPinsNanoWithStrictSchemaAndNativePdf() throws Exception {
        AISpecificationIntelligenceService service = new AISpecificationIntelligenceService();
        java.lang.reflect.Field deployment = AISpecificationIntelligenceService.class
                .getDeclaredField("azureOpenAiDeployment");
        deployment.setAccessible(true);
        deployment.set(service, "different-model");

        Class<?> outputType = java.util.Arrays.stream(AISpecificationIntelligenceService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("AzureOutput"))
                .findFirst().orElseThrow();
        Object complianceRows = Enum.valueOf((Class<? extends Enum>) outputType, "COMPLIANCE_ROWS");
        Method build = AISpecificationIntelligenceService.class.getDeclaredMethod(
                "buildAzureResponsesPayload", String.class, byte[].class, outputType);
        build.setAccessible(true);
        String payload = (String) build.invoke(service, "Extract rows", "%PDF-probe".getBytes(), complianceRows);

        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        assertEquals("gpt-5-nano", root.path("model").asText());
        assertFalse(root.path("store").asBoolean(true));
        assertEquals("json_schema", root.path("text").path("format").path("type").asText());
        assertEquals("object", root.path("text").path("format").path("schema").path("type").asText());
        assertTrue(root.path("text").path("format").path("schema")
                .path("properties").path("rows").path("items").path("properties")
                .has("sourceReference"));
        com.fasterxml.jackson.databind.JsonNode file = root.path("input").path(0).path("content").path(1);
        assertEquals("input_file", file.path("type").asText());
        assertTrue(file.path("file_data").asText().startsWith("data:application/pdf;base64,"));
        Method scopedBuild = AISpecificationIntelligenceService.class.getDeclaredMethod(
                "buildAzureResponsesPayload", String.class, byte[].class, outputType, List.class);
        scopedBuild.setAccessible(true);
        String scopedPayload = (String) scopedBuild.invoke(service, "Extract rows", null, complianceRows,
                List.of("Access Controller", "Compatible Reader"));
        com.fasterxml.jackson.databind.JsonNode allowed = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(scopedPayload).path("text").path("format").path("schema").path("properties")
                .path("rows").path("items").path("properties").path("productCategory").path("enum");
        assertEquals(2, allowed.size());
        assertEquals("Access Controller", allowed.get(0).asText());
        assertEquals("Compatible Reader", allowed.get(1).asText());
    }

    @Test
    void invalidNanoResponseGetsOneConstrainedNanoRetry() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AISpecificationIntelligenceService service = new AISpecificationIntelligenceService() {
            @Override
            String postAzureResponse(String prompt, byte[] fileBytes, AzureOutput output,
                                     List<String> knownProducts) {
                int call = calls.incrementAndGet();
                assertEquals(AzureOutput.COMPLIANCE_ROWS, output);
                assertNotNull(fileBytes);
                if (call == 2) {
                    assertTrue(prompt.contains("VALIDATION RETRY"));
                    assertTrue(prompt.contains("PDF p. 1 (Printed p. 83)"));
                    assertTrue(prompt.contains("\"1.1\""));
                }
                String source = call == 1 ? "PDF p. 99" : "PDF p. 1 (Printed p. 83)";
                return "{\"noApplicableRequirements\":false,\"rows\":[{"
                        + "\"clauseReference\":\"1.1\","
                        + "\"requirement\":\"Capacity shall be 100 litres.\","
                        + "\"requiredEvidence\":\"\",\"reviewerRemarks\":\"-\","
                        + "\"productCategory\":\"Pump Alpha\","
                        + "\"sourceReference\":\"" + source + "\","
                        + "\"rowType\":\"requirement\",\"sectionReference\":\"1\","
                        + "\"sectionTitle\":\"Capacity\",\"scheduleReference\":\"\"}]}";
            }
        };
        String context = "[SOURCE_PRODUCT name=\"Pump Alpha\"]\n"
                + "[SOURCE_PAGE pdf=\"1\" printed=\"83\"]\n"
                + "1.1 Capacity shall be 100 litres.\n[/SOURCE_PAGE]";

        List<String[]> rows = service.processOcrAndSynthesizeClauses(context,
                "%PDF-native".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                baseData(), List.of("Pump Alpha"));

        assertEquals(2, calls.get());
        assertEquals(1, rows.size());
        assertEquals("PDF p. 1 (Printed p. 83)", rows.get(0)[7]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void azureResponsesEnvelopeMapsToComplianceRows() throws Exception {
        AISpecificationIntelligenceService service = new AISpecificationIntelligenceService();
        String rowJson = "{\"rows\":[{\"clauseReference\":\"7.2\","
                + "\"requirement\":\"Capacity shall be 100 litres.\","
                + "\"requiredEvidence\":\"test certificate\",\"reviewerRemarks\":\"-\","
                + "\"productCategory\":\"Pump Alpha\",\"sourceReference\":\"PDF p. 2\"}]}";
        com.fasterxml.jackson.databind.node.ObjectNode envelope = new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode();
        envelope.putArray("output").addObject().putArray("content").addObject()
                .put("type", "output_text").put("text", rowJson);

        Method parse = AISpecificationIntelligenceService.class.getDeclaredMethod(
                "parseLlmJsonResponse", String.class, Map.class);
        parse.setAccessible(true);
        List<String[]> rows = (List<String[]>) parse.invoke(service, envelope.toString(), baseData());

        assertEquals(1, rows.size());
        assertEquals("7.2", rows.get(0)[0]);
        assertEquals("Capacity shall be 100 litres.", rows.get(0)[1]);
        assertEquals("test certificate", rows.get(0)[2]);
        assertEquals("", rows.get(0)[3]);
        assertEquals("Pump Alpha", rows.get(0)[5]);
        assertEquals("PDF p. 2", rows.get(0)[7]);
    }

    @Test
    void productCategoriesMustResolveToOneDetectedTenderProduct() throws Exception {
        AISpecificationIntelligenceService service = new AISpecificationIntelligenceService();
        Method method = AISpecificationIntelligenceService.class.getDeclaredMethod(
                "normalizeKnownProductNames", List.class, List.class);
        method.setAccessible(true);
        List<String[]> rows = new ArrayList<>();
        rows.add(row("1", "Requirement", "Pump Alpha", "PDF p. 1"));

        boolean accepted = (boolean) method.invoke(service, rows,
                List.of("Pump Alpha (Large)", "Pump Beta"));

        assertTrue(accepted);
        assertEquals("Pump Alpha (Large)", rows.get(0)[5]);
        rows.get(0)[5] = "Thermostat";
        assertFalse((boolean) method.invoke(service, rows,
                List.of("Pump Alpha (Large)", "Pump Beta")));
    }

    @Test
    void failedPdfBatchKeepsSplittingUntilSinglePages() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= 8; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(PDType1Font.HELVETICA, 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("Page " + i + " requirement " + i);
                    content.endText();
                }
            }
            document.save(out);
            pdf = out.toByteArray();
        }

        java.util.concurrent.atomic.AtomicInteger singlePageCalls = new java.util.concurrent.atomic.AtomicInteger();
        AISpecificationIntelligenceService splittingStub = new AISpecificationIntelligenceService() {
            @Override
            List<String> identifyProductsForConversion(String text) { return List.of("Dynamic Product"); }

            @Override
            public List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                                                                  Map<String, String> data) {
                return answerForSplitPage(bytes);
            }

            @Override
            List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                                                          Map<String, String> data,
                                                          List<String> knownProducts) {
                return answerForSplitPage(bytes);
            }

            private List<String[]> answerForSplitPage(byte[] bytes) {
                if (bytes == null) return java.util.Collections.emptyList();
                try (PDDocument supplied = PDDocument.load(bytes)) {
                    if (supplied.getNumberOfPages() != 1) return java.util.Collections.emptyList();
                } catch (Exception e) {
                    return java.util.Collections.emptyList();
                }
                int page = singlePageCalls.incrementAndGet();
                return java.util.Collections.singletonList(row(String.valueOf(page),
                        "Requirement on split page " + page, "Dynamic Product", "PDF p. " + page));
            }
        };
        java.lang.reflect.Field aiField = DocumentGeneratorService.class
                .getDeclaredField("aiSpecificationIntelligenceService");
        aiField.setAccessible(true);
        aiField.set(generator, splittingStub);

        List<String[]> rows = generator.parseSpecificationClauses(pdf, "split-retry.pdf", baseData());

        assertEquals(8, rows.size());
        assertEquals(8, singlePageCalls.get());
    }

    @Test
    void suppliedTenderRetainsItsNonContiguousPrintedPageLabels() throws Exception {
        String configuredPath = System.getenv().getOrDefault("TENDER_SAMPLE_PDF",
                System.getProperty("tender.sample.pdf", ""));
        Assumptions.assumeTrue(!configuredPath.isBlank() && Files.isRegularFile(Path.of(configuredPath)));

        Method method = DocumentGeneratorService.class.getDeclaredMethod("createPdfBatches", byte[].class, int.class);
        method.setAccessible(true);
        List<?> batches = (List<?>) method.invoke(generator, Files.readAllBytes(Path.of(configuredPath)), 1);

        int covered = 0;
        StringBuilder contexts = new StringBuilder();
        for (Object batch : batches) {
            java.lang.reflect.Field count = batch.getClass().getDeclaredField("pageCount");
            java.lang.reflect.Field context = batch.getClass().getDeclaredField("sourceContext");
            count.setAccessible(true);
            context.setAccessible(true);
            covered += count.getInt(batch);
            contexts.append(context.get(batch));
        }

        assertEquals(78, covered);
        Files.createDirectories(Path.of("target", "live-compliance-regression"));
        Files.writeString(Path.of("target", "live-compliance-regression", "source-context.txt"),
                contexts.toString());
        assertTrue(contexts.toString().contains("pdf=\"1\" printed=\"83\""));
        assertTrue(contexts.toString().contains("pdf=\"43\" printed=\"206\""));
        assertTrue(contexts.toString().contains("pdf=\"78\" printed=\"241\""));

        List<String> products = new AISpecificationIntelligenceService()
                .identifyProductsForConversion(contexts.toString());
        assertEquals(12, products.size());
        assertTrue(products.contains("Alcohol Stem Thermometer"));
        assertTrue(products.stream().noneMatch(name -> name.equalsIgnoreCase("Documentation")
                || name.equalsIgnoreCase("Thermostat") || name.equalsIgnoreCase("Warranty")));
        System.out.println("[ComplianceSheetConversionTest] Tender product headings: "
                + String.join(", ", products));
    }

    @Test
    void liveSuppliedTenderProducesReviewPdfAndDocxWhenEnabled() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LIVE_GEMINI")));
        String configuredPath = System.getenv("TENDER_SAMPLE_PDF");
        Assumptions.assumeTrue(configuredPath != null && Files.isRegularFile(Path.of(configuredPath)));

        Map<String, String> data = baseData();
        data.put("productDescription", "Tender Technical Specifications");
        data.put("companyName", "MARK ENTERPRISES");
        data.put("companyAddress", "Shed No. 1, Plot No. 93/2, Street No. 17, MIDC Satpur, Nashik - 422007, Maharashtra, India");
        data.put("companyEmail", "info@markenworld.com");
        data.put("companyWebsite", "www.markenworld.com");
        data.put("companyContact", "09175559646 / 090111 04332");
        ComplianceConversionMetrics metrics = new ComplianceConversionMetrics(
                System.getenv().getOrDefault("TECHSPEC_GPT_INPUT_USD_PER_MILLION", "0.05"),
                System.getenv().getOrDefault("TECHSPEC_GPT_CACHED_INPUT_USD_PER_MILLION", "0.005"),
                System.getenv().getOrDefault("TECHSPEC_GPT_OUTPUT_USD_PER_MILLION", "0.40"),
                System.getenv().getOrDefault("TECHSPEC_USD_TO_INR_RATE", ""),
                System.getenv().getOrDefault("TECHSPEC_USD_TO_INR_RATE_DATE", ""));
        List<String[]> rows = generator.parseSpecificationClauses(
                Files.readAllBytes(Path.of(configuredPath)), Path.of(configuredPath).getFileName().toString(), data,
                (stage, message, percent, completed, total, clauses) ->
                        System.out.println("[LiveBenchmark] " + stage + " " + message), metrics);

        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(row -> row.length >= 8 && !row[7].isBlank()));
        assertTrue(rows.stream().allMatch(row -> row[3].isBlank() && row[4].isBlank()));
        Path output = Path.of(System.getProperty("compliance.test.output",
                "target/live-compliance-regression"));
        Files.createDirectories(output);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(output.resolve("rows.json").toFile(), rows);
        Files.writeString(output.resolve("rows.txt"), rows.stream()
                .map(row -> String.join("\t", row))
                .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
        List<SpecificationSheetContent.Product> products = SpecificationSheetContent.from(rows);
        assertTrue(products.stream().flatMap(product -> product.rows().stream())
                .allMatch(row -> !row.reference().isBlank()));
        metrics.setResultCounts(products.size(), products.stream()
                .mapToInt(SpecificationSheetContent.Product::clauseCount).sum());
        metrics.beginRendering();
        int ordinal = 0;
        for (var product : products) {
            String stem = product.fileStem(++ordinal);
            Files.write(output.resolve(stem + ".pdf"), generator.generateProductSheetPdf(data, product));
            Files.write(output.resolve(stem + ".docx"), generator.generateProductSheetDocx(data, product));
        }
        metrics.endRendering();
        metrics.finish();
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(output.resolve("metrics.json").toFile(), metrics.snapshot());
        Files.write(output.resolve("compliance-sheet.pdf"), generator.generateTechSpecPdf(data, rows));
        Files.write(output.resolve("compliance-sheet.docx"), generator.generateTechSpecDocx(data, rows));
    }

    private String[] row(String clause, String requirement, String product, String source) {
        return new String[]{clause, requirement,
                "Not provided \u2014 bidder response not available. Evidence required: test certificate",
                "To Be Assessed During Bid Evaluation", "To be assessed during bid evaluation.",
                product, "-", source};
    }

    private Map<String, String> baseData() {
        Map<String, String> data = new HashMap<>();
        data.put("bidNumber", "TEST-001");
        data.put("companyName", "Test Company");
        data.put("companyAddress", "Test Address");
        return data;
    }
}
