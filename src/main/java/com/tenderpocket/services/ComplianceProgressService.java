package com.tenderpocket.services;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory, per-tender progress for the synchronous compliance-sheet upload workflow. */
@Service
public class ComplianceProgressService {
    private static final int MAX_EVENTS = 100;
    private final ConcurrentHashMap<String, Progress> jobs = new ConcurrentHashMap<>();

    @Value("${techspec.pricing.input-usd-per-million:0.05}")
    private String inputUsdPerMillion = "0.05";
    @Value("${techspec.pricing.cached-input-usd-per-million:0.005}")
    private String cachedInputUsdPerMillion = "0.005";
    @Value("${techspec.pricing.output-usd-per-million:0.40}")
    private String outputUsdPerMillion = "0.40";
    @Value("${techspec.usd-to-inr-rate:}")
    private String usdToInrRate = "";
    @Value("${techspec.usd-to-inr-rate-date:}")
    private String usdToInrRateDate = "";

    public void start(String tenderId, String fileName) {
        Progress progress = newProgress();
        jobs.put(tenderId, progress);
        progress.update("UPLOADING", "Upload received: " + fileName, 2, 0, 0, 0, null, null);
    }

    public ComplianceConversionMetrics metricsFor(String tenderId) {
        return jobs.computeIfAbsent(tenderId, ignored -> newProgress()).metrics;
    }

    public void update(String tenderId, String stage, String message, int percent,
                       int completedBatches, int totalBatches, int clauses) {
        jobs.computeIfAbsent(tenderId, ignored -> newProgress())
                .update(stage, message, percent, completedBatches, totalBatches, clauses, null, null);
    }

    public void complete(String tenderId, int clauses, String pdfUrl, String docxUrl) {
        Progress progress = jobs.computeIfAbsent(tenderId, ignored -> newProgress());
        progress.metrics.setResultCounts(1, clauses);
        progress.metrics.finish();
        progress.update("COMPLETED", "Compliance PDF and DOCX are ready.", 100, 0, 0,
                clauses, pdfUrl, docxUrl);
    }

    public void completeProducts(String tenderId, int clauses, java.util.List<Map<String, Object>> products) {
        Progress progress = jobs.computeIfAbsent(tenderId, ignored -> newProgress());
        synchronized (progress) {
            progress.generated = true;
            progress.products = java.util.List.copyOf(products);
            progress.metrics.setResultCounts(products.size(), clauses);
            progress.metrics.finish();
            Map<String, Object> first = products.get(0);
            progress.update("COMPLETED", "Technical data sheets are ready for " + products.size() + " products.",
                    100, 0, 0, clauses, (String) first.get("pdfDownloadUrl"), (String) first.get("docxDownloadUrl"));
        }
    }

    public void completeNoProducts(String tenderId, String message) {
        Progress progress = jobs.computeIfAbsent(tenderId, ignored -> newProgress());
        synchronized (progress) {
            progress.generated = false;
            progress.products = java.util.List.of();
            progress.pdfUrl = null;
            progress.docxUrl = null;
            progress.clauses = 0;
            progress.metrics.setResultCounts(0, 0);
            progress.metrics.finish();
            progress.update("COMPLETED", message, 100, 0, 0, 0, null, null);
        }
    }

    public void fail(String tenderId, String message) {
        Progress progress = jobs.computeIfAbsent(tenderId, ignored -> newProgress());
        progress.metrics.finish();
        progress.update("FAILED", message, -1, 0, 0, 0, null, null);
    }

    public Map<String, Object> snapshot(String tenderId) {
        Progress progress = jobs.get(tenderId);
        if (progress == null) {
            return Map.of("status", "NOT_STARTED", "percent", 0, "events", java.util.List.of());
        }
        return progress.snapshot();
    }

    private Progress newProgress() {
        return new Progress(new ComplianceConversionMetrics(inputUsdPerMillion, cachedInputUsdPerMillion,
                outputUsdPerMillion, usdToInrRate, usdToInrRateDate));
    }

    private static final class Progress {
        private final ComplianceConversionMetrics metrics;
        private String status = "NOT_STARTED";
        private String message = "Waiting to start";
        private int percent;
        private int completedBatches;
        private int totalBatches;
        private int clauses;
        private String pdfUrl;
        private String docxUrl;
        private Boolean generated;
        private final String jobId = java.util.UUID.randomUUID().toString();
        private java.util.List<Map<String, Object>> products = java.util.List.of();
        private String updatedAt = Instant.now().toString();
        private final ArrayDeque<Map<String, Object>> events = new ArrayDeque<>();

        private Progress(ComplianceConversionMetrics metrics) {
            this.metrics = metrics;
        }

        synchronized void update(String status, String message, int percent,
                                 int completedBatches, int totalBatches, int clauses,
                                 String pdfUrl, String docxUrl) {
            this.status = status;
            this.message = message;
            if (percent >= 0) this.percent = Math.max(this.percent, Math.min(100, percent));
            if (totalBatches > 0) this.totalBatches = totalBatches;
            if (completedBatches > 0 || totalBatches > 0) this.completedBatches = completedBatches;
            if (clauses > 0) this.clauses = clauses;
            if (pdfUrl != null) this.pdfUrl = pdfUrl;
            if (docxUrl != null) this.docxUrl = docxUrl;
            this.updatedAt = Instant.now().toString();

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("time", updatedAt);
            event.put("stage", status);
            event.put("message", message);
            events.addLast(event);
            while (events.size() > MAX_EVENTS) events.removeFirst();
        }

        synchronized Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("jobId", jobId);
            result.put("generated", generated);
            result.put("products", products);
            result.put("message", message);
            result.put("percent", percent);
            result.put("completedBatches", completedBatches);
            result.put("totalBatches", totalBatches);
            result.put("clauses", clauses);
            result.put("updatedAt", updatedAt);
            result.put("events", new ArrayList<>(events));
            result.put("metrics", metrics.snapshot());
            if (pdfUrl != null) result.put("pdfDownloadUrl", pdfUrl);
            if (docxUrl != null) result.put("docxDownloadUrl", docxUrl);
            return result;
        }
    }
}
