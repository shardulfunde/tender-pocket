package com.tenderpocket.services;

import java.util.*;
import java.util.regex.*;

/** Conversion-only content shared by PDF, DOCX and the per-product download manifest. */
public final class SpecificationSheetContent {
    private SpecificationSheetContent() {}

    public record Row(String reference, String wording, boolean heading, String sources) {}
    public record Product(String name, String schedule, List<Row> rows, List<String> clarifications) {
        public int clauseCount() { return (int) rows.stream().filter(row -> !row.heading()).count(); }
        public String fileStem(int ordinal) {
            String safe = name.replaceAll("[^\\p{L}\\p{N}._-]+", "_").replaceAll("^_+|_+$", "");
            if (safe.isBlank()) safe = "Product";
            if (safe.length() > 90) safe = safe.substring(0, 90);
            String prefix = schedule.replaceAll("[^A-Za-z0-9.-]+", "_").replaceAll("^_+|_+$", "");
            if (prefix.isBlank()) prefix = String.valueOf(ordinal);
            if (prefix.length() > 24) prefix = prefix.substring(0, 24);
            return prefix + "_Technical_Data_Sheet_" + safe;
        }
    }

    public static String value(String[] row, int index) {
        return row.length > index && row[index] != null ? row[index].trim() : "";
    }

    public static List<Product> from(List<String[]> input) {
        LinkedHashMap<String, List<String[]>> groups = new LinkedHashMap<>();
        for (String[] raw : input) {
            String name = value(raw, 5);
            if (name.isBlank()) throw new IllegalArgumentException("Specification product is missing");
            groups.computeIfAbsent(name, ignored -> new ArrayList<>()).add(raw.clone());
        }
        List<Product> products = new ArrayList<>();
        int ordinal = 0;
        for (var entry : groups.entrySet()) {
            List<String[]> assembled = new ArrayList<>();
            LinkedHashSet<String> notes = new LinkedHashSet<>();
            String schedule = "";
            for (String[] row : entry.getValue()) {
                if (isFormFurniture(value(row, 1))) continue;
                if (!value(row, 11).isBlank()) {
                    if (!schedule.isBlank() && !schedule.equals(value(row, 11))) {
                        notes.add("Conflicting source schedule references: " + schedule + " / " + value(row, 11));
                    } else schedule = value(row, 11);
                }
                String remark = value(row, 6);
                if (!remark.isBlank() && !remark.equals("-")) {
                    notes.add("Clause " + value(row, 0) + ": " + remark + sourceSuffix(value(row, 7)));
                }
                if ("continuation".equals(value(row, 8))) {
                    String[] previous = null;
                    for (int i = assembled.size() - 1; i >= 0; i--) {
                        String[] candidate = assembled.get(i);
                        if (!"heading".equals(value(candidate, 8))
                                && !value(row, 0).isBlank() && value(row, 0).equals(value(candidate, 0))) {
                            previous = candidate;
                            break;
                        }
                    }
                    if (previous != null) {
                        previous[1] = joinContinuation(previous[1], row[1]);
                        previous[7] = sources(previous[7], value(row, 7));
                        continue;
                    }
                    notes.add("Clause " + value(row, 0)
                            + ": continuation has no identifiable preceding clause; wording retained."
                            + sourceSuffix(value(row, 7)));
                }
                assembled.add(row);
            }
            LinkedHashMap<String, Row> unique = new LinkedHashMap<>();
            Map<String, Row> byReference = new LinkedHashMap<>();
            Set<String> headings = new HashSet<>();
            for (String[] row : assembled) {
                String section = AISpecificationIntelligenceService.cleanClauseNumber(value(row, 9));
                String title = value(row, 10);
                if (!title.isBlank() && !isFormFurniture(title)
                        && !normalized(title).equals(normalized(entry.getKey()))
                        && !"heading".equals(value(row, 8))
                        && headings.add(section + "|" + normalized(title))) {
                    add(unique, new Row(section, title, true, value(row, 7)));
                }
                boolean heading = "heading".equals(value(row, 8));
                if (heading) headings.add(value(row, 0) + "|" + normalized(value(row, 1)));
                Row content = new Row(value(row, 0), value(row, 1), heading, value(row, 7));
                if (!heading && !content.reference().isBlank()) {
                    Row earlier = byReference.putIfAbsent(content.reference(), content);
                    if (earlier != null && !normalized(earlier.wording()).equals(normalized(content.wording()))) {
                        notes.add("Clause " + content.reference() + ": conflicting source versions retained: \""
                                + earlier.wording() + "\" " + sourceSuffix(earlier.sources()) + " / \""
                                + content.wording() + "\" " + sourceSuffix(content.sources()));
                    }
                }
                add(unique, content);
            }
            List<Row> displayedRows = new ArrayList<>();
            int displayPosition = 0;
            for (Row row : unique.values()) {
                displayPosition++;
                displayedRows.add(row.reference().isBlank()
                        ? new Row(String.valueOf(displayPosition), row.wording(), row.heading(), row.sources())
                        : row);
            }
            ordinal++;
            products.add(new Product(entry.getKey(), schedule.isBlank() ? String.valueOf(ordinal) : schedule,
                    List.copyOf(displayedRows), List.copyOf(notes)));
        }
        return List.copyOf(products);
    }

    private static void add(Map<String, Row> rows, Row row) {
        String key = row.heading() + "|" + row.reference() + "|" + normalized(row.wording());
        Row previous = rows.get(key);
        rows.put(key, previous == null ? row : new Row(previous.reference(), previous.wording(),
                previous.heading(), sources(previous.sources(), row.sources())));
    }

    private static String normalized(String text) {
        return text.toLowerCase(Locale.ROOT).replace('\u2013', '-').replace('\u2014', '-')
                .replaceAll("\\s+", " ").replaceAll("[.:;]+$", "").trim();
    }

    private static boolean isFormFurniture(String text) {
        return text.matches("(?is)^(?:we shall comply\\b|we understand\\b|note to bidders\\b|form \\d+:"
                + "|technical compliance clause\\b|technical specifications and quality assurance\\b"
                + "|compliance(?:\\s*\\(yes/no\\))?$|section [ivx]+$).*");
    }
    private static String joinContinuation(String left, String right) {
        if (left.endsWith(right)) return left;
        int overlap = Math.min(left.length(), right.length());
        for (int i = overlap; i >= 15; i--) {
            if (left.regionMatches(left.length() - i, right, 0, i)) return left + right.substring(i);
        }
        return left + " " + right;
    }

    private static String sourceSuffix(String source) { return source.isBlank() ? "" : " [" + source + "]"; }
    private static String sources(String left, String right) {
        Set<String> refs = new LinkedHashSet<>();
        for (String value : List.of(left, right)) {
            for (String ref : value.split(";\\s*")) if (!ref.isBlank()) refs.add(ref);
        }
        return String.join("; ", refs);
    }

    // Match source text spans only; emphasis never modifies the requirement itself.
    public static final Pattern EMPHASIS = Pattern.compile(
            "(?i)(?:WHO(?:/PQS)?[/\\w.()-]+|\\b(?:IS|IEC|ISO|BIS)\\s*\\d+[\\w:./-]*"
            + "|[+\\-\\u00b1]?\\d+(?:[.,]\\d+)*(?:\\s*[-\\u2013]\\s*\\d+(?:\\.\\d+)?)?"
            + "(?:\\s*(?:\\u00b0C|deg(?:ree)?s?\\.?\\s*C|lit(?:er|re)s?|Ltr|CuM|mm|cms?|hrs?\\.?|hours?|volts?|VAC|Hz|%|kg|dBA))?)");
}
