package local.bootservers.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** WebTools · NodeDist 자체 점검. 실패하면 예외로 끝난다. */
public final class WebCheck {

    public static void main(String[] args) throws IOException {
        Path tmp = Files.createTempDirectory("webcheck");
        try {
            files();
            node(tmp);
            servers(tmp);
            vue(tmp);
            bridge();
            dist(tmp);
        } finally {
            deleteTree(tmp);
        }
        System.out.println("WebCheck 통과");
    }

    private static void files() {
        for (String ext : new String[]{"js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts", "TS"}) {
            yes(WebTools.isScript(ext), "스크립트 " + ext);
        }
        yes(!WebTools.isScript("vue") && !WebTools.isScript("json") && !WebTools.isScript(null), "스크립트 아님");
        yes(WebTools.isVue("vue") && !WebTools.isVue("js"), "vue");
        eq("typescript", WebTools.languageId("mts"), "mts");
        eq("typescript", WebTools.languageId("cts"), "cts");
        eq("javascriptreact", WebTools.languageId("jsx"), "jsx");
        eq("typescriptreact", WebTools.languageId("tsx"), "tsx");
        eq("javascript", WebTools.languageId("cjs"), "cjs");
        eq("vue", WebTools.languageId("vue"), "vue id");
        eq(null, WebTools.languageId("java"), "모름");
    }

    private static void node(Path tmp) throws IOException {
        Path base = WebTools.baseDir(tmp.toString());
        eq(tmp.resolve(".ei-web"), base, "기준 폴더");
        Path pathDir = Files.createDirectories(tmp.resolve("bin"));
        eq(null, WebTools.findNode(base, pathDir.toString(), true), "어디에도 없음");
        Path pathNode = Files.createFile(pathDir.resolve("node.exe"));
        eq(pathNode, WebTools.findNode(base, "\"" + pathDir + "\"" + java.io.File.pathSeparator, true), "PATH 의 node(따옴표)");
        Path bundled = WebTools.bundledNode(base, true);
        Files.createDirectories(bundled.getParent());
        Files.createFile(bundled);
        eq(bundled, WebTools.findNode(base, pathDir.toString(), true), "받은 node 우선");
        eq(base.resolve("node").resolve("bin").resolve("node"), WebTools.bundledNode(base, false), "유닉스 node");

        eq(List.of("npm.cmd"), WebTools.npmCommand(pathNode, true), "npm-cli.js 없으면 PATH 의 npm");
        Path cli = base.resolve("node/node_modules/npm/bin/npm-cli.js");
        Files.createDirectories(cli.getParent());
        Files.createFile(cli);
        eq(List.of(bundled.toString(), cli.toRealPath().toString()), WebTools.npmCommand(bundled, true), "node 옆 npm-cli.js");
        Path unixCli = tmp.resolve("usr/lib/node_modules/npm/bin/npm-cli.js");
        Files.createDirectories(unixCli.getParent());
        Files.createFile(unixCli);
        Path unixNode = Files.createDirectories(tmp.resolve("usr/bin")).resolve("node");
        Files.createFile(unixNode);
        eq(List.of(unixNode.toString(), unixCli.toRealPath().toString()), WebTools.npmCommand(unixNode, false), "유닉스 npm-cli.js");

        List<String> install = WebTools.installCommand(List.of("node", "cli.js"), base);
        eq(List.of("node", "cli.js", "install", "--prefix", base.resolve("servers").toString(), "--no-fund", "--no-audit",
                "@vtsls/language-server", "@vue/language-server", "@vue/typescript-plugin", "typescript@5"), install, "설치 명령");
    }

    private static void servers(Path tmp) throws IOException {
        Path base = tmp.resolve("srv");
        yes(!WebTools.serversInstalled(base), "서버 없음");
        Path modules = base.resolve("servers/node_modules");
        touch(modules.resolve("@vtsls/language-server/bin/vtsls.js"));
        touch(modules.resolve("@vue/language-server/bin/vue-language-server.js"));
        touch(modules.resolve("typescript/lib/tsserver.js"));
        yes(!WebTools.serversInstalled(base), "플러그인 빠짐");
        Files.createDirectories(modules.resolve("@vue/typescript-plugin"));
        yes(WebTools.serversInstalled(base), "서버 다 있음");

        Path node = Path.of("node");
        eq(List.of("node", modules.resolve("@vtsls/language-server/bin/vtsls.js").toString(), "--stdio"),
                WebTools.vtslsCommand(node, base), "vtsls 명령");
        Path ourTs = modules.resolve("typescript/lib");
        Path project = Files.createDirectories(tmp.resolve("proj"));
        eq(ourTs, WebTools.tsdk(project, base), "프로젝트 typescript 없음");
        eq(ourTs, WebTools.tsdk(null, base), "프로젝트 뿌리 모름");
        Files.createDirectories(project.resolve("node_modules/typescript/lib"));
        eq(ourTs, WebTools.tsdk(project, base), "tsserver.js 없는 typescript(7 판)");
        touch(project.resolve("node_modules/typescript/lib/tsserver.js"));
        Path projectTs = project.resolve("node_modules/typescript/lib");
        eq(projectTs, WebTools.tsdk(project, base), "프로젝트 typescript");
        eq(List.of("node", modules.resolve("@vue/language-server/bin/vue-language-server.js").toString(), "--stdio",
                "--tsdk=" + projectTs), WebTools.vueCommand(node, base, projectTs), "vue 명령");

        JsonObject settings = WebTools.vtslsSettings(projectTs, base);
        JsonObject vtsls = settings.getAsJsonObject("vtsls");
        eq(projectTs.toString(), vtsls.getAsJsonObject("typescript").get("globalTsdk").getAsString(), "globalTsdk");
        JsonObject plugin = vtsls.getAsJsonObject("tsserver").getAsJsonArray("globalPlugins").get(0).getAsJsonObject();
        eq("@vue/typescript-plugin", plugin.get("name").getAsString(), "플러그인 이름");
        eq(base.resolve("servers").toString(), plugin.get("location").getAsString(), "플러그인 위치");
        eq("[\"vue\"]", plugin.get("languages").toString(), "플러그인 언어");
        yes(plugin.get("enableForWorkspaceTypeScriptVersions").getAsBoolean(), "작업공간 판에도 플러그인");
    }

    private static void vue(Path tmp) throws IOException {
        yes(WebTools.dependsOnVue("{\"dependencies\":{\"vue\":\"^3.5.0\"}}"), "dependencies");
        yes(WebTools.dependsOnVue("{\"devDependencies\":{\"vue\":\"3\"}}"), "devDependencies");
        yes(!WebTools.dependsOnVue("{\"dependencies\":{\"vue-router\":\"4\"}}"), "비슷한 이름");
        yes(!WebTools.dependsOnVue("{\"name\":\"vue\"}"), "이름만 vue");
        yes(!WebTools.dependsOnVue("{깨진"), "깨진 JSON");
        yes(!WebTools.dependsOnVue("[]"), "배열");

        Path root = Files.createDirectories(tmp.resolve("mono"));
        Path app = Files.createDirectories(root.resolve("packages/app/src"));
        yes(!WebTools.usesVue(app, root), "package.json 없음");
        Files.writeString(root.resolve("package.json"), "{\"devDependencies\":{\"vue\":\"3\"}}");
        yes(WebTools.usesVue(app, root), "뿌리 package.json");
        Files.writeString(root.resolve("package.json"), "{}");
        Files.writeString(root.resolve("packages/app/package.json"), "{\"dependencies\":{\"vue\":\"3\"}}");
        yes(WebTools.usesVue(app, root), "가까운 package.json");
        Files.writeString(root.resolve("packages/app/package.json"), "{\"dependencies\":{\"react\":\"19\"}}");
        yes(!WebTools.usesVue(app, root), "둘 다 vue 아님");
        yes(!WebTools.usesVue(tmp.resolve("elsewhere"), root), "프로젝트 밖 파일 · 뿌리 vue 아님");
    }

    private static void bridge() {
        JsonArray wrapped = JsonParser.parseString("[[7,\"_vue:projectInfo\",{\"file\":\"/a.vue\"}]]").getAsJsonArray();
        JsonArray request = WebTools.tsserverRequest(wrapped);
        eq(7, request.get(0).getAsInt(), "요청 id");
        eq("_vue:projectInfo", request.get(1).getAsString(), "요청 명령");
        eq("/a.vue", request.get(2).getAsJsonObject().get("file").getAsString(), "요청 인자");
        eq("_vue:x", WebTools.tsserverRequest(JsonParser.parseString("[1,\"_vue:x\"]")).get(1).getAsString(), "한 겹");
        eq(null, WebTools.tsserverRequest(JsonParser.parseString("{\"a\":1}")), "객체");
        eq(null, WebTools.tsserverRequest(JsonParser.parseString("[[1]]")), "모자람");
        eq(null, WebTools.tsserverRequest(null), "없음");

        JsonPrimitive id = new JsonPrimitive(7);
        eq("[[7,{\"configFileName\":\"/t.json\"}]]", WebTools.tsserverResponse(id,
                JsonParser.parseString("{\"seq\":0,\"type\":\"response\",\"body\":{\"configFileName\":\"/t.json\"}}")).toString(), "응답 body");
        eq("[[7,null]]", WebTools.tsserverResponse(id, null).toString(), "실패도 null 로 응답");
        eq("[[7,null]]", WebTools.tsserverResponse(id, JsonParser.parseString("{\"success\":false}")).toString(), "body 없음");
    }

    private static void dist(Path tmp) throws IOException {
        eq("x64", NodeDist.arch("amd64"), "amd64");
        eq("arm64", NodeDist.arch("aarch64"), "aarch64");
        eq(null, NodeDist.arch("x86"), "32비트");
        String index = "[{\"version\":\"v26.1.0\",\"files\":[\"win-x64-zip\"],\"lts\":false},"
                + "{\"version\":\"v24.9.0\",\"files\":[\"win-arm64-zip\"],\"lts\":\"Krypton\"},"
                + "{\"version\":\"v24.8.0\",\"files\":[\"win-x64-zip\",\"win-arm64-zip\"],\"lts\":\"Krypton\"}]";
        eq("v24.8.0", NodeDist.latestLts(index, "x64"), "x64 zip 있는 최신 LTS");
        eq("v24.9.0", NodeDist.latestLts(index, "arm64"), "arm64 최신 LTS");
        eq(null, NodeDist.latestLts("[]", "x64"), "빈 목록");
        eq(null, NodeDist.latestLts("<html>", "x64"), "JSON 아님");
        eq("node-v24.8.0-win-x64.zip", NodeDist.zipName("v24.8.0", "x64"), "zip 이름");
        eq("https://nodejs.org/dist/v24.8.0/node-v24.8.0-win-x64.zip", NodeDist.zipUrl("v24.8.0", "x64"), "zip 주소");
        eq("https://nodejs.org/dist/v24.8.0/SHASUMS256.txt", NodeDist.shasumsUrl("v24.8.0"), "해시 주소");

        String hash = "F9683A19877E12A9B167305C8DB71040D812876359DE21F0D2E97C3F321F80C3";
        String sums = "aaaa  other.zip\n" + hash + "  node-v24.8.0-win-x64.zip\r\n" + "bbbb  win-x64/node.exe\n";
        eq(hash.toLowerCase(), NodeDist.expectedSha256(sums, "node-v24.8.0-win-x64.zip"), "해시 찾기");
        eq(null, NodeDist.expectedSha256(sums, "node-v24.8.0-win-arm64.zip"), "해시 없음");
        eq(null, NodeDist.expectedSha256("zz  node.zip", "node.zip"), "16진수 아님");

        Path abc = tmp.resolve("abc.txt");
        Files.writeString(abc, "abc");
        eq("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", NodeDist.sha256(abc), "SHA256");

        Path zip = tmp.resolve("node.zip");
        writeZip(zip, "node-v24.8.0-win-x64/", null, "node-v24.8.0-win-x64/node.exe", "exe",
                "node-v24.8.0-win-x64/node_modules/npm/bin/npm-cli.js", "cli");
        Path target = tmp.resolve("out");
        NodeDist.unzipFlatten(zip, target);
        eq("exe", Files.readString(target.resolve("node.exe")), "맨 위 폴더 뗌");
        eq("cli", Files.readString(target.resolve("node_modules/npm/bin/npm-cli.js")), "아래 폴더 유지");

        Path evil = tmp.resolve("evil.zip");
        writeZip(evil, "top/../../escape.txt", "x");
        boolean refused = false;
        try {
            NodeDist.unzipFlatten(evil, tmp.resolve("out2"));
        } catch (IOException e) {
            refused = true;
        }
        yes(refused && !Files.exists(tmp.resolve("escape.txt")), "대상 밖 항목 거부");
    }

    /** 이름 · 내용 짝(내용 null 이면 폴더). */
    private static void writeZip(Path zip, String... entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (int i = 0; i < entries.length; i += 2) {
                out.putNextEntry(new ZipEntry(entries[i]));
                if (entries[i + 1] != null) {
                    out.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
    }

    private static void touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream ignored = Files.newOutputStream(file)) {
            // 빈 파일
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
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
