package ei.db.console;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 결과를 엑셀(.xlsx) 한 장으로 쓴다. 외부 라이브러리 없이 Office Open XML 최소 구성만 만든다.
 * 첫 행 칼럼 이름, comments 가 있으면 둘째 행 주석. 머리 행은 굵게 · 틀 고정.
 * 숫자는 숫자 칸(엑셀 정밀도 15자리를 넘으면 글자 칸), null 은 빈 칸, 나머지(날짜 · 시각 포함)는 글자 칸.
 */
final class XlsxWriter {
  private XlsxWriter() {}

  /** 엑셀 숫자는 유효 숫자 15자리까지만 지킨다. 넘으면 끝자리가 바뀌므로 글자로 둔다. */
  private static final int EXCEL_DIGITS = 15;
  /** styles.xml 의 cellXfs 순서: 0 기본, 1 굵게. */
  private static final int STYLE_BOLD = 1;
  private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
  private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

  /** comments 는 칼럼 순서대로 주석(빈 글자 · null 은 빈 칸). 주석 행을 두지 않으려면 null. */
  static void write(Path path, List<String> columns, List<String> comments, List<List<Object>> rows) throws IOException {
    try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(out)) {
      entry(zip, "[Content_Types].xml", HEAD
          + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
          + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
          + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
          + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
          + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
          + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
          + "</Types>");
      entry(zip, "_rels/.rels", HEAD
          + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
          + "<Relationship Id=\"rId1\" Type=\"" + REL_NS + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
          + "</Relationships>");
      entry(zip, "xl/workbook.xml", HEAD
          + "<workbook xmlns=\"" + MAIN_NS + "\" xmlns:r=\"" + REL_NS + "\">"
          + "<sheets><sheet name=\"결과\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
      entry(zip, "xl/_rels/workbook.xml.rels", HEAD
          + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
          + "<Relationship Id=\"rId1\" Type=\"" + REL_NS + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
          + "<Relationship Id=\"rId2\" Type=\"" + REL_NS + "/styles\" Target=\"styles.xml\"/>"
          + "</Relationships>");
      entry(zip, "xl/styles.xml", HEAD
          + "<styleSheet xmlns=\"" + MAIN_NS + "\">"
          + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
          + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
          + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
          + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
          + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
          + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
          + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
          + "</styleSheet>");
      zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
      // 행이 많을 수 있어 문서를 통째로 만들지 않고 흘려 쓴다. 닫으면 zip 까지 닫히므로 flush 만 한다.
      Writer w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
      writeSheet(w, columns, comments, rows);
      w.flush();
      zip.closeEntry();
    }
  }

  private static void entry(ZipOutputStream zip, String name, String xml) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(xml.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static void writeSheet(Writer w, List<String> columns, List<String> comments, List<List<Object>> rows) throws IOException {
    int headRows = comments == null ? 1 : 2;
    w.write(HEAD);
    w.write("<worksheet xmlns=\"" + MAIN_NS + "\"><sheetViews><sheetView workbookViewId=\"0\">"
        + "<pane ySplit=\"" + headRows + "\" topLeftCell=\"A" + (headRows + 1) + "\" activePane=\"bottomLeft\" state=\"frozen\"/>"
        + "</sheetView></sheetViews><sheetData>");
    writeRow(w, 1, List.copyOf(columns), STYLE_BOLD);
    if (comments != null) {
      List<String> filled = new ArrayList<>();
      for (String comment : comments) filled.add(comment == null || comment.isBlank() ? null : comment);
      writeRow(w, 2, filled, STYLE_BOLD);
    }
    int r = headRows;
    for (List<Object> row : rows) writeRow(w, ++r, row, 0);
    w.write("</sheetData></worksheet>");
  }

  private static void writeRow(Writer w, int r, List<?> values, int style) throws IOException {
    w.write("<row r=\"" + r + "\">");
    for (int i = 0; i < values.size(); i++) {
      Object v = values.get(i);
      if (v == null) continue;
      String ref = columnName(i) + r;
      String s = style == 0 ? "" : " s=\"" + style + "\"";
      String number = numberText(v);
      if (number != null) {
        w.write("<c r=\"" + ref + "\"" + s + "><v>" + number + "</v></c>");
      } else {
        w.write("<c r=\"" + ref + "\"" + s + " t=\"inlineStr\"><is><t xml:space=\"preserve\">" + escape(SqlText.text(v)) + "</t></is></c>");
      }
    }
    w.write("</row>");
  }

  /** 숫자 칸에 쓸 글. 숫자가 아니거나 NaN · 무한 · 15자리를 넘는 숫자면 null(글자 칸으로). */
  static String numberText(Object v) {
    if (!(v instanceof Number n)) return null;
    if (n instanceof Double d && (d.isNaN() || d.isInfinite())) return null;
    if (n instanceof Float f && (f.isNaN() || f.isInfinite())) return null;
    BigDecimal b;
    try {
      b = n instanceof BigDecimal x ? x : new BigDecimal(n.toString());
    } catch (NumberFormatException e) {
      return null; // 형식을 모르는 Number 구현은 글자로 둔다.
    }
    b = b.stripTrailingZeros();
    return b.precision() > EXCEL_DIGITS ? null : b.toPlainString();
  }

  /** 0 → A, 25 → Z, 26 → AA. */
  static String columnName(int index) {
    StringBuilder sb = new StringBuilder();
    for (int n = index + 1; n > 0; n = (n - 1) / 26) sb.insert(0, (char) ('A' + (n - 1) % 26));
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
          } else if (Character.isSurrogate(ch) || (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') || ch == 0xFFFE || ch == 0xFFFF) {
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
