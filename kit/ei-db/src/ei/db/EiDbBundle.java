package ei.db;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/** 화면 글 묶음(resources/messages/EiDbBundle*.properties). 기본은 영어, IDE 언어가 한국어면 _ko. */
public final class EiDbBundle extends DynamicBundle {
  private static final String BUNDLE = "messages.EiDbBundle";
  private static final EiDbBundle INSTANCE = new EiDbBundle();

  private EiDbBundle() {
    super(EiDbBundle.class, BUNDLE);
  }

  public static @Nls @NotNull String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object @NotNull ... params) {
    return INSTANCE.getMessage(key, params);
  }
}
