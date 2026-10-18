package local.bootservers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** ProgramList · XlsxBook 자체 점검. 실패하면 예외로 끝난다. */
public final class ProgramListCheck {

    public static void main(String[] args) throws IOException {
        kinds();
        javadoc();
        descriptions();
        lines();
        row();
        xlsx();
        System.out.println("ProgramListCheck 통과");
    }

    private static void kinds() {
        eq(ProgramList.KIND_CONTROLLER, ProgramList.kind("OrderApi.java",
                "package a;\nimport org.springframework.web.bind.annotation.RestController;\n@RestController\npublic class OrderApi {}"), "@RestController");
        eq(ProgramList.KIND_CONTROLLER, ProgramList.kind("Order.java",
                "@org.springframework.stereotype.Controller\n@RequestMapping(\"/o\")\npublic class Order {}"), "전체 이름 @Controller");
        eq(ProgramList.KIND_SERVICE, ProgramList.kind("OrderLogic.java", "@Service(\"x\") public class OrderLogic {}"), "@Service");
        eq(ProgramList.KIND_DAO, ProgramList.kind("OrderStore.java", "@Mapper\npublic interface OrderStore {}"), "@Mapper");
        eq(ProgramList.KIND_DAO, ProgramList.kind("OrderStore.java", "@Repository public class OrderStore {}"), "@Repository");
        eq(ProgramList.KIND_CONFIG, ProgramList.kind("Web.java", "@Configuration\npublic class Web {}"), "@Configuration");
        eq(ProgramList.KIND_SERVICE, ProgramList.kind("OrderController.java", "@Service public class OrderController {}"), "주석이 이름보다 먼저");
        eq(ProgramList.KIND_CONTROLLER, ProgramList.kind("OrderController.java", "public class OrderController {}"), "끝말 Controller");
        eq(ProgramList.KIND_SERVICE, ProgramList.kind("OrderServiceImpl.java", "public class OrderServiceImpl {}"), "끝말 ServiceImpl");
        eq(ProgramList.KIND_DAO, ProgramList.kind("OrderDAO.java", "public class OrderDAO {}"), "끝말 DAO");
        eq(ProgramList.KIND_DAO, ProgramList.kind("OrderMapper.java", null), "끝말 Mapper · 내용 없음");
        eq(ProgramList.KIND_VO, ProgramList.kind("OrderVO.java", "public class OrderVO {}"), "끝말 VO");
        eq(ProgramList.KIND_VO, ProgramList.kind("OrderDto.java", "public record OrderDto(int a) {}"), "끝말 Dto");
        eq(ProgramList.KIND_CONFIG, ProgramList.kind("SecurityConfig.java", "public class SecurityConfig {}"), "끝말 Config");
        eq(ProgramList.KIND_JAVA, ProgramList.kind("StringUtil.java",
                "/** {@link Service} 를 돕는다. */\npublic class StringUtil {}"), "Javadoc 안 {@link} 는 주석 아님");
        eq(ProgramList.KIND_JAVA, ProgramList.kind("Holder.java",
                "class Other { @Service void x(){} }\n"), "선언 못 찾으면 이름으로");
        eq(ProgramList.KIND_JSP, ProgramList.kind("list.jsp", ""), "JSP");
        eq(ProgramList.KIND_JSP, ProgramList.kind("head.JSPF", null), "JSPF 대문자");
        eq(ProgramList.KIND_MAPPER_XML, ProgramList.kind("OrderMapper.xml",
                "<!DOCTYPE mapper>\n<mapper namespace=\"a.OrderMapper\">"), "Mapper XML");
        eq(ProgramList.KIND_MAPPER_XML, ProgramList.kind("order.xml", "<sqlMap namespace='order'>"), "iBATIS sqlMap");
        eq(ProgramList.KIND_SETTINGS, ProgramList.kind("logback.xml", "<configuration/>"), "일반 XML");
        eq(ProgramList.KIND_JS, ProgramList.kind("app.js", ""), "JS");
        eq(ProgramList.KIND_CSS, ProgramList.kind("app.scss", ""), "CSS");
        eq(ProgramList.KIND_SETTINGS, ProgramList.kind("application.yml", ""), "yml");
        eq(ProgramList.KIND_SETTINGS, ProgramList.kind("db.properties", ""), "properties");
        eq(ProgramList.KIND_OTHER, ProgramList.kind("logo.png", null), "이미지");
        eq(ProgramList.KIND_OTHER, ProgramList.kind("README", ""), "확장자 없음");
        yes(ProgramList.isExcludedDir("node_modules") && ProgramList.isExcludedDir("generated")
                && !ProgramList.isExcludedDir("src"), "제외 폴더");
    }

    private static void javadoc() {
        String src = "/*\n * 저작권\n */\npackage a.b;\n\nimport x.Y;\n\n"
                + "/**\n * 주문 목록을 보여 준다. 두 번째 문장.\n *\n * @author 홍길동\n */\n"
                + "@Controller\n@RequestMapping(value = {\"/a\", \"/b\"})\npublic final class OrderController {\n"
                + "  /** 메서드 설명. */ void m() {}\n}";
        eq("a.b", ProgramList.javaPackage(src), "패키지");
        eq("주문 목록을 보여 준다.", ProgramList.firstSentence(ProgramList.classJavadoc(src, "OrderController")), "클래스 Javadoc 첫 문장");
        eq("", ProgramList.firstSentence(ProgramList.classJavadoc(
                "/** 저작권 */\npackage a;\nimport b;\npublic class A {}", "A")), "package 앞 주석은 클래스 것 아님");
        eq("", ProgramList.firstSentence(null), "null");
        eq("주문 처리", ProgramList.firstSentence("/** 주문 처리\n * @since 1 */"), "마침표 없음 · 블록 태그에서 끊음");
        eq("버전 1.2 를 지원한다.", ProgramList.firstSentence("/** 버전 1.2 를 지원한다. 끝. */"), "숫자 속 점은 문장 끝 아님");
        eq("OrderService#find 를 부른다.", ProgramList.firstSentence(
                "/** {@link a.b.OrderService#find} 를 부른다. */"), "{@link} 단순 이름");
        eq("목록 조회 화면", ProgramList.firstSentence("/** {@linkplain List 목록} <b>조회</b> 화면<p>둘째 문단. */"), "{@linkplain} 글 · 태그 · 문단");
        eq("a < b 이면 참.", ProgramList.firstSentence("/** {@code a < b} 이면 참. */"), "{@code}");
        eq("x &lt; y", ProgramList.firstSentence("/** x &amp;lt; y */"), "HTML 엔티티 한 번만 풂");
        eq("요약.", ProgramList.firstSentence("/**\n * <p>요약. 나머지\n */"), "앞 <p>");
    }

    private static void descriptions() {
        eq("주문 목록", ProgramList.jspDescription("<%-- 머리 --%><html><head><title> 주문\n 목록 </title>"), "JSP <title>");
        eq("머리 주석", ProgramList.jspDescription("<%--\n  머리   주석\n--%><title><spring:message code=\"t\"/></title>"), "빈 <title> 은 주석으로");
        eq("", ProgramList.jspDescription("<html></html>"), "JSP 설명 없음");
        eq("a.b.OrderMapper", ProgramList.mapperNamespace("<mapper\n  namespace=\"a.b.OrderMapper\">"), "namespace");
        String longComment = "<%--" + "가".repeat(ProgramList.DESCRIPTION_MAX + 10) + "--%>";
        eq(ProgramList.DESCRIPTION_MAX, ProgramList.jspDescription(longComment).length(), "설명 길이 제한");
    }

    private static void lines() {
        eq(0, ProgramList.lineCount(""), "빈 파일");
        eq(1, ProgramList.lineCount("a"), "한 줄");
        eq(1, ProgramList.lineCount("a\n"), "끝 줄바꿈");
        eq(3, ProgramList.lineCount("a\r\nb\r\nc"), "CRLF");
        eq(2, ProgramList.lineCount("a\rb\r"), "CR");
        eq(3, ProgramList.lineCount("a\n\nb\n"), "빈 줄 포함");
    }

    private static void row() {
        List<Object> r = ProgramList.programRow(7, "web", "src/main/java/a/OrderController.java", "OrderController.java",
                "package a;\n/** 주문. */\n@Controller\npublic class OrderController {}\n", 0);
        eq(List.of(7, "web", ProgramList.KIND_CONTROLLER, "src/main/java/a/OrderController.java", "OrderController.java",
                "a", "OrderController", "주문.", 4, ""), r, "자바 행");
        List<Object> binary = ProgramList.programRow(1, "web", "img/a.png", "a.png", null, 0);
        yes(binary.get(8) == null && "".equals(binary.get(7)), "이진 파일은 줄 수 · 설명 빈 칸");
        yes(ProgramList.date(1_700_000_000_000L).matches("\\d{4}-\\d{2}-\\d{2}"), "날짜 형식");
        eq(ProgramList.PROGRAM_COLUMNS.size(), ProgramList.PROGRAM_WIDTHS.length, "프로그램 칼럼 너비 수");
        eq(ProgramList.URL_COLUMNS.size(), ProgramList.URL_WIDTHS.length, "URL 칼럼 너비 수");
    }

    private static void xlsx() throws IOException {
        eq("A", XlsxBook.columnName(0), "A");
        eq("AA", XlsxBook.columnName(26), "AA");
        eq("a&amp;&lt;b&gt;&quot; c\td", XlsxBook.escape("a&<b>\"\u0001c\td"), "escape");
        eq("x y", XlsxBook.escape("x\uD800y"), "짝 없는 대리 문자");
        eq("😀", XlsxBook.escape("😀"), "짝 맞는 대리 문자");
        Path file = Files.createTempFile("program-list", ".xlsx");
        try {
            List<List<Object>> programs = new ArrayList<>();
            programs.add(Arrays.asList(1, "모듈", "<a&b>", null, 12L));
            List<List<Object>> urls = new ArrayList<>();
            XlsxBook.write(file, List.of(
                    new XlsxBook.Sheet("프로그램 목록", List.of("번호", "모듈", "구분", "경로", "줄 수"), new int[]{6, 16}, programs),
                    new XlsxBook.Sheet("URL 목록", ProgramList.URL_COLUMNS, ProgramList.URL_WIDTHS, urls)));
            Map<String, String> parts = read(file);
            yes(parts.keySet().containsAll(List.of("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml",
                    "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")), "부품 " + parts.keySet());
            String workbook = parts.get("xl/workbook.xml");
            yes(workbook.contains("<sheet name=\"프로그램 목록\" sheetId=\"1\" r:id=\"rId1\"/>")
                    && workbook.contains("<sheet name=\"URL 목록\" sheetId=\"2\" r:id=\"rId2\"/>"), "장 이름");
            String rels = parts.get("xl/_rels/workbook.xml.rels");
            yes(rels.contains("Id=\"rId2\"") && rels.contains("Target=\"worksheets/sheet2.xml\"")
                    && rels.contains("Id=\"rId3\"") && rels.contains("Target=\"styles.xml\""), "관계");
            yes(parts.get("[Content_Types].xml").contains("/xl/worksheets/sheet2.xml"), "내용 형식");
            String sheet1 = parts.get("xl/worksheets/sheet1.xml");
            yes(sheet1.contains("state=\"frozen\"") && sheet1.contains("ySplit=\"1\""), "머리 행 고정");
            yes(sheet1.contains("<col min=\"2\" max=\"2\" width=\"16\" customWidth=\"1\"/>"), "칼럼 너비");
            yes(sheet1.contains("<c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">번호</t></is></c>"), "머리 굵게");
            yes(sheet1.contains("<c r=\"A2\"><v>1</v></c>"), "정수는 숫자 칸");
            yes(sheet1.contains("<c r=\"E2\"><v>12</v></c>"), "Long 은 숫자 칸");
            yes(sheet1.contains("&lt;a&amp;b&gt;") && !sheet1.contains("<a&b>"), "글 escape");
            yes(!sheet1.contains("r=\"D2\""), "null 은 빈 칸");
            yes(parts.get("xl/worksheets/sheet2.xml").contains("<row r=\"1\">") && !parts.get("xl/worksheets/sheet2.xml").contains("<row r=\"2\">"),
                    "빈 장은 머리 행만");
        } finally {
            Files.deleteIfExists(file);
        }
        boolean rejected = false;
        try {
            XlsxBook.write(file, List.of(new XlsxBook.Sheet("가".repeat(XlsxBook.SHEET_NAME_MAX + 1), List.of(), null, List.of())));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        yes(rejected && !Files.exists(file), "긴 장 이름 거부 · 파일 안 만듦");
    }

    private static Map<String, String> read(Path file) throws IOException {
        Map<String, String> parts = new HashMap<>();
        try (InputStream in = Files.newInputStream(file); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                zip.transferTo(bytes);
                parts.put(entry.getName(), bytes.toString(StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    private static void eq(Object expected, Object actual, String name) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": 기대 [" + expected + "] 실제 [" + actual + "]");
        }
    }

    private static void yes(boolean ok, String name) {
        if (!ok) {
            throw new AssertionError(name);
        }
    }
}
