package com.tenderpocket.services;

import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: two small Azure checks, without database writes or uploaded-file replacement. */
class TechnicalOnlyLiveTest {
    private byte[] pdf(String... lines) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(); doc.addPage(page);
            try (var text = new PDPageContentStream(doc, page)) {
                text.beginText(); text.setFont(PDType1Font.HELVETICA, 10);
                text.newLineAtOffset(30, 740);
                for (String line : lines) {
                    text.showText(line); text.newLineAtOffset(0, -15);
                }
                text.endText();
            }
            doc.save(out); return out.toByteArray();
        }
    }

    @Test void lowReasoningHandlesEmptyAndMixedDocuments() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LIVE_LOW_REASONING")));
        var metrics = new ComplianceConversionMetrics("0.05", "0.005", "0.40", "", "");
        List<String> stages = new ArrayList<>();
        var generator = new DocumentGeneratorService();
        var empty = generator.parseSpecificationClauses(pdf(
                "Administrative bidding instructions only.",
                "Bidders shall submit registration certificates and pay applicable taxes.",
                "A performance guarantee is required. No equipment is specified."),
                "administrative.pdf", Map.of(),
                (stage, message, percent, done, total, clauses) -> {
                    stages.add(stage); System.out.println("[LiveLowReasoning] " + stage + ": " + message);
                }, metrics);
        assertTrue(AISpecificationIntelligenceService.isCompletedEmpty(empty));
        assertFalse(stages.contains("EXTRACTING"), "Confirmed empty discovery must stop before sheet extraction");

        String[] lines = {"Technical Specifications for Pump Alpha",
                "1.1 Capacity shall be 100 litres.",
                "1.2 Warranty shall be five years.",
                "1.3 Training shall be provided.",
                "1.4 Product shall conform to IEC 61010-1."};
        var ai = new AISpecificationIntelligenceService();
        ai.setConversionMetrics(metrics);
        try {
            var technical = ai.processOcrAndSynthesizeClauses(
                    "[SOURCE_PAGE pdf=\"1\"]\n" + String.join("\n", lines) + "\n[/SOURCE_PAGE]",
                    pdf(lines), Map.of(), List.of("Pump Alpha"));
            assertFalse(technical.isEmpty());
            String words = String.join(" ", technical.stream().map(row -> row[1]).toList());
            assertTrue(words.contains("100"));
            assertTrue(words.contains("IEC 61010-1"));
            assertFalse(words.toLowerCase(Locale.ROOT).contains("warranty"));
            assertFalse(words.toLowerCase(Locale.ROOT).contains("training"));
            assertTrue(technical.stream().allMatch(row -> row[2].isBlank() && row[3].isBlank() && row[4].isBlank()));
            System.out.println("[LiveLowReasoning] PASS empty discovery + technical-only mixed PDF; "
                    + "API attempts=" + metrics.snapshot().get("apiAttempts"));
        } finally {
            ai.clearConversionMetrics();
        }
    }
}
