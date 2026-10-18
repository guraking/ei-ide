package local.bootservers.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * nodejs.org/dist 의 윈도우 zip 고르기 · SHA256 확인 · 풀기(IntelliJ 클래스 없음).
 */
final class NodeDist {

    static final String DIST_URL = "https://nodejs.org/dist/";
    static final String INDEX_URL = DIST_URL + "index.json";
    static final String SHASUMS = "SHASUMS256.txt";

    private static final String ZIP_SUFFIX = "-zip";
    private static final int BUFFER = 64 * 1024;

    private NodeDist() {
    }

    /** os.arch 를 dist 의 이름(x64 · arm64)으로. 받을 수 없는 구조면 null. */
    static String arch(String osArch) {
        String a = osArch == null ? "" : osArch.toLowerCase(Locale.ROOT);
        return switch (a) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> null;
        };
    }

    /** index.json(새 판이 앞) 에서 win-<arch>-zip 이 있는 가장 새 LTS 판(v24.1.0 꼴). 없으면 null. */
    static String latestLts(String indexJson, String arch) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(indexJson);
        } catch (JsonParseException e) {
            return null;
        }
        if (!parsed.isJsonArray()) {
            return null;
        }
        String file = "win-" + arch + ZIP_SUFFIX;
        for (JsonElement e : parsed.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonElement lts = e.getAsJsonObject().get("lts");
            JsonElement version = e.getAsJsonObject().get("version");
            JsonElement files = e.getAsJsonObject().get("files");
            boolean isLts = lts != null && lts.isJsonPrimitive() && !(lts.getAsJsonPrimitive().isBoolean() && !lts.getAsBoolean());
            if (isLts && version != null && version.isJsonPrimitive() && files != null && files.isJsonArray()) {
                for (JsonElement f : files.getAsJsonArray()) {
                    if (f.isJsonPrimitive() && file.equals(f.getAsString())) {
                        return version.getAsString();
                    }
                }
            }
        }
        return null;
    }

    static String zipName(String version, String arch) {
        return "node-" + version + "-win-" + arch + ".zip";
    }

    static String zipUrl(String version, String arch) {
        return DIST_URL + version + "/" + zipName(version, arch);
    }

    static String shasumsUrl(String version) {
        return DIST_URL + version + "/" + SHASUMS;
    }

    /** SHASUMS256.txt 에서 파일 이름의 해시(소문자 16진수). 없으면 null. */
    static String expectedSha256(String shasums, String fileName) {
        for (String line : shasums.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length == 2 && parts[1].equals(fileName) && parts[0].matches("[0-9a-fA-F]{64}")) {
                return parts[0].toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * zip 을 target 에 푼다. 모든 항목의 맨 위 폴더(node-v…-win-x64/)는 떼고, target 밖으로 나가는 항목은 거부한다.
     */
    static void unzipFlatten(Path zip, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        Files.createDirectories(root);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                int slash = name.indexOf('/');
                String rest = slash < 0 ? "" : name.substring(slash + 1);
                if (rest.isEmpty()) {
                    continue;
                }
                Path out = root.resolve(rest).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("압축 파일 항목이 대상 폴더 밖을 가리킵니다: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
