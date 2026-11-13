package local.bootservers;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.util.registry.Registry;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Full Line(편집기 회색 한 줄 제안) 기본값을 IDE 설정마다 한 번 바꾼다. 그 뒤 사용자가 바꾼 값은 그대로 둔다.
 * <ul>
 *   <li>Java 제안을 받으면 그 자리를 코드 서식에 맞춰 정렬한다(레지스트리 full.line.java.force.format, 원래 false).</li>
 *   <li>자동 완성 목록이 떠 있을 때 회색 제안을 목록 안 항목으로 함께 보인다(설정 「Synchronize inline and popup completions」,
 *       원래 Independent). Tab 하나가 목록 선택과 회색 제안 받기로 갈리지 않는다.</li>
 * </ul>
 * Full Line 은 우리가 컴파일에 쓰지 않는 플러그인이라 설정 서비스 클래스를 Full Line 의 인라인 완성 제공자가 쓰는 클래스 로더에서
 * 이름으로 읽어 부른다(설정 서비스는 처음 쓸 때 만들어지는 light service 라 이름만으로는 찾을 수 없다). 플러그인이 꺼져 있거나
 * 이름 · 메서드가 바뀐 판이면 기록만 남기고 넘어간다.
 */
public final class FullLineDefaults implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(FullLineDefaults.class);
    /** 적용했다는 표시. 앱 수준 PropertiesComponent(other.xml)에 남는다. */
    private static final String APPLIED_KEY = "moaide.fullLine.defaults.applied";
    private static final String FORMAT_KEY = "full.line.java.force.format";
    private static final ExtensionPointName<Object> PROVIDERS = ExtensionPointName.create("com.intellij.inline.completion.provider");
    private static final String PROVIDER_CLASS = "org.jetbrains.completion.full.line.impl.platform.inline.FullLineInlineCompletionProvider";
    private static final String SETTINGS_CLASS = "com.intellij.ml.inline.completion.impl.configuration.MLCompletionSettings";
    private static final String SET_UX_MODE = "setUxMode";
    private static final String UX_MODE_SYNC = "LookupItemWhenCollision";

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        if (properties.getBoolean(APPLIED_KEY)) return;
        properties.setValue(APPLIED_KEY, true);

        ClassLoader loader = null;
        for (Object provider : PROVIDERS.getExtensionList()) {
            if (provider.getClass().getName().equals(PROVIDER_CLASS)) loader = provider.getClass().getClassLoader();
        }
        if (loader == null) {
            LOG.info("Full Line 플러그인이 없거나 꺼져 있어 기본값을 바꾸지 않았습니다.");
            return;
        }
        try {
            Registry.get(FORMAT_KEY).setValue(true);
        } catch (RuntimeException e) {
            LOG.warn("Full Line 레지스트리 " + FORMAT_KEY + " 를 바꾸지 못했습니다", e);
        }
        try {
            setUxMode(ApplicationManager.getApplication().getService(loader.loadClass(SETTINGS_CLASS)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Full Line 「Synchronize inline and popup completions」 기본값을 바꾸지 못했습니다", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setUxMode(Object settings) throws ReflectiveOperationException {
        for (Method method : settings.getClass().getMethods()) {
            if (method.getName().equals(SET_UX_MODE) && method.getParameterCount() == 1 && method.getParameterTypes()[0].isEnum()) {
                method.invoke(settings, Enum.valueOf((Class) method.getParameterTypes()[0], UX_MODE_SYNC));
                return;
            }
        }
        throw new NoSuchMethodException(SETTINGS_CLASS + "." + SET_UX_MODE);
    }
}
