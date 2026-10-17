package ei.db.console;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 만든 xlsx 를 다시 열어 부품 · XML 형식 · 칸 내용을 확인한다. */
public class XlsxWriterCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }

  public static void main(String[] args) throws Exception {
    eq(XlsxWriter.columnName(0), "A");
    eq(XlsxWriter.columnName(25), "Z");
    eq(XlsxWriter.columnName(26), "AA");
    eq(XlsxWriter.columnName(701), "ZZ");
    eq(XlsxWriter.columnName(702), "AAA");
    eq(XlsxWriter.numberText(new BigDecimal("12.500")), "12.5");
    eq(XlsxWriter.numberText(new BigDecimal("1234567890123456789")), null);
    eq(XlsxWriter.numberText(Double.NaN), null);
    eq(XlsxWriter.numberText("1"), null);

    Path file = args.length > 0 ? Path.of(args[0]) : Files.createTempFile("xlsx-check", ".xlsx");
    List<List<Object>> rows = List.of(
        Arrays.asList(1, "a<b & \"c\"", null, Timestamp.valueOf("2024-03-01 10:20:30")),
        Arrays.asList(new BigDecimal("1234567890123456789"), "제어\u0001문자\t탭", 3.5, null));
    XlsxWriter.write(file, List.of("ID", "NAME", "QTY", "AT"), List.of("번호", "", "수량", "일시"), rows);

    try (ZipFile zip = new ZipFile(file.toFile())) {
      for (String name : List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                                 "xl/styles.xml", "xl/worksheets/sheet1.xml")) {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new AssertionError("없음: " + name);
        parse(zip, e);
      }
      Document sheet = parse(zip, zip.getEntry("xl/worksheets/sheet1.xml"));
      Element pane = (Element) sheet.getElementsByTagName("pane").item(0);
      eq(pane.getAttribute("ySplit"), "2");
      eq(pane.getAttribute("state"), "frozen");
      eq(sheet.getElementsByTagName("row").getLength(), 4);
      eq(cell(sheet, "A1").getAttribute("s"), "1");
      eq(cell(sheet, "A1").getTextContent(), "ID");
      eq(cell(sheet, "C2").getTextContent(), "수량");
      eq(cell(sheet, "B2"), null);
      eq(cell(sheet, "A3").getAttribute("t"), "");
      eq(cell(sheet, "A3").getTextContent(), "1");
      eq(cell(sheet, "B3").getTextContent(), "a<b & \"c\"");
      eq(cell(sheet, "C3"), null);
      eq(cell(sheet, "D3").getAttribute("t"), "inlineStr");
      eq(cell(sheet, "D3").getTextContent(), "2024-03-01 10:20:30.0");
      eq(cell(sheet, "A4").getAttribute("t"), "inlineStr");
      eq(cell(sheet, "A4").getTextContent(), "1234567890123456789");
      eq(cell(sheet, "B4").getTextContent(), "제어 문자\t탭");
      eq(cell(sheet, "C4").getTextContent(), "3.5");
    }

    Path noComment = Files.createTempFile("xlsx-check", ".xlsx");
    XlsxWriter.write(noComment, List.of("A"), null, List.of());
    try (ZipFile zip = new ZipFile(noComment.toFile())) {
      Document sheet = parse(zip, zip.getEntry("xl/worksheets/sheet1.xml"));
      eq(((Element) sheet.getElementsByTagName("pane").item(0)).getAttribute("ySplit"), "1");
      eq(sheet.getElementsByTagName("row").getLength(), 1);
    }
    Files.delete(noComment);
    if (args.length == 0) Files.delete(file);
    System.out.println("XlsxWriterCheck 통과");
  }

  static Document parse(ZipFile zip, ZipEntry e) throws Exception {
    try (InputStream in = zip.getInputStream(e)) {
      return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
    }
  }

  static Element cell(Document sheet, String ref) {
    NodeList cells = sheet.getElementsByTagName("c");
    for (int i = 0; i < cells.getLength(); i++) {
      Element c = (Element) cells.item(i);
      if (c.getAttribute("r").equals(ref)) return c;
    }
    return null;
  }
}
