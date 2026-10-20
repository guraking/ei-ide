package local.bootservers;

import local.bootservers.LogTail.Event;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** LogTail 자체 점검(임시 폴더의 파일로). 실패하면 예외로 끝난다. */
public final class LogTailCheck {

    private static final Charset MS949 = Charset.forName("MS949");

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("logtail");
        try {
            follow(dir.resolve("a.log"));
            initialTail(dir.resolve("b.log"));
            rotateAndMissing(dir.resolve("c.log"));
            fallbackCharset(dir.resolve("d.log"));
            longLine(dir.resolve("e.log"));
            backlog(dir.resolve("f.log"));
        }
        finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        System.out.println("LogTailCheck 통과");
    }

    private static void follow(Path f) throws Exception {
        Files.writeString(f, "첫 줄\n둘째", StandardCharsets.UTF_8);
        LogTail tail = new LogTail(f, MS949, 1024);
        LogTail.Poll p = tail.poll();
        check(p.event() == Event.NONE && p.text().equals("첫 줄\n"), "끝나지 않은 줄은 미룸: " + p);
        append(f, " 줄\n셋째 줄\n");
        p = tail.poll();
        check(p.text().equals("둘째 줄\n셋째 줄\n"), "이어 읽기: " + p);
        check(tail.poll().text().isEmpty(), "새 글 없음");
    }

    private static void initialTail(Path f) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("line").append(i).append('\n');
        }
        Files.writeString(f, sb, StandardCharsets.UTF_8);
        LogTail tail = new LogTail(f, MS949, 20);
        String text = tail.poll().text();
        check(text.endsWith("line99\n") && !text.contains("line90") && text.startsWith("line9"), "끝 부분만, 줄 처음부터: " + text);
    }

    private static void rotateAndMissing(Path f) throws Exception {
        Files.writeString(f, "old 1\nold 2\n", StandardCharsets.UTF_8);
        LogTail tail = new LogTail(f, MS949, 1024);
        tail.poll();
        Files.writeString(f, "new\n", StandardCharsets.UTF_8);
        LogTail.Poll p = tail.poll();
        check(p.event() == Event.ROTATED && p.text().equals("new\n"), "줄어들면 처음부터: " + p);
        Files.delete(f);
        check(tail.poll().event() == Event.MISSING, "없으면 MISSING");
        check(tail.poll().event() == Event.NONE, "MISSING 은 한 번만");
        Files.writeString(f, "again\n", StandardCharsets.UTF_8);
        p = tail.poll();
        check(p.event() == Event.APPEARED && p.text().equals("again\n"), "다시 생기면 처음부터: " + p);
        LogTail never = new LogTail(f.resolveSibling("none.log"), MS949, 1024);
        check(never.poll().event() == Event.MISSING, "처음부터 없는 파일");
    }

    private static void fallbackCharset(Path f) throws Exception {
        Files.write(f, "한글 로그\n".getBytes(MS949));
        check(new LogTail(f, MS949, 1024).poll().text().equals("한글 로그\n"), "UTF-8 이 아니면 대체 문자셋");
    }

    private static void longLine(Path f) throws Exception {
        Files.writeString(f, "x".repeat(LogTail.MAX_READ_BYTES + 10), StandardCharsets.UTF_8);
        LogTail tail = new LogTail(f, MS949, LogTail.MAX_READ_BYTES * 2L);
        check(tail.poll().text().length() == LogTail.MAX_READ_BYTES, "줄바꿈 없는 긴 글은 한도만큼");
        append(f, "\n");
        check(tail.poll().text().equals("x".repeat(10) + "\n"), "나머지");
    }

    /** 1초 사이에 수 MB 가 쌓여도 한 poll 에 다 읽고, 한도를 넘는 몫만 다음 poll 로 미룬다. */
    private static void backlog(Path f) throws Exception {
        Files.writeString(f, "", StandardCharsets.UTF_8);
        LogTail tail = new LogTail(f, MS949, 0);
        tail.poll();
        String line = "x".repeat(99) + "\n";
        int lines = LogTail.MAX_READ_BYTES / line.length() * 3 / 2;
        append(f, line.repeat(lines));
        String first = tail.poll().text();
        check(first.length() > 1024 * 1024 && first.length() <= LogTail.MAX_READ_BYTES && first.endsWith("\n"), "한 poll 에 한도까지: " + first.length());
        String rest = tail.poll().text();
        check(first.length() + rest.length() == line.length() * lines, "나머지는 다음 poll: " + rest.length());
        check(tail.poll().text().isEmpty(), "다 읽음");
    }

    private static void append(Path f, String s) throws Exception {
        Files.writeString(f, s, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
