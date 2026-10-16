package local.bootservers.was;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 앱 하나를 펼친 war 폴더로 맞춘다(이클립스 Publish 와 같은 방식).
 * 웹 폴더 → 폴더 바로 아래, 컴파일 결과 → WEB-INF/classes, jar → WEB-INF/lib/이름.jar.
 * 크기와 수정 시각이 같은 파일은 다시 복사하지 않고, 원본에 없는 파일은 지운다.
 * 같은 자리에 둘 이상이 오면 먼저 나온 것을 쓴다(웹 폴더 → 컴파일 결과 → jar 순).
 */
public final class ExplodedSync {

    private ExplodedSync() {
    }

    /** @return 무엇이든 복사하거나 지웠으면 true */
    public static boolean sync(TomcatBase.App app, Path warDir) throws IOException {
        return sync(app, warDir, Set.of());
    }

    /** keep: 원본에 없어도 지우지 않을 파일(warDir 기준 상대 경로, 예: "WEB-INF/REDEPLOY"). WAS 가 쓰는 표시 파일용. */
    public static boolean sync(TomcatBase.App app, Path warDir, Set<String> keep) throws IOException {
        Map<Path, Path> wanted = new HashMap<>();
        collect(app.docBase(), warDir, wanted);
        for (Path dir : app.classDirs()) {
            collect(dir, warDir.resolve("WEB-INF").resolve("classes"), wanted);
        }
        for (Path jar : app.jars()) {
            wanted.putIfAbsent(warDir.resolve("WEB-INF").resolve("lib").resolve(jar.getFileName().toString()), jar);
        }

        boolean changed = false;
        for (Map.Entry<Path, Path> e : wanted.entrySet()) {
            Path target = e.getKey();
            Path source = e.getValue();
            if (Files.isRegularFile(target)
                    && Files.size(target) == Files.size(source)
                    && Files.getLastModifiedTime(target).equals(Files.getLastModifiedTime(source))) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
            changed = true;
        }
        if (Files.isDirectory(warDir)) {
            List<Path> existing;
            try (Stream<Path> walk = Files.walk(warDir)) {
                existing = walk.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path p : existing) {
                if (Files.isRegularFile(p) && !wanted.containsKey(p) && !keep.contains(warDir.relativize(p).toString().replace('\\', '/'))) {
                    Files.delete(p);
                    changed = true;
                }
                else if (Files.isDirectory(p) && !p.equals(warDir) && isEmpty(p)) {
                    Files.delete(p);
                }
            }
        }
        else {
            Files.createDirectories(warDir);
        }
        return changed;
    }

    private static void collect(Path from, Path to, Map<Path, Path> wanted) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                wanted.putIfAbsent(to.resolve(from.relativize(p).toString()), p);
            }
        }
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }

    /** "/" → ROOT, "/a/b" → a#b (Tomcat 규칙과 같게 맞춘다). WildFly · WebLogic 은 war 이름이 곧 컨텍스트 경로다. */
    public static String warName(String contextPath) {
        return TomcatBase.contextFileName(contextPath);
    }

    /** 펼친 war 폴더 안의 파일 시각을 지금으로 바꾸지 않고, 표시 파일의 시각만 새로 한다. */
    public static void touch(Path marker) throws IOException {
        if (!Files.exists(marker)) {
            Files.createFile(marker);
        }
        Files.setLastModifiedTime(marker, FileTime.fromMillis(System.currentTimeMillis()));
    }
}
