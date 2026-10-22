package local.bootservers;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/** 화면 글 묶음(resources/messages/BootServersBundle*.properties). 기본은 영어, IDE 언어가 한국어면 _ko. */
public final class BootServersBundle extends DynamicBundle {
    private static final String BUNDLE = "messages.BootServersBundle";
    private static final BootServersBundle INSTANCE = new BootServersBundle();

    private BootServersBundle() {
        super(BootServersBundle.class, BUNDLE);
    }

    public static @Nls @NotNull String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object @NotNull ... params) {
        return INSTANCE.getMessage(key, params);
    }
}
