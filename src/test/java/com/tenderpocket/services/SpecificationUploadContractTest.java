package com.tenderpocket.services;

import com.tenderpocket.controllers.TenderController;
import com.tenderpocket.models.Tender;
import com.tenderpocket.repositories.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpecificationUploadContractTest {
    @org.junit.jupiter.api.io.TempDir
    Path testHome;

    @Test
    void noProductsReturnsSuccessfulMessageWithoutRenderingOrGeneratedMetadata() throws Exception {
        String id = "compliance-no-products-" + UUID.randomUUID();
        Path directory = Path.of("public", "documents", id);
        var tender = new Tender();
        tender.setId(id); tender.setTitle("Administrative tender"); tender.setStatus("New");
        tender.setDownloadedDocs("[{\"filename\":\"existing.pdf\",\"local_path\":\"/existing.pdf\"}]");
        var repository = mock(TenderRepository.class);
        when(repository.findById(id)).thenReturn(Optional.of(tender));
        var controller = new TenderController();
        var progress = new ComplianceProgressService();
        ReflectionTestUtils.setField(controller, "tenderRepository", repository);
        ReflectionTestUtils.setField(controller, "complianceProgressService", progress);
        ReflectionTestUtils.setField(controller, "documentGeneratorService", new DocumentGeneratorService() {
            @Override public List<String[]> parseSpecificationClauses(byte[] bytes, String fileName,
                    Map<String, String> data, ConversionProgressListener listener, ComplianceConversionMetrics metrics) {
                return AISpecificationIntelligenceService.completedEmptyRows();
            }
            @Override public byte[] generateProductSheetPdf(Map<String, String> data, SpecificationSheetContent.Product p) {
                throw new AssertionError("An empty result must not render a file");
            }
            @Override public byte[] generateProductSheetDocx(Map<String, String> data, SpecificationSheetContent.Product p) {
                throw new AssertionError("An empty result must not render a file");
            }
        });
        try {
            var response = controller.uploadTechSpec("Admin", "test", id,
                    new MockMultipartFile("file", "admin.pdf", "application/pdf", new byte[]{1}),
                    null, null, null, null, null);
            assertEquals(200, response.getStatusCode().value());
            Map<?, ?> body = (Map<?, ?>) response.getBody();
            assertEquals(true, body.get("success"));
            assertEquals(false, body.get("generated"));
            assertEquals(List.of(), body.get("products"));
            assertEquals(0, body.get("clauseCount"));
            assertNull(body.get("pdfDownloadUrl"));
            assertNull(body.get("docxDownloadUrl"));
            assertNotNull(body.get("metrics"));
            assertTrue(body.get("message").toString().contains("No products found"));
            assertEquals("COMPLETED", progress.snapshot(id).get("status"));
            assertEquals(false, progress.snapshot(id).get("generated"));
            assertTrue(tender.getDownloadedDocs().contains("/existing.pdf"));
            try (var files = Files.list(directory)) {
                assertEquals(List.of("specification.pdf"), files.map(p -> p.getFileName().toString()).toList());
            }
        } finally {
            Files.deleteIfExists(directory.resolve("specification.pdf"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void onlySpecificationFileIsRequiredAndEveryProductGetsRegistered() throws Exception {
        String id = "compliance-contract-test-" + UUID.randomUUID();
        Path output = Path.of("public", "documents", id);
        Tender tender = new Tender();
        tender.setId(id); tender.setTitle("Specification"); tender.setStatus("New");
        tender.setDownloadedDocs("[{\"filename\":\"unrelated.pdf\",\"local_path\":\"/existing/unrelated.pdf\"}]");
        TenderRepository repository = mock(TenderRepository.class);
        when(repository.findById(id)).thenReturn(Optional.of(tender));
        var controller = new TenderController();
        var progress = new ComplianceProgressService();
        ReflectionTestUtils.setField(controller, "tenderRepository", repository);
        ReflectionTestUtils.setField(controller, "activityLogRepository", mock(ActivityLogRepository.class));
        ReflectionTestUtils.setField(controller, "complianceProgressService", progress);
        ReflectionTestUtils.setField(controller, "documentGeneratorService", new DocumentGeneratorService() {
            @Override
            public List<String[]> parseSpecificationClauses(byte[] input, String name, Map<String, String> data,
                                                            ConversionProgressListener listener,
                                                            ComplianceConversionMetrics metrics) {
                metrics.setDocumentCounts(2, 1);
                return List.of(
                        new String[]{"3.10", "Capacity 40 litres", "", "", "", "Pump / Alpha", "-", "PDF p. 1"},
                        new String[]{"1.1", "Capacity 20 litres", "", "", "", "Pump Beta", "-", "PDF p. 2"});
            }
            @Override
            public byte[] generateProductSheetPdf(Map<String, String> data, SpecificationSheetContent.Product p) {
                return "test-pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            @Override
            public byte[] generateProductSheetDocx(Map<String, String> data, SpecificationSheetContent.Product p) {
                return "test-docx".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        });
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", testHome.toString());
            var response = controller.uploadTechSpec("Admin", "test", id,
                    new MockMultipartFile("file", "spec.pdf", "application/pdf", new byte[]{1}),
                    null, null, null, null, null);
            assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
            Map<?, ?> body = (Map<?, ?>) response.getBody();
            List<Map<String, Object>> products = (List<Map<String, Object>>) body.get("products");
            assertEquals(2, products.size());
            assertEquals(products.get(0).get("pdfDownloadUrl"), body.get("pdfDownloadUrl"));
            assertEquals(products.get(0).get("docxDownloadUrl"), body.get("docxDownloadUrl"));
            for (Map<String, Object> product : products) {
                for (String type : List.of("pdfDownloadUrl", "docxDownloadUrl")) {
                    String path = (String) product.get(type);
                    assertTrue(Files.isRegularFile(Path.of("public" + path)));
                    assertTrue(tender.getDownloadedDocs().contains(path));
                }
            }
            assertTrue(tender.getDownloadedDocs().contains("/existing/unrelated.pdf"));
            assertEquals(products, progress.snapshot(id).get("products"));
            assertNotNull(body.get("metrics"));
            assertEquals(2, ((Map<?, ?>) ((Map<?, ?>) body.get("metrics")).get("document")).get("products"));
            verify(repository).save(tender);
        } finally {
            System.setProperty("user.home", originalHome);
            // Only this test's freshly generated UUID directory is cleaned.
            if (Files.isDirectory(output)) {
                try (var files = Files.list(output)) {
                    for (Path file : files.toList()) Files.delete(file);
                }
                Files.delete(output);
            }
        }
    }
}
