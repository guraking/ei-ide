package local.bootservers.was;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * WAS 종류별 로그 폴더와 그 안의 로그 파일 찾기. IntelliJ 에 기대지 않는다.
 * <ul>
 *   <li>Tomcat: 실행 폴더(CATALINA_BASE)/logs — catalina.날짜.log · localhost_access_log.날짜.txt 등</li>
 *   <li>JBoss/WildFly: 실행 폴더(jboss.server.base.dir)/log — server.log · server.log.날짜</li>
 *   <li>WebLogic: 도메인/servers/서버이름/logs — 서버이름.log · 서버이름.log00001 · access.log · *.out</li>
 *   <li>JEUS: 설치 폴더/domains/도메인/servers/서버/logs — JeusServer.log 등</li>
 * </ul>
 */
public final class WasLogFiles {

    // WebLogic 의 servers/서버이름/logs/파일 이 가장 깊다.
    private static final int MAX_DEPTH = 3;
    // 「.log」 · 「.out」(순환 번호 · 날짜 꼬리 포함)과 Tomcat 접근 로그 「_log.날짜.txt」.
    private static final Pattern LOG_NAME = Pattern.compile("(?i).+(\\.log\\d*|\\.out\\d*)(\\.[\\w.-]+)?|.+_log\\..+\\.txt");
    // 날짜가 이름 가운데 든 로그(Tomcat 의 catalina.2026-10-20.log · localhost_access_log.2026-10-20.txt). 1: 앞, 2: 날짜, 3: 꼬리.
    // 날짜가 맨 끝인 server.log.2026-10-19 는 지난 날 파일이고 지금 쓰는 파일은 server.log 이므로 대상이 아니다.
    private static final Pattern DATED_NAME = Pattern.compile("(.+\\.)(\\d{4}-\\d{2}-\\d{2})(\\..+)");

    private WasLogFiles() {
    }

    /** 로그를 찾아볼 폴더들. runDir 은 서버 전용 실행 폴더, home 은 설치 폴더(WebLogic 은 도메인 폴더). */
    public static List<Path> roots(WasSettings.Kind kind, Path runDir, Path home, String jeusDomain, String jeusServer) {
        return switch (kind) {
            case TOMCAT -> List.of(runDir.resolve("logs"));
            case WILDFLY -> List.of(runDir.resolve("log"));
            case WEBLOGIC -> List.of(home.resolve("servers"));
            case JEUS -> List.of(home.resolve("domains").resolve(jeusDomain).resolve("servers").resolve(jeusServer).resolve("logs"));
        };
    }

    public static boolean isLogName(String fileName) {
        return LOG_NAME.matcher(fileName).matches();
    }

    /**
     * 폴더들 아래(MAX_DEPTH 까지)의 로그 파일. 최근에 고친 것부터. 없는 폴더는 건너뛴다.
     *
     * @throws IOException 폴더를 읽지 못한 경우
     */
    public static List<Path> find(List<Path> roots) throws IOException {
        List<Path> found = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(p) && isLogName(p.getFileName().toString())) {
                        found.add(p);
                    }
                }
            }
            catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
        found.sort(Comparator.comparing(WasLogFiles::modified).reversed());
        return found;
    }

    /** file 이 놓인 폴더를 그 file 을 품은 root 기준으로 적은 것. root 바로 아래면 "", 어느 root 아래도 아니면 폴더 전체 경로. */
    public static String folderLabel(List<Path> roots, Path file) {
        Path parent = file.getParent();
        if (parent == null) {
            return "";
        }
        for (Path root : roots) {
            if (parent.startsWith(root)) {
                return root.relativize(parent).toString();
            }
        }
        return parent.toString();
    }

    /**
     * 날짜가 이름 가운데 든 로그(catalina.날짜.log)와 앞 · 꼬리가 같고 날짜가 더 늦은 같은 폴더의 파일 중 가장 늦은 것.
     * 날짜 이름이 아니거나 더 늦은 파일이 없으면 null.
     *
     * @throws IOException 폴더를 읽지 못한 경우
     */
    public static Path newerDated(Path file) throws IOException {
        Matcher m = DATED_NAME.matcher(file.getFileName().toString());
        Path dir = file.getParent();
        if (!m.matches() || dir == null) {
            return null;
        }
        Path newest = null;
        String newestDate = m.group(2);
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(dir)) {
            for (Path p : siblings) {
                Matcher o = DATED_NAME.matcher(p.getFileName().toString());
                // 날짜가 yyyy-MM-dd 이므로 글자 순서가 곧 날짜 순서다.
                if (o.matches() && o.group(1).equals(m.group(1)) && o.group(3).equals(m.group(3))
                        && o.group(2).compareTo(newestDate) > 0) {
                    newest = p;
                    newestDate = o.group(2);
                }
            }
        }
        return newest;
    }

    private static FileTime modified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        }
        catch (IOException e) {
            // 찾은 뒤 지워진 파일은 맨 뒤로 보낸다.
            return FileTime.fromMillis(0);
        }
    }
}
