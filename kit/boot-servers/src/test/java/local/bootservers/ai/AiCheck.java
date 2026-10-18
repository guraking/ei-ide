package local.bootservers.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** AiText 자체 점검. 실패하면 예외로 끝난다. */
public final class AiCheck {

    public static void main(String[] args) throws IOException {
        prompt();
        command();
        path();
        System.out.println("AiCheck 통과");
    }

    private static void prompt() {
        eq("지시\n\n위치: src/A.java:3-5\n\n```\nint a;\n```\n", AiText.prompt("지시", "src/A.java:3-5", "int a;"), "기본 모양");
        eq("지시\n\n```\nlog\n```\n", AiText.prompt("지시", null, "log\n"), "위치 없음 · 끝 줄바꿈 하나");
        eq("지시\n\n````\na ``` b\n````\n", AiText.prompt("지시", null, "a ``` b"), "본문의 ``` 보다 긴 울타리");
        eq("a/B.java:7", AiText.location("a/B.java", 7, 7), "한 줄");
        eq("a/B.java:7-9", AiText.location("a/B.java", 7, 9), "여러 줄");
    }

    private static void command() {
        eq("claude \"" + AiText.COMMAND_INSTRUCTION + " @C:/Users/홍 길동/AppData/Local/tmp/ei-ai-1.md\"",
                AiText.command("C:\\Users\\홍 길동\\AppData\\Local\\tmp\\ei-ai-1.md"), "윈도우 경로는 / 로 · 빈칸은 따옴표 안");
        eq("claude \"" + AiText.COMMAND_INSTRUCTION + " @/tmp/ei-ai-2.md\"", AiText.command("/tmp/ei-ai-2.md"), "유닉스 경로");
        eq("\"a & b | c ^ d < e > f ( g ) ; h ' i\"", AiText.quote("a & b | c ^ d < e > f ( g ) ; h ' i"), "cmd · PowerShell 특수 글자는 따옴표 안에서 그대로");
        for (String bad : new String[]{"a\"b", "%PATH%", "$HOME", "a`b", "a!b", "a\nb", "a\rb"}) {
            throws_(() -> AiText.quote(bad), "막을 글자: " + bad);
        }
    }

    private static void path() throws IOException {
        Path dir = Files.createTempDirectory("ai-check");
        Path empty = Files.createTempDirectory("ai-check-empty");
        try {
            Files.createFile(dir.resolve("claude.cmd"));
            yes(AiText.claudeOnPath(empty + ";\"" + dir + "\";", ";"), "따옴표 · 빈 칸 섞인 PATH 에서 claude.cmd");
            yes(!AiText.claudeOnPath(empty + ":" + empty, ":"), "없음");
            yes(!AiText.claudeOnPath(null, ":"), "PATH 없음");
            Files.createDirectory(empty.resolve("claude"));
            yes(!AiText.claudeOnPath(empty.toString(), ":"), "같은 이름 폴더는 아님");
        } finally {
            Files.delete(dir.resolve("claude.cmd"));
            Files.delete(dir);
            Files.delete(empty.resolve("claude"));
            Files.delete(empty);
        }
    }

    private static void throws_(Runnable r, String name) {
        try {
            r.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(name + ": 예외 없음");
    }

    private static void eq(Object expected, Object actual, String name) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(name + ": 기대 " + expected + ", 실제 " + actual);
        }
    }

    private static void yes(boolean ok, String name) {
        if (!ok) {
            throw new AssertionError(name);
        }
    }
}
