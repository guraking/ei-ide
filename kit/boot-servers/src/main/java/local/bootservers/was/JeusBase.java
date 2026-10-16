package local.bootservers.was;

import com.intellij.util.execution.ParametersListUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * JEUS(7 · 8 · 21) 를 명령 틀로 띄우고 · 끄고 · 앱을 올린다. 회사마다 도메인 구성과 스크립트가 다르므로
 * 시작 · 중지 · 배포는 사용자가 고칠 수 있는 명령 틀로 두고, 비워 두면 아래 기본 틀을 쓴다.
 * 기본 틀은 JEUS 8 기준이며, 현장(판 · 도메인 구성)에 따라 고쳐야 할 수 있다.
 * <p>
 * 틀의 자리 표시: {home} 설치 폴더 · {domain} 도메인 · {server} 서버 이름 · {user} 관리자 계정 · {password} 비밀번호 ·
 * {host} localhost · {port} 관리(기본) 포트 · {path} 펼친 war 폴더 · {ctx} 컨텍스트 경로 · {name} 앱 id ·
 * {vmoptions} VM 옵션(디버그면 jdwp 포함). {path} · {ctx} · {name} 은 배포 명령에서만 채운다.
 * <p>
 * 앱은 서버별 실행 폴더의 apps/이름.war 로 펼쳐 복사하고, 서버가 뜬 뒤 배포 명령으로 올린다.
 * 이 apps 폴더는 플러그인만 쓰므로 목록에서 빠진 앱 폴더는 지운다. 판 판별 · 명령 조립은 IDE 없이 시험할 수 있다.
 */
public final class JeusBase {

    /** 기본 시작 명령(JEUS 8). 윈도우에서는 같은 이름의 .cmd 가 있으면 그것을 쓴다. */
    public static final String DEFAULT_START =
            "{home}/bin/startDomainAdminServer -domain {domain} -server {server} -u {user} -p {password}";
    /** 기본 중지 명령(JEUS 8). {port} 는 관리(기본) 포트. */
    public static final String DEFAULT_STOP = "{home}/bin/stopServer -host {host}:{port} -u {user} -p {password}";
    /** 기본 배포 명령(JEUS 8). 따옴표 안은 jeusadmin 이 받는 명령 하나다. */
    public static final String DEFAULT_DEPLOY = "{home}/bin/jeusadmin -host {host}:{port} -u {user} -p {password} "
            + "\"deploy-application -path {path} -id {name} -servers {server} -cr {ctx}\"";
    /** 화면에 보이는 명령줄에서 비밀번호 대신 쓰는 글자. */
    public static final String MASK = "******";

    // 이 자리만 단독 낱말로 쓰이면 여러 인자로 펼친다(VM 옵션은 여러 개이고, 비어 있으면 인자를 남기지 않는다).
    private static final String VM_OPTIONS_TOKEN = "{vmoptions}";
    private static final String APPS = "apps";

    private JeusBase() {
    }

    /**
     * 예: "JEUS 8.0.0.0". bin/jeusadmin(.cmd) 과 lib/system/jeus.jar 가 있어야 JEUS 설치 폴더로 본다(아니면 null).
     * jeus.jar 의 판 정보를 못 읽으면 "JEUS".
     */
    public static String detectVersion(Path home) {
        Path jar = home.resolve("lib").resolve("system").resolve("jeus.jar");
        boolean admin = Files.isRegularFile(home.resolve("bin").resolve("jeusadmin"))
                || Files.isRegularFile(home.resolve("bin").resolve("jeusadmin.cmd"));
        if (!admin || !Files.isRegularFile(jar)) {
            return null;
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest m = jf.getManifest();
            if (m != null) {
                Attributes a = m.getMainAttributes();
                String v = a.getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                if (v == null) {
                    v = a.getValue(Attributes.Name.SPECIFICATION_VERSION);
                }
                if (v != null && !v.isBlank()) {
                    return "JEUS " + v.strip();
                }
            }
        }
        catch (IOException e) {
            // 판을 못 읽어도 필요한 파일이 있으면 설치 폴더로 본다.
        }
        return "JEUS";
    }

    /** 앱 하나의 펼친 war 폴더. */
    public static Path warDir(Path base, String contextPath) {
        return base.resolve(APPS).resolve(ExplodedSync.warName(contextPath) + ".war");
    }

    /** 앱들을 펼친 war 로 맞추고, 목록에서 빠진 앱 폴더는 지운다. */
    public static void prepare(Path base, List<TomcatBase.App> apps) throws IOException {
        Path dir = Files.createDirectories(base.resolve(APPS));
        Set<Path> keep = new HashSet<>();
        for (TomcatBase.App app : apps) {
            Path war = warDir(base, app.contextPath());
            keep.add(war);
            ExplodedSync.sync(app, war);
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                if (!keep.contains(p)) {
                    try (var walk = Files.walk(p)) {
                        for (Path x : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                            Files.delete(x);
                        }
                    }
                }
            }
        }
    }

    /** 실행 중에 바뀐 파일을 다시 맞춘다. @return 바뀐 것이 있으면 true(그때만 다시 배포한다) */
    public static boolean publish(Path base, TomcatBase.App app) throws IOException {
        return ExplodedSync.sync(app, warDir(base, app.contextPath()));
    }

    /**
     * 명령 틀을 인자 목록으로 만든다. 먼저 따옴표를 지켜 낱말로 나눈 뒤 낱말마다 자리 표시를 채우므로,
     * 값에 띄어쓰기가 있어도(예: 설치 폴더) 인자가 갈라지지 않는다. values 에 없는 자리 표시는 그대로 둔다.
     * windows 이고 첫 낱말에 「.cmd」 를 붙인 파일이 있으면 그것을 실행 파일로 쓴다.
     */
    public static List<String> command(String template, Map<String, String> values, boolean windows) {
        List<String> argv = new ArrayList<>();
        for (String token : ParametersListUtil.parse(template)) {
            if (token.equals(VM_OPTIONS_TOKEN)) {
                argv.addAll(ParametersListUtil.parse(values.getOrDefault("vmoptions", "")));
                continue;
            }
            String filled = token;
            for (Map.Entry<String, String> e : values.entrySet()) {
                filled = filled.replace("{" + e.getKey() + "}", e.getValue());
            }
            argv.add(filled);
        }
        if (windows && !argv.isEmpty() && !argv.get(0).endsWith(".cmd")) {
            try {
                if (Files.isRegularFile(Path.of(argv.get(0) + ".cmd"))) {
                    argv.set(0, argv.get(0) + ".cmd");
                }
            }
            catch (InvalidPathException e) {
                // 경로가 아닌 첫 낱말(예: cmd)은 그대로 실행한다.
            }
        }
        return argv;
    }

    /** 실행 창 · 기록에 보일 글에서 비밀번호를 가린다. */
    public static String mask(String text, String password) {
        return password == null || password.isEmpty() ? text : text.replace(password, MASK);
    }
}
