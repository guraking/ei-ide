package local.bootservers.was;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebLogic 개발 모드 도메인을 그대로 띄우고, 도메인의 autodeploy 폴더에 펼친 war 로 올린다.
 * 개발 모드 관리 서버는 autodeploy 를 주기적으로 보고 올린다. 펼친 앱을 다시 올리려면
 * 앱 안의 WEB-INF/REDEPLOY 파일 시각을 새로 한다(오라클 문서의 autodeploy 규칙).
 * 실행 폴더를 따로 만들지 않으므로 도메인 폴더 자체가 「설치 폴더」 다. IDE 와 무관한 순수 자바.
 */
public final class WeblogicBase {

    private static final Pattern DOMAIN_VERSION = Pattern.compile("<(?:\\w+:)?domain-version>([^<]+)</");
    // 이 플러그인이 올린 앱만 지우기 위한 표시. 사용자가 손으로 넣은 autodeploy 앱은 건드리지 않는다.
    private static final String OWNED_MARK = "WEB-INF/.kinggura";

    private WeblogicBase() {
    }

    /** 예: "WebLogic 14.1.1.0.0 도메인". 도메인 폴더(config/config.xml · bin/startWebLogic)가 아니면 null. */
    public static String detectVersion(Path domain) {
        Path config = domain.resolve("config").resolve("config.xml");
        if (!Files.isRegularFile(config) || !Files.isDirectory(domain.resolve("bin"))
                || !(Files.isRegularFile(domain.resolve("bin/startWebLogic.cmd")) || Files.isRegularFile(domain.resolve("bin/startWebLogic.sh")))) {
            return null;
        }
        try {
            Matcher m = DOMAIN_VERSION.matcher(Files.readString(config, StandardCharsets.UTF_8));
            return m.find() ? "WebLogic " + m.group(1).strip() + " 도메인" : "WebLogic 도메인";
        }
        catch (IOException e) {
            return "WebLogic 도메인";
        }
    }

    /** autodeploy 에 앱을 맞추고 다시 올리도록 표시한다. 이 플러그인이 올렸다가 목록에서 빠진 앱은 지운다. */
    public static void prepare(Path domain, List<TomcatBase.App> apps) throws IOException {
        Path autodeploy = Files.createDirectories(domain.resolve("autodeploy"));
        Set<String> keep = new HashSet<>();
        for (TomcatBase.App app : apps) {
            String war = ExplodedSync.warName(app.contextPath()) + ".war";
            keep.add(war);
            syncAndMark(autodeploy.resolve(war), app);
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(autodeploy, Files::isDirectory)) {
            for (Path p : entries) {
                if (!keep.contains(p.getFileName().toString()) && Files.exists(p.resolve(OWNED_MARK))) {
                    try (var walk = Files.walk(p)) {
                        for (Path x : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                            Files.delete(x);
                        }
                    }
                }
            }
        }
    }

    /** 실행 중에 바뀐 파일을 다시 맞추고, 바뀐 것이 있으면 그 앱만 다시 올린다. */
    public static boolean publish(Path domain, TomcatBase.App app) throws IOException {
        return syncAndMark(domain.resolve("autodeploy").resolve(ExplodedSync.warName(app.contextPath()) + ".war"), app);
    }

    private static boolean syncAndMark(Path warDir, TomcatBase.App app) throws IOException {
        boolean changed = ExplodedSync.sync(app, warDir, Set.of(OWNED_MARK, "WEB-INF/REDEPLOY"));
        Path owned = warDir.resolve(OWNED_MARK);
        if (!Files.exists(owned)) {
            Files.createDirectories(owned.getParent());
            Files.createFile(owned);
        }
        if (changed) {
            ExplodedSync.touch(warDir.resolve("WEB-INF").resolve("REDEPLOY"));
        }
        return changed;
    }
}
