package local.bootservers.was;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

/** WasLogFiles 자체 점검. 실패하면 예외로 끝난다. */
public final class WasLogFilesCheck {

    public static void main(String[] args) throws Exception {
        names();
        roots();
        find();
        folderLabel();
        newerDated();
        System.out.println("WasLogFilesCheck 통과");
    }

    private static void names() {
        for (String yes : List.of("catalina.2026-10-20.log", "server.log", "server.log.2026-10-19", "AdminServer.log00001",
                "AdminServer.out", "localhost_access_log.2026-10-20.txt", "JeusServer.log", "ACCESS.LOG")) {
            check(WasLogFiles.isLogName(yes), "로그 이름: " + yes);
        }
        for (String no : List.of("server.xml", "readme.txt", "catalina.policy", "logging.properties", "log")) {
            check(!WasLogFiles.isLogName(no), "로그 아님: " + no);
        }
    }

    private static void roots() {
        Path run = Path.of("run");
        Path home = Path.of("home");
        check(WasLogFiles.roots(WasSettings.Kind.TOMCAT, run, home, "d", "s").equals(List.of(run.resolve("logs"))), "Tomcat");
        check(WasLogFiles.roots(WasSettings.Kind.WILDFLY, run, home, "d", "s").equals(List.of(run.resolve("log"))), "WildFly");
        check(WasLogFiles.roots(WasSettings.Kind.WEBLOGIC, run, home, "d", "s").equals(List.of(home.resolve("servers"))), "WebLogic");
        check(WasLogFiles.roots(WasSettings.Kind.JEUS, run, home, "dom", "srv")
                .equals(List.of(home.resolve("domains/dom/servers/srv/logs"))), "JEUS");
    }

    private static void find() throws Exception {
        Path dir = Files.createTempDirectory("waslog");
        Path logs = Files.createDirectories(dir.resolve("servers/AdminServer/logs"));
        Path older = Files.writeString(logs.resolve("AdminServer.log00001"), "a");
        Path newer = Files.writeString(logs.resolve("AdminServer.log"), "b");
        Files.writeString(logs.resolve("notes.xml"), "c");
        Path deep = Files.createDirectories(logs.resolve("deeper"));
        Files.writeString(deep.resolve("too-deep.log"), "d");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000_000));
        try {
            List<Path> found = WasLogFiles.find(List.of(dir.resolve("servers"), dir.resolve("없는 폴더")));
            check(found.equals(List.of(newer, older)), "최근 순, 깊이 한도, 없는 폴더 건너뜀: " + found);
            check(WasLogFiles.find(List.of(dir.resolve("없음"))).isEmpty(), "없는 폴더뿐이면 빈 목록");
        }
        finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static void folderLabel() {
        Path root = Path.of("home", "servers");
        List<Path> roots = List.of(root);
        check(WasLogFiles.folderLabel(roots, root.resolve("AdminServer/logs/AdminServer.log")).equals(Path.of("AdminServer", "logs").toString()),
                "root 기준 폴더");
        check(WasLogFiles.folderLabel(roots, root.resolve("a.log")).isEmpty(), "root 바로 아래");
        Path other = Path.of("elsewhere", "x.log");
        check(WasLogFiles.folderLabel(roots, other).equals(other.getParent().toString()), "root 밖은 전체 경로");
    }

    private static void newerDated() throws Exception {
        Path dir = Files.createTempDirectory("waslogdate");
        try {
            Path today = Files.writeString(dir.resolve("catalina.2026-10-20.log"), "a");
            check(WasLogFiles.newerDated(today) == null, "새 파일 없음");
            Files.writeString(dir.resolve("catalina.2026-10-19.log"), "old");
            Files.writeString(dir.resolve("localhost.2026-10-22.log"), "다른 앞");
            Files.writeString(dir.resolve("catalina.2026-10-23.txt"), "다른 꼬리");
            check(WasLogFiles.newerDated(today) == null, "앞 · 꼬리가 다르거나 더 이른 것은 무시");
            Files.writeString(dir.resolve("catalina.2026-10-21.log"), "b");
            Path latest = Files.writeString(dir.resolve("catalina.2026-11-02.log"), "c");
            check(latest.equals(WasLogFiles.newerDated(today)), "가장 늦은 날짜");
            check(WasLogFiles.newerDated(Files.writeString(dir.resolve("server.log.2026-10-19"), "w")) == null, "날짜가 끝인 이름은 대상 아님");
            check(WasLogFiles.newerDated(dir.resolve("catalina.out")) == null, "날짜 없는 이름");
        }
        finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
