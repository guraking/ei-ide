package local.bootservers;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 여러 장짜리 엑셀(.xlsx)을 외부 라이브러리 없이 쓴다(Office Open XML 최소 구성).
 * 장마다 첫 행은 칼럼 이름(굵게 · 틀 고정), 칼럼 너비는 글자 수 단위.
 * 정수 · 유한 실수는 숫자 칸, null 은 빈 칸, 나머지는 글자 칸(inline string).
 */
final class XlsxBook {

    /** 한 장. widths 는 칼럼 순서대로 너비(글자 수), 칼럼 수보다 짧으면 나머지는 기본 너비. */
    record Sheet(String name, List<String> columns, int[] widths, List<List<Object>> rows) {
    }

    /** 엑셀 장 이름 최대 길이. */
    static final int SHEET_NAME_MAX = 31;
    /** styles.xml 의 cellXfs 순서: 0 기본, 1 굵게. */
    private static final int STYLE_BOLD = 1;
    private static final int STYLE_NONE = 0;
    private static final int ALPHABET = 26;
    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String CT_PREFIX = "application/vnd.openxmlformats-officedocument.spreadsheetml.";
    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private XlsxBook() {
    }

    static void write(Path path, List<Sheet> sheets) throws IOException {
        if (sheets.isEmpty()) {
            throw new IllegalArgumentException("장이 하나도 없습니다.");
        }
        StringBuilder overrides = new StringBuilder();
        StringBuilder sheetList = new StringBuilder();
        StringBuilder sheetRels = new StringBuilder();
        for (int i = 1; i <= sheets.size(); i++) {
            String name = sheets.get(i - 1).name();
            if (name == null || name.isBlank() || name.length() > SHEET_NAME_MAX) {
                throw new IllegalArgumentException("장 이름이 비었거나 " + SHEET_NAME_MAX + "자를 넘습니다: " + name);
            }
            overrides.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"").append(CT_PREFIX).append("worksheet+xml\"/>");
            sheetList.append("<sheet name=\"").append(escape(name)).append("\" sheetId=\"").append(i)
                    .append("\" r:id=\"rId").append(i).append("\"/>");
            sheetRels.append("<Relationship Id=\"rId").append(i).append("\" Type=\"").append(REL_NS)
                    .append("/worksheet\" Target=\"worksheets/sheet").append(i).append(".xml\"/>");
        }
        String stylesId = "rId" + (sheets.size() + 1);
        try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(out)) {
            entry(zip, "[Content_Types].xml", HEAD
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"" + CT_PREFIX + "sheet.main+xml\"/>"
                    + overrides
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"" + CT_PREFIX + "styles+xml\"/>"
                    + "</Types>");
            entry(zip, "_rels/.rels", HEAD
                    + "<Relationships xmlns=\"" + PKG_REL_NS + "\">"
                    + "<Relationship Id=\"rId1\" Type=\"" + REL_NS + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            entry(zip, "xl/workbook.xml", HEAD
                    + "<workbook xmlns=\"" + MAIN_NS + "\" xmlns:r=\"" + REL_NS + "\"><sheets>" + sheetList + "</sheets></workbook>");
            entry(zip, "xl/_rels/workbook.xml.rels", HEAD
                    + "<Relationships xmlns=\"" + PKG_REL_NS + "\">" + sheetRels
                    + "<Relationship Id=\"" + stylesId + "\" Type=\"" + REL_NS + "/styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>");
            entry(zip, "xl/styles.xml", HEAD
                    + "<styleSheet xmlns=\"" + MAIN_NS + "\">"
                    + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"맑은 고딕\"/></font><font><b/><sz val=\"11\"/><name val=\"맑은 고딕\"/></font></fonts>"
                    + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
                    + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                    + "</styleSheet>");
            for (int i = 1; i <= sheets.size(); i++) {
                zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + i + ".xml"));
                // 행이 많을 수 있어 흘려 쓴다. Writer 를 닫으면 zip 까지 닫히므로 flush 만 한다.
                Writer w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
                writeSheet(w, sheets.get(i - 1));
                w.flush();
                zip.closeEntry();
            }
        }
    }

    private static void entry(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void writeSheet(Writer w, Sheet sheet) throws IOException {
        w.write(HEAD);
        w.write("<worksheet xmlns=\"" + MAIN_NS + "\"><sheetViews><sheetView workbookViewId=\"0\">"
                + "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
                + "</sheetView></sheetViews>");
        int[] widths = sheet.widths() == null ? new int[0] : sheet.widths();
        if (widths.length > 0) {
            w.write("<cols>");
            for (int i = 0; i < widths.length; i++) {
                w.write("<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + widths[i] + "\" customWidth=\"1\"/>");
            }
            w.write("</cols>");
        }
        w.write("<sheetData>");
        writeRow(w, 1, sheet.columns(), STYLE_BOLD);
        int r = 1;
        for (List<Object> row : sheet.rows()) {
            writeRow(w, ++r, row, STYLE_NONE);
        }
        w.write("</sheetData></worksheet>");
    }

    private static void writeRow(Writer w, int r, List<?> values, int style) throws IOException {
        w.write("<row r=\"" + r + "\">");
        String s = style == STYLE_NONE ? "" : " s=\"" + style + "\"";
        for (int i = 0; i < values.size(); i++) {
            Object v = values.get(i);
            if (v == null) {
                continue;
            }
            String ref = columnName(i) + r;
            String number = numberText(v);
            if (number != null) {
                w.write("<c r=\"" + ref + "\"" + s + "><v>" + number + "</v></c>");
            } else {
                w.write("<c r=\"" + ref + "\"" + s + " t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                        + escape(String.valueOf(v)) + "</t></is></c>");
            }
        }
        w.write("</row>");
    }

    /** 숫자 칸에 쓸 글. 정수형 · 유한 실수만, 나머지는 null(글자 칸). */
    static String numberText(Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return v.toString();
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            return Double.isFinite(d) ? Double.toString(d) : null;
        }
        return null;
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    static String columnName(int index) {
        StringBuilder sb = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / ALPHABET) {
            sb.insert(0, (char) ('A' + (n - 1) % ALPHABET));
        }
        return sb.toString();
    }

    /** XML 특수문자를 바꾸고, XML 1.0 에 쓸 수 없는 제어문자 · 짝 없는 대리 문자는 공백으로 바꾼다(탭 · 줄바꿈은 둔다). */
    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (Character.isHighSurrogate(ch) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        sb.append(ch).append(s.charAt(++i));
                    } else if (Character.isSurrogate(ch) || (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r')
                            || ch == 0xFFFE || ch == 0xFFFF) {
                        sb.append(' ');
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.toString();
    }
}
