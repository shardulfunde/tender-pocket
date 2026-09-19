package com.tenderpocket.services;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Matcher;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

/** Five-column blank bidder sheets; deliberately separate from bid-pack templates. */
final class SpecificationSheetRenderer {
    private static final String[] HEADERS = {"Sr. No.", "Specification", "Compliance (Yes/No)",
            "Deviations, if any", "Remarks"};
    private static final int[] WIDTHS = {10, 40, 13, 13, 24};
    private static final String TITLE = "Technical Compliance Clause by Clause";

    static String html(Map<String, String> data, List<SpecificationSheetContent.Product> products,
                       byte[] logo, byte[] partner) {
        StringBuilder out = new StringBuilder("""
            <!DOCTYPE html><html><head><meta charset="utf-8"/><style>
            @page { size:A4 portrait; margin:29mm 8mm 10mm;
              @top-center { content:element(letterhead); }
              @bottom-center { content:"Page " counter(page); font-family:Cambria; font-size:9pt; } }
            body { font-family:Cambria,serif; font-size:11pt; line-height:1.05; color:#000; }
            .letterhead { position:running(letterhead); width:100%; font-family:Calibri,sans-serif;
              border-bottom:0.6pt solid #333; padding-bottom:4pt; }
            .letterhead table { width:100%; border-collapse:collapse; }
            .letterhead td { border:0; vertical-align:top; padding:0 3pt; }
            .company { font-size:22pt; color:#4472c4; font-weight:bold; }
            .contact { font-size:9.5pt; line-height:1.1; }
            h1 { font-size:11pt; text-align:center; margin:4pt 0 12pt; }
            h2 { font-size:11pt; margin:8pt 0; page-break-after:avoid; }
            .sheet { width:100%; border-collapse:collapse; table-layout:fixed; -fs-table-paginate:paginate; }
            .sheet th,.sheet td { border:0.5pt solid #666; padding:2pt 4pt; vertical-align:top; word-wrap:break-word; }
            .sheet tr { page-break-inside:avoid; }
            .sheet tr.long { page-break-inside:auto; }
            .sheet th { text-align:center; }
            .sheet thead { display:table-header-group; }
            .sheet .heading { font-weight:bold; page-break-after:avoid; }
            .reference { text-align:center; font-weight:bold; }
            .product { text-align:center; font-weight:bold; border:0.5pt solid #666; padding:4pt; page-break-after:avoid; }
            .next { page-break-before:always; }
            .clarifications { font-size:10pt; }
            </style></head><body>
            """);
        out.append("<div class=\"letterhead\"><table><tr><td style=\"width:12%\">")
                .append(image(logo, 62)).append("</td><td><div class=\"company\">")
                .append(escape(data.getOrDefault("companyName", "").toUpperCase(Locale.ROOT)))
                .append("</div><div class=\"contact\">")
                .append(escape(data.getOrDefault("companyAddress", ""))).append("<br/>Email ID: ")
                .append(escape(data.getOrDefault("companyEmail", ""))).append(" URL: ")
                .append(escape(data.getOrDefault("companyWebsite", ""))).append("<br/>Contact No.: ")
                .append(escape(data.getOrDefault("companyContact", "")))
                .append("</div></td><td style=\"width:20%;text-align:right\">")
                .append(image(partner, 82)).append("</td></tr></table></div>");
        int ordinal = 0;
        for (var product : products) {
            out.append("<div").append(ordinal++ == 0 ? "" : " class=\"next\"").append("><h1>")
                    .append(TITLE).append("</h1><h2>Schedule No. ").append(escape(product.schedule()))
                    .append("</h2><div class=\"product\">").append(escape(product.name()))
                    .append("<br/>Make: __________<br/>Model No.: __________</div><table class=\"sheet\"><colgroup>");
            for (int width : WIDTHS) out.append("<col style=\"width:").append(width).append("%\"/>");
            out.append("</colgroup><thead><tr>");
            for (String label : HEADERS) out.append("<th>").append(label).append("</th>");
            out.append("</tr></thead><tbody>");
            for (var row : product.rows()) {
                out.append("<tr").append(row.heading() ? " class=\"heading\""
                                : row.wording().length() > 1200 ? " class=\"long\"" : "")
                        .append("><td class=\"reference\">").append(escape(row.reference()))
                        .append("</td><td>").append(emphasize(row.wording()))
                        .append("</td><td></td><td></td><td></td></tr>");
            }
            out.append("</tbody></table>");
            if (!product.clarifications().isEmpty()) {
                out.append("<div class=\"clarifications\"><h2>Source Clarifications</h2><ol>");
                for (String note : product.clarifications()) out.append("<li>").append(escape(note)).append("</li>");
                out.append("</ol></div>");
            }
            out.append("</div>");
        }
        return out.append("</body></html>").toString();
    }

    static byte[] docx(Map<String, String> data, List<SpecificationSheetContent.Product> products,
                       byte[] logo, byte[] partner) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CTSectPr section = doc.getDocument().getBody().addNewSectPr();
            CTPageSz size = section.addNewPgSz();
            size.setW(BigInteger.valueOf(11906)); size.setH(BigInteger.valueOf(16838));
            CTPageMar margin = section.addNewPgMar();
            margin.setTop(BigInteger.valueOf(1644)); margin.setBottom(BigInteger.valueOf(567));
            margin.setLeft(BigInteger.valueOf(454)); margin.setRight(BigInteger.valueOf(454));
            margin.setHeader(BigInteger.valueOf(170)); margin.setFooter(BigInteger.valueOf(170));
            XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
            XWPFTable letterhead = header.createTable(1, 3);
            letterhead.setWidth("100%"); letterhead.removeBorders();
            picture(letterhead.getRow(0).getCell(0), logo, "logo.png", 62, 54);
            XWPFTableCell company = letterhead.getRow(0).getCell(1);
            company.setWidth("68%");
            XWPFRun companyRun = paragraph(company.getParagraphs().get(0),
                    data.getOrDefault("companyName", "").toUpperCase(Locale.ROOT), true, 22);
            companyRun.setFontFamily("Calibri"); companyRun.setColor("4472C4");
            for (String line : List.of(data.getOrDefault("companyAddress", ""),
                    "Email ID: " + data.getOrDefault("companyEmail", "") + " URL: " + data.getOrDefault("companyWebsite", ""),
                    "Contact No.: " + data.getOrDefault("companyContact", ""))) {
                paragraph(company.addParagraph(), line, false, 10).setFontFamily("Calibri");
            }
            picture(letterhead.getRow(0).getCell(2), partner, "partner.png", 82, 50);
            header.createParagraph().setBorderBottom(Borders.SINGLE);
            XWPFParagraph footer = doc.createFooter(HeaderFooterType.DEFAULT).createParagraph();
            footer.setAlignment(ParagraphAlignment.CENTER);
            paragraph(footer, "Page ", false, 9);
            footer.getCTP().addNewFldSimple().setInstr("PAGE");
            int ordinal = 0;
            for (var product : products) {
                XWPFParagraph title = doc.createParagraph();
                if (ordinal++ > 0) title.setPageBreak(true);
                title.setAlignment(ParagraphAlignment.CENTER);
                keepNext(title);
                paragraph(title, TITLE, true, 11);
                XWPFParagraph schedule = doc.createParagraph();
                keepNext(schedule); schedule.setSpacingBefore(140);
                paragraph(schedule, "Schedule No. " + product.schedule(), true, 11);
                XWPFParagraph productHeading = doc.createParagraph();
                productHeading.setAlignment(ParagraphAlignment.CENTER); keepNext(productHeading);
                paragraph(productHeading, product.name(), true, 11).addBreak();
                paragraph(productHeading, "Make: __________", true, 11).addBreak();
                paragraph(productHeading, "Model No.: __________", true, 11);
                XWPFTable table = doc.createTable(1, 5);
                table.setWidth("100%");
                table.setCellMargins(30, 75, 30, 75);
                table.getCTTbl().getTblPr().addNewTblLayout().setType(STTblLayoutType.FIXED);
                table.getRow(0).setRepeatHeader(true);
                for (int i = 0; i < 5; i++) cell(table.getRow(0).getCell(i), HEADERS[i], WIDTHS[i], true);
                for (var row : product.rows()) {
                    XWPFTableRow output = table.createRow();
                    for (int i = 0; i < 5; i++) {
                        String text = i == 0 ? row.reference() : i == 1 ? row.wording() : "";
                        cell(output.getCell(i), text, WIDTHS[i], row.heading() || i == 0);
                        if (row.heading()) keepNext(output.getCell(i).getParagraphs().get(0));
                    }
                }
                if (!product.clarifications().isEmpty()) {
                    XWPFParagraph heading = doc.createParagraph(); keepNext(heading);
                    paragraph(heading, "Source Clarifications", true, 11);
                    int note = 0;
                    for (String text : product.clarifications()) paragraph(doc.createParagraph(),
                            (++note) + ". " + text, false, 10);
                }
            }
            doc.write(out); return out.toByteArray();
        }
    }

    private static void cell(XWPFTableCell cell, String text, int width, boolean bold) {
        cell.setWidth(width + "%");
        XWPFParagraph p = cell.getParagraphs().get(0);
        p.setSpacingBefore(0); p.setSpacingAfter(0); p.setSpacingBetween(1.0);
        Matcher matcher = SpecificationSheetContent.EMPHASIS.matcher(text);
        int at = 0;
        while (matcher.find()) {
            paragraph(p, text.substring(at, matcher.start()), bold, 11);
            paragraph(p, matcher.group(), true, 11); at = matcher.end();
        }
        paragraph(p, text.substring(at), bold, 11);
    }

    private static void keepNext(XWPFParagraph paragraph) {
        if (!paragraph.getCTP().isSetPPr()) paragraph.getCTP().addNewPPr();
        paragraph.setKeepNext(true);
    }

    private static XWPFRun paragraph(XWPFParagraph p, String text, boolean bold, int size) {
        p.setSpacingAfter(0); p.setSpacingBefore(0);
        XWPFRun run = p.createRun(); run.setFontFamily("Cambria"); run.setFontSize(size);
        run.setBold(bold); run.setText(text); return run;
    }
    private static void picture(XWPFTableCell cell, byte[] bytes, String name, int width, int height) throws Exception {
        if (bytes != null) cell.getParagraphs().get(0).createRun().addPicture(new ByteArrayInputStream(bytes),
                XWPFDocument.PICTURE_TYPE_PNG, name, width * 12700, height * 12700);
    }
    private static String image(byte[] bytes, int width) {
        return bytes == null ? "" : "<img style=\"width:" + width + "pt\" src=\"data:image/png;base64,"
                + Base64.getEncoder().encodeToString(bytes) + "\"/>";
    }
    private static String emphasize(String text) {
        Matcher matcher = SpecificationSheetContent.EMPHASIS.matcher(text);
        StringBuilder result = new StringBuilder(); int at = 0;
        while (matcher.find()) {
            result.append(escape(text.substring(at, matcher.start()))).append("<strong>")
                    .append(escape(matcher.group())).append("</strong>"); at = matcher.end();
        }
        return result.append(escape(text.substring(at))).toString();
    }
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
