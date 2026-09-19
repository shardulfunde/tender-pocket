package com.tenderpocket.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe measurements for one specification-to-data-sheet conversion. */
public final class ComplianceConversionMetrics {
    public static final String MODEL = "gpt-5-nano";
    public static final String PRICING_SOURCE =
            "https://developers.openai.com/api/docs/models/gpt-5-nano";
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000L);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BigDecimal inputUsdPerMillion;
    private final BigDecimal cachedInputUsdPerMillion;
    private final BigDecimal outputUsdPerMillion;
    private final BigDecimal usdToInrRate;
    private final String usdToInrRateDate;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    private final long startedNanos = System.nanoTime();
    private final Instant startedAt = Instant.now();
    private final AtomicLong finishedNanos = new AtomicLong();
    private final AtomicLong extractionStartedNanos = new AtomicLong();
    private final AtomicLong extractionFinishedNanos = new AtomicLong();
    private final AtomicLong renderingStartedNanos = new AtomicLong();
    private final AtomicLong renderingFinishedNanos = new AtomicLong();

    private final LongAdder apiAttempts = new LongAdder();
    private final LongAdder successfulResponses = new LongAdder();
    private final LongAdder apiLatencyMs = new LongAdder();
    private final LongAdder inputTokens = new LongAdder();
    private final LongAdder cachedInputTokens = new LongAdder();
    private final LongAdder outputTokens = new LongAdder();
    private final LongAdder reasoningTokens = new LongAdder();
    private final LongAdder totalTokens = new LongAdder();
    private final LongAdder validationRetries = new LongAdder();
    private final LongAdder ocrRetries = new LongAdder();
    private final LongAdder splitRetries = new LongAdder();
    private final LongAdder rateLimitRetries = new LongAdder();
    private final AtomicInteger pages = new AtomicInteger();
    private final AtomicInteger batches = new AtomicInteger();
    private final AtomicInteger products = new AtomicInteger();
    private final AtomicInteger clauses = new AtomicInteger();

    public ComplianceConversionMetrics(String inputPrice, String cachedInputPrice, String outputPrice,
                                       String exchangeRate, String exchangeRateDate) {
        inputUsdPerMillion = positiveOrDefault(inputPrice, "0.05", "input-token price");
        cachedInputUsdPerMillion = positiveOrDefault(cachedInputPrice, "0.005", "cached-input price");
        outputUsdPerMillion = positiveOrDefault(outputPrice, "0.40", "output-token price");
        usdToInrRate = positiveOrNull(exchangeRate);
        usdToInrRateDate = exchangeRateDate == null ? "" : exchangeRateDate.trim();
        if (usdToInrRate == null) {
            warnings.add("TECHSPEC_USD_TO_INR_RATE is missing or invalid; estimated INR cost is unavailable.");
        }
        if (usdToInrRateDate.isBlank()) {
            warnings.add("TECHSPEC_USD_TO_INR_RATE_DATE is missing; estimated INR cost is unavailable.");
        }
    }

    private BigDecimal positiveOrDefault(String raw, String fallback, String label) {
        BigDecimal parsed = positiveOrNull(raw);
        if (parsed != null) return parsed;
        if (raw != null && !raw.isBlank()) {
            warnings.add("Invalid " + label + " configuration; using the official GPT-5 nano reference price.");
        }
        return new BigDecimal(fallback);
    }

    private static BigDecimal positiveOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            BigDecimal value = new BigDecimal(raw.trim());
            return value.signum() > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public void beginExtraction() {
        extractionStartedNanos.compareAndSet(0, System.nanoTime());
    }

    public void endExtraction() {
        if (extractionStartedNanos.get() > 0) extractionFinishedNanos.compareAndSet(0, System.nanoTime());
    }

    public void beginRendering() {
        renderingStartedNanos.compareAndSet(0, System.nanoTime());
    }

    public void endRendering() {
        if (renderingStartedNanos.get() > 0) renderingFinishedNanos.compareAndSet(0, System.nanoTime());
    }

    public void finish() {
        endExtraction();
        endRendering();
        finishedNanos.compareAndSet(0, System.nanoTime());
    }

    public void setDocumentCounts(int pageCount, int batchCount) {
        pages.set(Math.max(0, pageCount));
        batches.set(Math.max(0, batchCount));
    }

    public void setResultCounts(int productCount, int clauseCount) {
        products.set(Math.max(0, productCount));
        clauses.set(Math.max(0, clauseCount));
    }

    public void recordApiAttempt(long latencyMs, boolean successful, String responseBody) {
        apiAttempts.increment();
        apiLatencyMs.add(Math.max(0, latencyMs));
        if (!successful) return;
        successfulResponses.increment();
        recordUsage(responseBody);
    }

    void recordUsage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return;
        try {
            JsonNode usage = JSON.readTree(responseBody).path("usage");
            if (!usage.isObject()) return;
            long input = nonNegative(usage.path("input_tokens").asLong());
            long cached = nonNegative(usage.path("input_tokens_details").path("cached_tokens").asLong());
            long output = nonNegative(usage.path("output_tokens").asLong());
            long reasoning = nonNegative(usage.path("output_tokens_details").path("reasoning_tokens").asLong());
            long total = nonNegative(usage.path("total_tokens").asLong(input + output));
            inputTokens.add(input);
            cachedInputTokens.add(Math.min(input, cached));
            outputTokens.add(output);
            reasoningTokens.add(Math.min(output, reasoning));
            totalTokens.add(total);
        } catch (Exception ignored) {
            warnings.add("A successful model response did not contain readable token usage metadata.");
        }
    }

    private static long nonNegative(long value) {
        return Math.max(0, value);
    }

    public void incrementValidationRetries() { validationRetries.increment(); }
    public void incrementOcrRetries() { ocrRetries.increment(); }
    public void incrementSplitRetries() { splitRetries.increment(); }
    public void incrementRateLimitRetries() { rateLimitRetries.increment(); }

    public Map<String, Object> snapshot() {
        long input = inputTokens.sum();
        long cached = Math.min(input, cachedInputTokens.sum());
        long nonCached = input - cached;
        long output = outputTokens.sum();

        Map<String, Object> tokenValues = new LinkedHashMap<>();
        tokenValues.put("input", input);
        tokenValues.put("cachedInput", cached);
        tokenValues.put("nonCachedInput", nonCached);
        tokenValues.put("output", output);
        tokenValues.put("reasoning", reasoningTokens.sum());
        tokenValues.put("total", totalTokens.sum());

        Map<String, Object> retryValues = new LinkedHashMap<>();
        retryValues.put("validation", validationRetries.sum());
        retryValues.put("ocr", ocrRetries.sum());
        retryValues.put("split", splitRetries.sum());
        retryValues.put("rateLimit", rateLimitRetries.sum());

        Map<String, Object> durationValues = new LinkedHashMap<>();
        durationValues.put("api", apiLatencyMs.sum());
        durationValues.put("extraction", phaseMillis(extractionStartedNanos, extractionFinishedNanos));
        durationValues.put("rendering", phaseMillis(renderingStartedNanos, renderingFinishedNanos));
        long end = finishedNanos.get();
        durationValues.put("total", nanosToMillis((end == 0 ? System.nanoTime() : end) - startedNanos));

        Map<String, Object> documentValues = new LinkedHashMap<>();
        documentValues.put("pages", pages.get());
        documentValues.put("batches", batches.get());
        documentValues.put("products", products.get());
        documentValues.put("clauses", clauses.get());

        Map<String, Object> pricingValues = new LinkedHashMap<>();
        pricingValues.put("source", "Official GPT-5 nano reference pricing");
        pricingValues.put("sourceUrl", PRICING_SOURCE);
        pricingValues.put("usdToInrRate", usdToInrRate);
        pricingValues.put("usdToInrRateDate", usdToInrRateDate.isBlank() ? null : usdToInrRateDate);
        pricingValues.put("inputInrPerMillion", convertedUnitPrice(inputUsdPerMillion));
        pricingValues.put("cachedInputInrPerMillion", convertedUnitPrice(cachedInputUsdPerMillion));
        pricingValues.put("outputInrPerMillion", convertedUnitPrice(outputUsdPerMillion));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", MODEL);
        result.put("startedAt", startedAt.toString());
        result.put("apiAttempts", apiAttempts.sum());
        result.put("successfulModelResponses", successfulResponses.sum());
        result.put("tokens", tokenValues);
        result.put("retries", retryValues);
        result.put("durationsMs", durationValues);
        result.put("document", documentValues);
        result.put("estimatedCostInr", estimatedCostInr(nonCached, cached, output));
        result.put("currency", "INR");
        result.put("costIsEstimate", true);
        result.put("pricing", pricingValues);
        result.put("warnings", new ArrayList<>(warnings));
        return result;
    }

    private long phaseMillis(AtomicLong started, AtomicLong ended) {
        long start = started.get();
        if (start == 0) return 0;
        long end = ended.get();
        return nanosToMillis((end == 0 ? System.nanoTime() : end) - start);
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0, nanos / 1_000_000L);
    }

    private BigDecimal estimatedCostInr(long nonCachedInput, long cachedInput, long output) {
        if (usdToInrRate == null || usdToInrRateDate.isBlank()) return null;
        BigDecimal usd = BigDecimal.valueOf(nonCachedInput).multiply(inputUsdPerMillion)
                .add(BigDecimal.valueOf(cachedInput).multiply(cachedInputUsdPerMillion))
                .add(BigDecimal.valueOf(output).multiply(outputUsdPerMillion))
                .divide(ONE_MILLION, 12, RoundingMode.HALF_UP);
        return usd.multiply(usdToInrRate).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal convertedUnitPrice(BigDecimal usdPrice) {
        return usdToInrRate == null ? null
                : usdPrice.multiply(usdToInrRate).setScale(4, RoundingMode.HALF_UP);
    }
}
