package local.bootservers.web;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 자바스크립트 작업대 점검: 예제 파일 모양, 실행기 출력 읽기, 칸 이름 읽기, 고르기로 만든 코드.
 * PATH 에 node 가 있으면 실행기 · 바꾸기 · 만든 코드 · 실행할 수 있는 예제 전부를 실제로 돌려 오류가 없는지 본다(없으면 그 부분만 건너뜀).
 */
public final class JsBenchCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        examplesShape();
        parseEvents();
        columns();
        pipelineText();
        Path node = WebTools.findNode(Path.of("없는-폴더"), System.getenv("PATH"), File.separatorChar == '\\');
        if (node == null) {
            System.out.println("node 가 PATH 에 없어 실행 점검은 건너뜀");
        } else {
            runs(node);
            pipelineRuns(node);
            examplesRun(node);
        }
        if (failures > 0) {
            System.out.println("JsBenchCheck 실패 " + failures + "건");
            System.exit(1);
        }
        System.out.println("JsBenchCheck 통과");
    }

    private static void examplesShape() throws Exception {
        List<JsExamples.Example> small = JsExamples.parse("""
                ## 날짜
                ### 오늘
                > 오늘 날짜
                > 둘째 줄
                @보기만

                console.log(1)
                ## 이름

                ### 표
                console.table(data)
                --- 데이터
                [{"A":1}]

                """);
        check(small.size() == 2, "예제 두 개: " + small.size());
        check(small.get(0).description().equals("오늘 날짜 둘째 줄"), "설명 이어 붙임: " + small.get(0).description());
        check(small.get(0).viewOnly(), "보기만");
        check(small.get(0).code().equals("console.log(1)"), "코드 앞뒤 빈 줄: [" + small.get(0).code() + "]");
        check(small.get(1).group().equals("이름") && small.get(1).data().equals("[{\"A\":1}]"), "데이터: " + small.get(1));
        expectFail("### 묶음없음\nx\n");
        expectFail("## 묶음\n### 코드없음\n> 설명\n");
        expectFail("## 묶음\n떠도는 글\n");

        List<JsExamples.Example> all = JsExamples.load();
        Set<String> titles = new HashSet<>();
        Set<String> groups = new HashSet<>();
        for (JsExamples.Example e : all) {
            check(titles.add(e.group() + " / " + e.title()), "같은 묶음에 같은 제목: " + e.title());
            check(!e.description().isBlank(), "설명 없음: " + e.title());
            groups.add(e.group());
        }
        System.out.println("예제 " + all.size() + "개, 묶음 " + groups.size() + "개");
    }

    private static void expectFail(String text) {
        try {
            JsExamples.parse(text);
            check(false, "틀린 모양을 받아들임: " + text);
        } catch (IllegalArgumentException expected) {
            // 맞는 동작
        }
    }

    private static void parseEvents() {
        JsBench.Event plain = JsBench.parse("그냥 글");
        check(plain.kind() == JsBench.Kind.TEXT && plain.text().equals("그냥 글"), "MARK 없는 줄");
        JsBench.Event log = JsBench.parse(JsBench.MARK + "{\"kind\":\"log\",\"text\":\"안녕\"}");
        check(log.kind() == JsBench.Kind.LOG && log.text().equals("안녕"), "log");
        JsBench.Event table = JsBench.parse(JsBench.MARK + "{\"kind\":\"table\",\"columns\":[\"#\",\"A\"],\"rows\":[[\"0\",\"1\"]],\"more\":3}");
        check(table.kind() == JsBench.Kind.TABLE && table.columns().size() == 2 && table.rows().get(0).get(1).equals("1") && table.more() == 3, "table");
        check(JsBench.parse(JsBench.MARK + "{깨짐").kind() == JsBench.Kind.TEXT, "깨진 JSON");
        check(JsBench.parse(JsBench.MARK + "{\"kind\":\"모름\"}").kind() == JsBench.Kind.TEXT, "모르는 kind");
        check("document".equals(JsBench.browserOnlyName("ReferenceError: document is not defined")), "브라우저 이름");
        check(JsBench.browserOnlyName("ReferenceError: foo is not defined") == null, "브라우저 아닌 이름");
    }

    private static void columns() {
        check(JsBench.columns("[{\"A\":1,\"B\":2},{\"C\":3,\"A\":4}]").equals(List.of("A", "B", "C")), "객체 배열 칸");
        check(JsBench.columns("{\"X\":1}").equals(List.of("X")), "객체 칸");
        check(JsBench.columns("이름\t금액\t\n홍\t1\t").equals(List.of("이름", "금액", "COL3")), "탭 표 칸");
        check(JsBench.columns("").isEmpty() && JsBench.columns("그냥 글").isEmpty() && JsBench.columns("[1,2]").isEmpty(), "칸 없음");
    }

    private static void pipelineText() {
        check(JsPipeline.quote("a'b\\c\n").equals("'a\\'b\\\\c\\n'"), "따옴표: " + JsPipeline.quote("a'b\\c\n"));
        check(JsPipeline.field("r", "USER_ID").equals("r.USER_ID"), "칸 점");
        check(JsPipeline.field("r", "고객 이름").equals("r['고객 이름']"), "칸 대괄호");
        String none = JsPipeline.generate(new JsPipeline.Spec(null, JsPipeline.Op.EQ, "", null, null, null, null, null, false, 0));
        check(none.contains("console.table(rows)") && !none.contains("filter"), "단계 없음: " + none);
    }

    private static final String SALES = """
            [{"DEPT":"영업","NAME":"가","AMT":100,"CODE":"A1"},
             {"DEPT":"영업","NAME":"나","AMT":300,"CODE":"A2"},
             {"DEPT":"개발","NAME":"다","AMT":50,"CODE":"B1"},
             {"DEPT":"개발","NAME":"라","AMT":"20","CODE":"B1"},
             {"DEPT":"총무","NAME":"마","AMT":null,"CODE":""}]""";

    private static void pipelineRuns(Path node) throws Exception {
        expectTable(node, new JsPipeline.Spec("DEPT", JsPipeline.Op.EQ, "영업", null, null, null, null, "AMT", true, 0),
                List.of(List.of("0", "영업", "나", "300", "A2"), List.of("1", "영업", "가", "100", "A1")));
        expectTable(node, new JsPipeline.Spec("AMT", JsPipeline.Op.GE, "1,00", null, null, null, null, null, false, 0),
                List.of(List.of("0", "영업", "가", "100", "A1"), List.of("1", "영업", "나", "300", "A2")));
        expectTable(node, new JsPipeline.Spec(null, null, null, null, "DEPT", JsPipeline.Agg.SUM, "AMT", "SUM_AMT", true, 2),
                List.of(List.of("0", "영업", "400"), List.of("1", "개발", "70")));
        expectTable(node, new JsPipeline.Spec(null, null, null, "CODE", "DEPT", JsPipeline.Agg.COUNT, null, null, false, 0),
                List.of(List.of("0", "영업", "2"), List.of("1", "개발", "1"), List.of("2", "총무", "1")));
        expectTable(node, new JsPipeline.Spec("CODE", JsPipeline.Op.EMPTY, null, null, null, JsPipeline.Agg.MAX, "AMT", null, false, 0),
                List.of(List.of("0", "0")));
        expectTable(node, new JsPipeline.Spec(null, null, null, null, null, JsPipeline.Agg.AVG, "AMT", null, false, 0),
                List.of(List.of("0", "94")));
        expectTable(node, new JsPipeline.Spec("NAME", JsPipeline.Op.CONTAINS, "'", null, null, null, null, null, false, 0), List.of());
    }

    private static void expectTable(Path node, JsPipeline.Spec spec, List<List<String>> rows) throws Exception {
        String code = JsPipeline.generate(spec);
        JsBench.Result r = new JsBench.Run().execute(node, JsBench.Mode.RUN_JS, code, SALES, null);
        JsBench.Event table = null;
        for (JsBench.Event e : r.events()) {
            if (e.kind() == JsBench.Kind.TABLE) {
                table = e;
            }
        }
        boolean ok = !r.failed() && table != null && table.rows().size() == rows.size();
        if (ok) {
            for (int i = 0; i < rows.size(); i++) {
                List<String> got = table.rows().get(i);
                ok &= got.containsAll(rows.get(i)) && got.size() >= rows.get(i).size();
            }
        }
        check(ok, "고르기로 만든 코드 결과가 다름\n" + code + "\n→ " + r.events());
    }

    private static void runs(Path node) throws Exception {
        JsBench.Result r = run(node, JsBench.Mode.RUN_JS, "console.log('a', 1); [1, 2].map(x => x * 2)", "");
        check(!r.failed() && r.events().size() == 3 && r.events().get(1).kind() == JsBench.Kind.TABLE, "실행: " + r.events());
        r = run(node, JsBench.Mode.RUN_JS, "const n = await Promise.resolve(data.length); return n", "[1,2,3]");
        check(!r.failed() && r.events().get(0).text().equals("3"), "await · return: " + r.events());
        r = run(node, JsBench.Mode.RUN_JS, "foo()", "");
        check(r.failed() && r.events().get(0).text().startsWith("ReferenceError: foo is not defined"), "오류: " + r.events());
        r = run(node, JsBench.Mode.RUN_JS, "while (true) {}", "");
        check(r.timedOut() && r.failed(), "시간 초과: " + r);
        r = run(node, JsBench.Mode.RUN_JS, "for (;;) console.log('x'.repeat(1000))", "");
        check(r.truncated() && r.failed(), "출력 넘침: " + r.truncated() + " " + r.timedOut());
        r = run(node, JsBench.Mode.RUN_JS, "process.stdout.write('날것\\n'); console.table(data)", "이름\t금액\n홍\t1,200\n김\t007");
        check(!r.failed() && r.events().get(0).text().equals("날것") && r.events().get(1).rows().get(0).get(2).equals("1200")
                && r.events().get(1).rows().get(1).get(2).equals("007"), "엑셀 표 데이터: " + r.events());
        r = run(node, JsBench.Mode.TS, "", "[{\"ID\":1,\"NM\":\"a\"},{\"ID\":2}]");
        check(!r.failed() && r.events().get(0).text().contains("NM?: string;"), "TS 타입: " + r.events());
        r = run(node, JsBench.Mode.PROPS, "", "{\"USER_NM\":\"a\",\"AGE\":3,\"TAGS\":[]}");
        check(!r.failed() && r.events().get(0).text().contains("AGE: { type: Number, default: 0 }"), "Vue props: " + r.events());
        r = run(node, JsBench.Mode.CAMEL, "", "{\"USER_NM\":\"a\",\"userId\":1}");
        check(!r.failed() && r.events().get(0).text().contains("\"userNm\"") && r.events().get(0).text().contains("\"userId\""), "camel: " + r.events());
        r = run(node, JsBench.Mode.CAMEL, "", "그냥 글");
        check(r.failed(), "JSON 아닌 데이터 바꾸기는 실패");
        Path tsLib = tsLib(node);
        if (tsLib != null) {
            r = new JsBench.Run().execute(node, JsBench.Mode.RUN_TS, "const n: number = 2;\ninterface A { x: number }\nconsole.log(n * 3)", "", tsLib);
            check(!r.failed() && r.events().get(0).text().equals("6"), "TypeScript 실행: " + r.events());
        } else {
            System.out.println("typescript 를 찾지 못해 TS 실행 점검은 건너뜀");
        }
        JsBench.Run stopped = new JsBench.Run();
        stopped.stop();
        r = stopped.execute(node, JsBench.Mode.RUN_JS, "console.log(1)", "", null);
        check(r.cancelled() && r.events().isEmpty(), "멈춘 뒤 실행 안 함");
    }

    /** node 옆 전역 모듈의 typescript/lib(npm i -g typescript). 없으면 null. */
    private static Path tsLib(Path node) {
        Path real;
        try {
            real = node.toRealPath();
        } catch (java.io.IOException e) {
            return null;
        }
        for (Path candidate : List.of(real.getParent().resolveSibling("lib/node_modules/typescript/lib"),
                real.getParent().resolve("node_modules/typescript/lib"))) {
            if (Files.isRegularFile(candidate.resolve("typescript.js"))) {
                return candidate;
            }
        }
        return null;
    }

    private static JsBench.Result run(Path node, JsBench.Mode mode, String code, String data) throws Exception {
        return new JsBench.Run().execute(node, mode, code, data, null);
    }

    private static void examplesRun(Path node) throws Exception {
        Path tsLib = tsLib(node);
        List<JsExamples.Example> runnable = new ArrayList<>();
        for (JsExamples.Example e : JsExamples.load()) {
            if (!e.viewOnly()) {
                runnable.add(e);
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()));
        try {
            List<Future<String>> results = new ArrayList<>();
            for (JsExamples.Example e : runnable) {
                results.add(pool.submit(() -> {
                    JsBench.Result r = new JsBench.Run().execute(node, JsBench.Mode.RUN_JS, e.code(), e.data(), tsLib);
                    if (!r.failed() && !r.events().isEmpty()) {
                        return null;
                    }
                    return e.group() + " / " + e.title() + (r.events().isEmpty() ? " → 출력 없음" : " → " + r.events())
                            + (r.timedOut() ? " (시간 초과)" : "");
                }));
            }
            for (Future<String> f : results) {
                String problem = f.get();
                check(problem == null, "예제 실행 실패: " + problem);
            }
        } finally {
            pool.shutdownNow();
        }
        System.out.println("실행한 예제 " + runnable.size() + "개");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("실패: " + what);
        }
    }
}
