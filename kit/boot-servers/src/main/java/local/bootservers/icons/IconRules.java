package local.bootservers.icons;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.IconLoader;

import javax.swing.Icon;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * /icons/ei/*.properties 의 「키=아이콘」 줄을 적힌 순서대로 읽는다.
 * 아이콘 값은 AllIcons 필드 이름(AllIcons.Actions.Find)이나 이 jar 의 SVG 경로(/icons/ei/caseCamel.svg).
 * SVG 는 같은 이름의 _dark 파일이 어두운 테마에서 쓰인다(IconLoader 규칙).
 * 파일이 없거나 아이콘을 못 찾은 줄은 기록만 남기고 건너뛴다.
 */
final class IconRules {

    private static final Logger LOG = Logger.getInstance(IconRules.class);
    private static final String ALL_ICONS = "AllIcons.";
    private static final String COMMENT = "#";

    private IconRules() {
    }

    static Map<String, Icon> load(String resource) {
        Map<String, Icon> rules = new LinkedHashMap<>();
        try (InputStream in = IconRules.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOG.warn("아이콘 규칙 파일이 없음: " + resource);
                return rules;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith(COMMENT) || eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                Icon icon = icon(line.substring(eq + 1).strip());
                if (icon == null) {
                    LOG.warn("아이콘을 찾지 못함: " + resource + " " + line);
                } else {
                    rules.put(key, icon);
                }
            }
        } catch (Exception e) {
            LOG.warn("아이콘 규칙을 읽지 못함: " + resource, e);
        }
        return rules;
    }

    private static Icon icon(String spec) {
        if (spec.startsWith(ALL_ICONS)) {
            return allIcons(spec.substring(ALL_ICONS.length()));
        }
        return IconRules.class.getResource(spec) == null ? null : IconLoader.getIcon(spec, IconRules.class);
    }

    /** "Actions.Find" → AllIcons.Actions 의 Find 필드. 안쪽 클래스가 여러 단이면 점마다 한 단. 없으면 null. */
    private static Icon allIcons(String path) {
        int dot = path.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        try {
            Class<?> owner = Class.forName(AllIcons.class.getName() + "$" + path.substring(0, dot).replace('.', '$'));
            return owner.getField(path.substring(dot + 1)).get(null) instanceof Icon icon ? icon : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
