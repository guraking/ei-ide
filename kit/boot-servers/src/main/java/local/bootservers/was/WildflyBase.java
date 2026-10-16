package local.bootservers.was;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Manifest;

/**
 * JBoss EAP · WildFly 를 standalone 으로, 서버마다 따로 쓰는 실행 폴더(jboss.server.base.dir)로 띄운다.
 * 설정은 설치 폴더의 standalone/configuration 을 복사해 쓰고, 앱은 deployments 아래 펼친 war 폴더와
 * 「이름.war.dodeploy」 표시 파일로 올린다(배포 감시기가 보고 올린다). IDE 와 무관한 순수 자바.
 */
public final class WildflyBase {

    private WildflyBase() {
    }

    /** 예: "WildFly Full 26.1.3.Final", "JBoss EAP 7.4.0.GA". JBoss · WildFly 설치 폴더가 아니면 null. */
    public static String detectVersion(Path home) {
        if (!Files.isRegularFile(home.resolve("jboss-modules.jar"))) {
            return null;
        }
        String slot = "main";
        Path productConf = home.resolve("bin").resolve("product.conf");
        try {
            if (Files.isRegularFile(productConf)) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(productConf)) {
                    p.load(in);
                }
                slot = p.getProperty("slot", "main").strip();
            }
            Path mf = home.resolve("modules/system/layers/base/org/jboss/as/product").resolve(slot).resolve("dir/META-INF/MANIFEST.MF");
            if (Files.isRegularFile(mf)) {
                try (InputStream in = Files.newInputStream(mf)) {
                    Manifest m = new Manifest(in);
                    String name = m.getMainAttributes().getValue("JBoss-Product-Release-Name");
                    String version = m.getMainAttributes().getValue("JBoss-Product-Release-Version");
                    if (name != null && version != null) {
                        return name + " " + version;
                    }
                }
            }
        }
        catch (IOException e) {
            // 판을 못 읽어도 jboss-modules.jar 가 있으면 설치 폴더로 본다.
        }
        return "JBoss / WildFly";
    }

    /**
     * 실행 폴더를 꾸미고 앱을 펼친 war 로 맞춘다. 목록에서 빠진 앱은 지운다.
     * 설정 파일은 처음 한 번만 복사한다(WildFly 가 실행 중에 standalone.xml 을 고쳐 쓰므로 그 내용을 지키기 위해).
     */
    public static void prepare(Path home, Path base, List<TomcatBase.App> apps) throws IOException {
        Path conf = base.resolve("configuration");
        if (!Files.isDirectory(conf)) {
            Files.createDirectories(conf);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(home.resolve("standalone").resolve("configuration"), Files::isRegularFile)) {
                for (Path f : files) {
                    Files.copy(f, conf.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path deployments = Files.createDirectories(base.resolve("deployments"));
        Set<String> keep = new HashSet<>();
        for (TomcatBase.App app : apps) {
            String war = ExplodedSync.warName(app.contextPath()) + ".war";
            keep.add(war);
            ExplodedSync.sync(app, deployments.resolve(war));
            // 시작할 때마다 다시 올리도록 표시한다. 실패 표시(.failed)가 남아 있으면 감시기가 다시 올리지 않으므로 지운다.
            Files.deleteIfExists(deployments.resolve(war + ".failed"));
            ExplodedSync.touch(deployments.resolve(war + ".dodeploy"));
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(deployments)) {
            for (Path p : entries) {
                String name = p.getFileName().toString();
                String war = name.endsWith(".war") ? name : name.contains(".war.") ? name.substring(0, name.indexOf(".war.") + 4) : null;
                if (war != null && !keep.contains(war)) {
                    deleteTree(p);
                }
            }
        }
    }

    /** 실행 중에 바뀐 파일을 다시 맞추고, 바뀐 것이 있으면 그 앱만 다시 올린다. */
    public static boolean publish(Path base, TomcatBase.App app) throws IOException {
        Path deployments = base.resolve("deployments");
        String war = ExplodedSync.warName(app.contextPath()) + ".war";
        boolean changed = ExplodedSync.sync(app, deployments.resolve(war));
        if (changed) {
            ExplodedSync.touch(deployments.resolve(war + ".dodeploy"));
        }
        return changed;
    }

    private static void deleteTree(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (var walk = Files.walk(p)) {
                for (Path x : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(x);
                }
            }
        }
        else {
            Files.deleteIfExists(p);
        }
    }
}
