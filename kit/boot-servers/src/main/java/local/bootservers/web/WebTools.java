package local.bootservers.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 웹 언어 서버(vtsls · Vue)용 경로 · 명령 · 파일 판별(IntelliJ 클래스 없음).
 * 도구는 모두 사용자 폴더의 .ei-web 아래: node(Node.js), servers(npm 으로 받은 언어 서버).
 */
final class WebTools {

    static final String BASE_DIR = ".ei-web";
    static final String NODE_DIR = "node";
    static final String SERVERS_DIR = "servers";
    static final String PACKAGE_JSON = "package.json";
    static final String VUE_EXTENSION = "vue";
    static final String TSSERVER_REQUEST_COMMAND = "typescript.tsserverRequest";
    static final String VUE_PLUGIN = "@vue/typescript-plugin";

    /** vtsls 가 맡는 확장자. */
    static final Set<String> SCRIPT_EXTENSIONS = Set.of("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts");
    /** LSP languageId. 플랫폼 기본 표에 없는 mts · cts · vue 때문에 모두 직접 정한다. */
    private static final Map<String, String> LANGUAGE_IDS = Map.of(
            "js", "javascript", "mjs", "javascript", "cjs", "javascript", "jsx", "javascriptreact",
            "ts", "typescript", "mts", "typescript", "cts", "typescript", "tsx", "typescriptreact",
            VUE_EXTENSION, "vue");

    /**
     * npm 으로 받는 패키지. typescript 7 부터는 Go 로 옮겨 가 tsserver.js 와 JS API 가 없어
     * Vue 서버(require('typescript'))와 tsdk 로 쓸 수 없으므로 5 판으로 고정한다.
     */
    static final List<String> SERVER_PACKAGES = List.of(
            "@vtsls/language-server", "@vue/language-server", VUE_PLUGIN, "typescript@5");

    private static final String MODULES = "node_modules";
    private static final String VTSLS_JS = MODULES + "/@vtsls/language-server/bin/vtsls.js";
    private static final String VUE_SERVER_JS = MODULES + "/@vue/language-server/bin/vue-language-server.js";
    private static final String TS_LIB = MODULES + "/typescript/lib";
    private static final String TSSERVER_JS = "tsserver.js";
    private static final String NPM_CLI = "node_modules/npm/bin/npm-cli.js";
    /** 유닉스 설치(<prefix>/bin/node)에서 node 폴더 기준 npm-cli.js 위치. */
    private static final String UNIX_NPM_CLI = "../lib/" + NPM_CLI;
    private static final String STDIO = "--stdio";
    private static final String TSDK_ARG = "--tsdk=";
    private static final String[] DEPENDENCY_KEYS = {"dependencies", "devDependencies", "peerDependencies"};
    private static final String VUE_PACKAGE = "vue";

    private WebTools() {
    }

    static Path baseDir(String userHome) {
        return Path.of(userHome, BASE_DIR);
    }

    static boolean isScript(String extension) {
        return extension != null && SCRIPT_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    static boolean isVue(String extension) {
        return VUE_EXTENSION.equalsIgnoreCase(extension);
    }

    /** 맡지 않는 확장자는 null. */
    static String languageId(String extension) {
        return extension == null ? null : LANGUAGE_IDS.get(extension.toLowerCase(Locale.ROOT));
    }

    /** 받은 Node: 윈도우 zip 은 폴더 바로 아래, 유닉스 묶음은 bin 아래. */
    static Path bundledNode(Path base, boolean windows) {
        Path dir = base.resolve(NODE_DIR);
        return windows ? dir.resolve("node.exe") : dir.resolve("bin").resolve("node");
    }

    /** 받은 Node → PATH 의 node 순. 없으면 null. */
    static Path findNode(Path base, String pathEnv, boolean windows) {
        Path bundled = bundledNode(base, windows);
        if (Files.isRegularFile(bundled)) {
            return bundled;
        }
        if (pathEnv == null) {
            return null;
        }
        String exe = windows ? "node.exe" : "node";
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate;
            try {
                candidate = Path.of(dir.strip().replace("\"", "")).resolve(exe);
            } catch (RuntimeException e) {
                continue;
            }
            if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * npm 실행 명령의 앞부분. node 옆(실제 경로 기준)의 npm-cli.js 를 node 로 돌리고, 없으면 PATH 의 npm.
     */
    static List<String> npmCommand(Path node, boolean windows) {
        Path real;
        try {
            real = node.toRealPath();
        } catch (IOException e) {
            real = node.toAbsolutePath();
        }
        Path dir = real.getParent();
        for (String rel : new String[]{NPM_CLI, UNIX_NPM_CLI}) {
            Path cli = dir.resolve(rel).normalize();
            if (Files.isRegularFile(cli)) {
                return List.of(node.toString(), cli.toString());
            }
        }
        return List.of(windows ? "npm.cmd" : "npm");
    }

    /** 언어 서버 설치 명령: npm install --prefix <base>/servers ... */
    static List<String> installCommand(List<String> npm, Path base) {
        List<String> cmd = new ArrayList<>(npm);
        cmd.add("install");
        cmd.add("--prefix");
        cmd.add(base.resolve(SERVERS_DIR).toString());
        cmd.add("--no-fund");
        cmd.add("--no-audit");
        cmd.addAll(SERVER_PACKAGES);
        return cmd;
    }

    static Path serversDir(Path base) {
        return base.resolve(SERVERS_DIR);
    }

    /** vtsls · Vue 서버 · Vue 플러그인 · typescript 가 모두 있어야 설치된 것으로 본다. */
    static boolean serversInstalled(Path base) {
        Path servers = serversDir(base);
        return Files.isRegularFile(servers.resolve(VTSLS_JS))
                && Files.isRegularFile(servers.resolve(VUE_SERVER_JS))
                && Files.isDirectory(servers.resolve(MODULES).resolve(VUE_PLUGIN))
                && Files.isRegularFile(servers.resolve(TS_LIB).resolve(TSSERVER_JS));
    }

    static List<String> vtslsCommand(Path node, Path base) {
        return List.of(node.toString(), serversDir(base).resolve(VTSLS_JS).toString(), STDIO);
    }

    static List<String> vueCommand(Path node, Path base, Path tsdk) {
        return List.of(node.toString(), serversDir(base).resolve(VUE_SERVER_JS).toString(), STDIO, TSDK_ARG + tsdk);
    }

    /** 프로젝트의 node_modules/typescript/lib(tsserver.js 가 있는 5 판 이하) → servers 의 것. */
    static Path tsdk(Path projectRoot, Path base) {
        if (projectRoot != null) {
            Path own = projectRoot.resolve(TS_LIB);
            if (Files.isRegularFile(own.resolve(TSSERVER_JS))) {
                return own;
            }
        }
        return serversDir(base).resolve(TS_LIB);
    }

    /**
     * vtsls 설정 전체(workspace/configuration 의 빈 section 응답).
     * globalTsdk 로 고른 typescript 를 기본 판으로 쓰게 하고, Vue 플러그인을 tsserver 에 싣는다.
     * location 은 플러그인을 node_modules 아래에서 찾을 폴더.
     */
    static JsonObject vtslsSettings(Path tsdk, Path base) {
        JsonObject plugin = new JsonObject();
        plugin.addProperty("name", VUE_PLUGIN);
        plugin.addProperty("location", serversDir(base).toString());
        JsonArray languages = new JsonArray();
        languages.add(VUE_EXTENSION);
        plugin.add("languages", languages);
        plugin.addProperty("configNamespace", "typescript");
        plugin.addProperty("enableForWorkspaceTypeScriptVersions", true);
        JsonArray plugins = new JsonArray();
        plugins.add(plugin);

        JsonObject tsserver = new JsonObject();
        tsserver.add("globalPlugins", plugins);
        JsonObject typescript = new JsonObject();
        typescript.addProperty("globalTsdk", tsdk.toString());
        JsonObject vtsls = new JsonObject();
        vtsls.add("tsserver", tsserver);
        vtsls.add("typescript", typescript);
        JsonObject root = new JsonObject();
        root.add("vtsls", vtsls);
        return root;
    }

    /**
     * 파일 폴더에서 프로젝트 뿌리까지 올라가며 처음 만나는 package.json, 그리고 뿌리의 package.json 중
     * 하나라도 vue 를 의존하면 true.
     */
    static boolean usesVue(Path fileDir, Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path dir = fileDir.toAbsolutePath().normalize();
        if (!dir.startsWith(root)) {
            return dependsOnVue(root.resolve(PACKAGE_JSON));
        }
        while (dir != null && dir.startsWith(root)) {
            Path pkg = dir.resolve(PACKAGE_JSON);
            if (Files.isRegularFile(pkg)) {
                return dependsOnVue(pkg) || !dir.equals(root) && dependsOnVue(root.resolve(PACKAGE_JSON));
            }
            dir = dir.getParent();
        }
        return false;
    }

    /** 읽을 수 없거나 JSON 이 아니면 false. */
    static boolean dependsOnVue(Path packageJson) {
        if (!Files.isRegularFile(packageJson)) {
            return false;
        }
        try {
            return dependsOnVue(Files.readString(packageJson, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    static boolean dependsOnVue(String packageJsonText) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(packageJsonText);
        } catch (JsonParseException e) {
            return false;
        }
        if (!parsed.isJsonObject()) {
            return false;
        }
        for (String key : DEPENDENCY_KEYS) {
            JsonElement deps = parsed.getAsJsonObject().get(key);
            if (deps != null && deps.isJsonObject() && deps.getAsJsonObject().has(VUE_PACKAGE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vue 서버의 tsserver/request 알림 매개변수에서 [id, 명령, 인자]. 모양이 다르면 null.
     * 서버는 [[id, 명령, 인자]] 로 보내지만, 한 겹 벗겨진 [id, 명령, 인자] 도 받는다.
     */
    static JsonArray tsserverRequest(JsonElement params) {
        if (params == null || !params.isJsonArray()) {
            return null;
        }
        JsonArray array = params.getAsJsonArray();
        if (array.size() == 1 && array.get(0).isJsonArray()) {
            array = array.get(0).getAsJsonArray();
        }
        if (array.size() < 2 || !array.get(0).isJsonPrimitive() || !array.get(1).isJsonPrimitive()) {
            return null;
        }
        return array;
    }

    /**
     * Vue 서버로 돌려줄 tsserver/response 매개변수 [[id, body]].
     * vtsls 의 typescript.tsserverRequest 결과에서 body 만 쓰고, 실패 · 빈 결과도 null body 로 꼭 돌려준다(Vue 서버가 기다리며 쌓이지 않게).
     */
    static JsonArray tsserverResponse(JsonElement id, JsonElement result) {
        JsonElement body = JsonNull.INSTANCE;
        if (result != null && result.isJsonObject() && result.getAsJsonObject().has("body")) {
            body = result.getAsJsonObject().get("body");
        }
        JsonArray pair = new JsonArray();
        pair.add(id);
        pair.add(body);
        JsonArray params = new JsonArray();
        params.add(pair);
        return params;
    }
}
