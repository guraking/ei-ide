package ei.db.console;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

public class RunHistoryCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }
  public static void main(String[] args) throws Exception {
    RunHistory.Entry e = new RunHistory.Entry(1700000000000L, "id-1", "개발\t접속", 12, 3, true, "SELECT 'a\\b'\n  FROM DUAL\r\n\t;", List.of());
    String line = RunHistory.format(e);
    eq(line.contains("\n"), false);
    eq(line.split("\t", -1).length, 7);
    eq(RunHistory.parse(line), e);
    RunHistory.Entry empty = new RunHistory.Entry(1, "x", "", 0, RunHistory.NO_COUNT, false, "", List.of());
    eq(RunHistory.parse(RunHistory.format(empty)), empty);
    eq(RunHistory.parse(""), null);
    eq(RunHistory.parse("a\tb\tc\t1\t2\t1\tsql"), null);
    eq(RunHistory.unescape("끝\\"), "끝\\");

    // 바인드 값: 형식별로 되돌아오고, 탭 · 줄바꿈 · 「-」 · 콜론이 든 문자열과 null 이 섞여도 칸이 어긋나지 않는다.
    List<Object> binds = Arrays.asList("A\tB\nC", "-", "S:x", null, 7L, 3, new BigDecimal("1.50"), 2.5d, true,
        Date.valueOf("2026-10-17"), Timestamp.valueOf("2026-10-17 09:30:15.25"), "");
    RunHistory.Entry withBinds = new RunHistory.Entry(5, "id", "n", 1, 0, true, "SELECT * FROM T WHERE A = ? AND B = ?", binds);
    String bl = RunHistory.format(withBinds);
    eq(bl.contains("\n"), false);
    eq(bl.split("\t", -1).length, 7 + binds.size());
    RunHistory.Entry back = RunHistory.parse(bl);
    eq(back.sql(), withBinds.sql());
    eq(back.binds().size(), binds.size());
    eq(back.binds().get(0), "A\tB\nC");
    eq(back.binds().get(1), "-");
    eq(back.binds().get(2), "S:x");
    eq(back.binds().get(3), null);
    eq(back.binds().get(4), 7L);
    eq(back.binds().get(5), 3);
    eq(back.binds().get(6), new BigDecimal("1.50"));
    eq(back.binds().get(7), new BigDecimal("2.5"));
    eq(back.binds().get(8), true);
    eq(back.binds().get(9), Date.valueOf("2026-10-17"));
    eq(back.binds().get(10), Timestamp.valueOf("2026-10-17 09:30:15.25"));
    eq(back.binds().get(11), "");
    // 깨진 바인드 칸이 있는 줄은 버린다.
    eq(RunHistory.parse("1\tid\tn\t0\t0\t1\tSELECT ?\tL:abc"), null);
    eq(RunHistory.parse("1\tid\tn\t0\t0\t1\tSELECT ?\tX:1"), null);
    // 예전 형식(7칸) 줄은 값 없음으로 읽힌다.
    eq(RunHistory.parse("1\tid\tn\t0\t0\t1\tSELECT 1").binds(), List.of());

    Path dir = Files.createTempDirectory("hist");
    Path file = dir.resolve("sub").resolve("history.tsv");
    RunHistory h = new RunHistory(file);
    eq(h.entries(), List.of());
    for (int i = 0; i < RunHistory.MAX + 5; i++) h.add(new RunHistory.Entry(i, "id", "n", i, i, i % 2 == 0, "SELECT " + i, List.of()));
    List<RunHistory.Entry> all = new RunHistory(file).entries();
    eq(all.size(), RunHistory.MAX);
    eq(all.get(0).sql(), "SELECT " + (RunHistory.MAX + 4));
    eq(all.get(all.size() - 1).sql(), "SELECT 5");
    // 깨진 줄은 건너뛰고 예전 줄 · 새 줄은 함께 읽는다.
    Files.writeString(file, "깨진 줄\n1\tid\tn\t0\t0\t1\tSELECT 1\n" + bl + "\n");
    List<RunHistory.Entry> mixed = new RunHistory(file).entries();
    eq(mixed.size(), 2);
    eq(mixed.get(0).binds().size(), binds.size());
    eq(mixed.get(1).binds(), List.of());
    System.out.println("RunHistoryCheck 통과");
  }
}
