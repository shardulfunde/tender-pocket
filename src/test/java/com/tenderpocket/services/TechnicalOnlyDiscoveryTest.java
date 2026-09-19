package com.tenderpocket.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class TechnicalOnlyDiscoveryTest {
    private static final String ADMIN = "Contractors must submit documents and pay taxes. "
            + "This document contains only administrative conditions and no equipment requirements.";

    private byte[] pdf(boolean readable) throws Exception {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            if (readable) try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(PDType1Font.HELVETICA, 10);
                content.newLineAtOffset(20, 700); content.showText(ADMIN); content.endText();
            }
            document.save(out); return out.toByteArray();
        }
    }

    @Test void confirmedZeroProductsSkipsAllExtractionCalls() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var ai = new AISpecificationIntelligenceService() {
            @Override List<String> identifyProductsForConversion(String text) { return confirmedEmptyProducts(); }
            @Override List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                    Map<String, String> data, List<String> products) {
                calls.incrementAndGet(); return List.of();
            }
        };
        var generator = new DocumentGeneratorService();
        ReflectionTestUtils.setField(generator, "aiSpecificationIntelligenceService", ai);
        var result = generator.parseSpecificationClauses(pdf(true), "admin.pdf", Map.of());
        assertTrue(AISpecificationIntelligenceService.isCompletedEmpty(result));
        assertEquals(0, calls.get());
    }

    @Test void failedDiscoveryIsNotNoProductsAndDoesNotLaunchExtraction() throws Exception {
        var ai = new AISpecificationIntelligenceService() {
            @Override List<String> identifyProductsForConversion(String text) { return List.of(); }
            @Override List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                    Map<String, String> data, List<String> products) {
                fail("Failed discovery must not start extraction"); return List.of();
            }
        };
        var generator = new DocumentGeneratorService();
        ReflectionTestUtils.setField(generator, "aiSpecificationIntelligenceService", ai);
        var result = generator.parseSpecificationClauses(pdf(true), "admin.pdf", Map.of());
        assertTrue(result.isEmpty());
        assertFalse(AISpecificationIntelligenceService.isCompletedEmpty(result));
    }

    @Test void scansRequireNativeProductVerificationBeforeAnEmptyResult() throws Exception {
        var nativeCalls = new AtomicInteger();
        var ai = new AISpecificationIntelligenceService() {
            @Override List<String> identifyProductsForConversion(String text) { return confirmedEmptyProducts(); }
            @Override List<String> identifyProductsInPdfBatch(String text, byte[] bytes) {
                assertNotNull(bytes); nativeCalls.incrementAndGet(); return confirmedEmptyProducts();
            }
            @Override List<String[]> processOcrAndSynthesizeClauses(String text, byte[] bytes,
                    Map<String, String> data, List<String> products) {
                fail("No sheet extraction for verified empty scans"); return List.of();
            }
        };
        var generator = new DocumentGeneratorService();
        ReflectionTestUtils.setField(generator, "aiSpecificationIntelligenceService", ai);
        assertTrue(AISpecificationIntelligenceService.isCompletedEmpty(
                generator.parseSpecificationClauses(pdf(false), "scan.pdf", Map.of())));
        assertEquals(1, nativeCalls.get());
    }

    @Test void discoveryDistinguishesEmptyMalformedAndTruncatedResponses() {
        for (String response : List.of("{\"products\":[],\"readable\":true}",
                "{\"products\":[3],\"readable\":true}", "{\"products\":[],\"readable\":false}",
                "{\"status\":\"incomplete\",\"output_text\":\"{\\\"products\\\":[],\\\"readable\\\":true}\"}")) {
            var ai = new AISpecificationIntelligenceService() {
                @Override String postAzureResponse(String prompt, byte[] bytes, AzureOutput output, List<String> names) {
                    return response;
                }
            };
            assertEquals(response.equals("{\"products\":[],\"readable\":true}"),
                    AISpecificationIntelligenceService.isConfirmedEmptyProducts(ai.identifyProductsForConversion(ADMIN)));
        }
    }

    @Test void lowReasoningIsSetForDiscoveryAndExtraction() throws Exception {
        var ai = new AISpecificationIntelligenceService();
        var method = AISpecificationIntelligenceService.class.getDeclaredMethod("buildAzureResponsesPayload",
                String.class, byte[].class, AISpecificationIntelligenceService.AzureOutput.class, List.class);
        method.setAccessible(true);
        for (var output : AISpecificationIntelligenceService.AzureOutput.values()) {
            var payload = new ObjectMapper().readTree((String) method.invoke(ai, "Test", null, output, List.of()));
            assertEquals("low", payload.path("reasoning").path("effort").asText());
            assertEquals("gpt-5-nano", payload.path("model").asText());
        }
    }

    @Test void excludedWarrantyDoesNotTriggerMissingClauseRetry() {
        var calls = new AtomicInteger();
        var ai = new AISpecificationIntelligenceService() {
            @Override String postAzureResponse(String prompt, byte[] bytes, AzureOutput output, List<String> names) {
                calls.incrementAndGet();
                assertTrue(prompt.contains("Exclude warranty"));
                return """
                        {"noApplicableRequirements":false,"excludedClauseReferences":["3.11"],"rows":[
                        {"clauseReference":"3.10","requirement":"Capacity shall be 100 litres. Warranty shall be five years.",
                         "productCategory":"Pump","sourceReference":"PDF p. 1","rowType":"requirement"}]}
                        """;
            }
        };
        var rows = ai.processOcrAndSynthesizeClauses(
                "[SOURCE_PAGE pdf=\"1\"]\n3.10 Capacity shall be 100 litres.\n3.11 Warranty shall be five years.\n[/SOURCE_PAGE]",
                new byte[]{1}, Map.of(), List.of("Pump"));
        assertEquals(1, rows.size());
        assertEquals("3.10", rows.get(0)[0]);
        assertEquals("Capacity shall be 100 litres.", rows.get(0)[1]);
        assertEquals(1, calls.get());
    }

    @Test void administrativeOnlyPageReturnsExplicitEmptyWithoutRetry() {
        var calls = new AtomicInteger();
        var ai = new AISpecificationIntelligenceService() {
            @Override String postAzureResponse(String prompt, byte[] bytes, AzureOutput output, List<String> names) {
                calls.incrementAndGet();
                return "{\"rows\":[],\"noApplicableRequirements\":true,\"excludedClauseReferences\":[\"1.1\"]}";
            }
        };
        assertTrue(AISpecificationIntelligenceService.isCompletedEmpty(ai.processOcrAndSynthesizeClauses(
                "[SOURCE_PAGE pdf=\"1\"]\n1.1 Warranty shall be five years.\n[/SOURCE_PAGE]",
                new byte[]{1}, Map.of(), List.of("Pump"))));
        assertEquals(1, calls.get());
    }

    @Test void administrativeKeywordsInsideTechnicalParametersAreRetained() {
        String[] row = {"3.10", "Delivery pressure shall be 3 bar. Transport weight shall be 20 kg. "
                + "Training mode shall support offline operation. Warranty shall be five years.",
                "", "", "", "Pump", "-", "PDF p. 1", "requirement"};
        List<String[]> result = ReflectionTestUtils.invokeMethod(new AISpecificationIntelligenceService(),
                "technicalRequirementsOnly", Collections.singletonList(row));
        assertNotNull(result);
        assertEquals(1, result.size());
        assertTrue(result.get(0)[1].contains("Delivery pressure"));
        assertTrue(result.get(0)[1].contains("Transport weight"));
        assertTrue(result.get(0)[1].contains("Training mode"));
        assertFalse(result.get(0)[1].contains("Warranty"));
    }
}
