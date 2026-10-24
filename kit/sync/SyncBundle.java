package kinggura.sync;

import com.intellij.AbstractBundle;
import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

import java.util.Locale;
import java.util.ResourceBundle;

/** 화면 글 묶음(resources/messages/SyncBundle*.properties). 기본은 영어, IDE 언어가 한국어면 _ko.
 * 오류 · 확인 · 알림 · 진행 · 안내 글은 messages/SyncMessages.properties 에 두고 IDE 언어와 상관없이 그 한국어 글을 쓴다. */
final class SyncBundle extends DynamicBundle {
  private static final String BUNDLE = "messages.SyncBundle";
  private static final ResourceBundle KOREAN = ResourceBundle.getBundle("messages.SyncMessages", Locale.ROOT, SyncBundle.class.getClassLoader());
  private static final SyncBundle INSTANCE = new SyncBundle();

  private SyncBundle() {
    super(SyncBundle.class, BUNDLE);
  }

  static @Nls @NotNull String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object @NotNull ... params) {
    return KOREAN.containsKey(key) ? AbstractBundle.message(KOREAN, key, params) : INSTANCE.getMessage(key, params);
  }
}
