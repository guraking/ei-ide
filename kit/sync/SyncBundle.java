package kinggura.sync;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/** 화면 글 묶음(resources/messages/SyncBundle*.properties). 기본은 영어, IDE 언어가 한국어면 _ko. */
final class SyncBundle extends DynamicBundle {
  private static final String BUNDLE = "messages.SyncBundle";
  private static final SyncBundle INSTANCE = new SyncBundle();

  private SyncBundle() {
    super(SyncBundle.class, BUNDLE);
  }

  static @Nls @NotNull String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object @NotNull ... params) {
    return INSTANCE.getMessage(key, params);
  }
}
