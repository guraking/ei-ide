package local.bootservers.ai;

import java.io.File;
import java.util.regex.Pattern;

/** AI 동작의 프롬프트 글과 터미널 명령을 만든다. IntelliJ 클래스 없이 둔다. */
public final class AiText {

    /** Claude Code 명령 이름. */
    static final String CLAUDE = "claude";
    /** PATH 에서 찾는 Claude Code 실행 파일 이름(유닉스 · 윈도우 설치형 · npm). */
    static final String[] CLAUDE_FILES = {"claude", "claude.exe", "claude.cmd"};
    /** 명령줄에 넣는 짧은 지시. 긴 글은 프롬프트 파일에 둔다. */
    static final String COMMAND_INSTRUCTION = "첨부한 파일의 요청대로 해 줘.";
    /** cmd · PowerShell · bash 에서 큰따옴표 안에서도 뜻이 바뀌는 글자. */
    private static final String UNSAFE = "\"%$`!\r\n";
    private static final int MIN_FENCE = 3;

    private AiText() {
    }

    /** 지시, 위치(「경로:줄」, 없으면 null), 코드 · 로그 본문으로 프롬프트 파일 글을 만든다. */
    static String prompt(String instruction, String location, String body) {
        StringBuilder sb = new StringBuilder(instruction).append("\n\n");
        if (location != null) {
            sb.append("위치: ").append(location).append("\n\n");
        }
        String fence = "`".repeat(Math.max(MIN_FENCE, longestBacktickRun(body) + 1));
        sb.append(fence).append('\n').append(body);
        if (!body.endsWith("\n")) {
            sb.append('\n');
        }
        return sb.append(fence).append('\n').toString();
    }

    /** 「경로:시작」 또는 「경로:시작-끝」. 줄 번호는 1 부터. */
    static String location(String path, int startLine, int endLine) {
        return startLine == endLine ? path + ":" + startLine : path + ":" + startLine + "-" + endLine;
    }

    /** 프롬프트 파일을 붙여 Claude Code 를 시작하는 명령. 경로의 \ 는 / 로 바꾼다(bash 큰따옴표에서 \ 가 사라지지 않게). */
    static String command(String promptPath) {
        return CLAUDE + " " + quote(COMMAND_INSTRUCTION + " @" + promptPath.replace('\\', '/'));
    }

    /**
     * cmd · PowerShell · bash 가 같은 한 인자로 받는 큰따옴표 글. 세 셸 모두에서 안전하게 감쌀 수 없는 글자가 있으면
     * IllegalArgumentException.
     */
    static String quote(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (UNSAFE.indexOf(s.charAt(i)) >= 0) {
                throw new IllegalArgumentException("명령줄에 쓸 수 없는 글자가 있습니다: " + s);
            }
        }
        return "\"" + s + "\"";
    }

    /** PATH 글(구분자 separator)의 폴더 가운데 하나에 Claude Code 실행 파일이 있는지. */
    static boolean claudeOnPath(String path, String separator) {
        if (path == null) {
            return false;
        }
        for (String dir : path.split(Pattern.quote(separator))) {
            String d = dir.strip();
            if (d.length() > 1 && d.startsWith("\"") && d.endsWith("\"")) {
                d = d.substring(1, d.length() - 1);
            }
            if (d.isEmpty()) {
                continue;
            }
            for (String name : CLAUDE_FILES) {
                if (new File(d, name).isFile()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int longestBacktickRun(String s) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            run = s.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        return longest;
    }
}
