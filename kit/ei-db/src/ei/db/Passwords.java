package ei.db;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;

/**
 * 접속 비밀번호는 설정 파일이 아닌 IDE 암호 저장소에 접속 id 로 둔다.
 * 화면 스레드에서 부르지 말 것: 저장소(OS 키체인 · KeePass)를 여는 데 막힐 수 있다.
 */
public final class Passwords {
  private Passwords() {}

  private static CredentialAttributes attributes(DbConfig c) {
    return new CredentialAttributes(CredentialAttributesKt.generateServiceName("EI Database", c.id));
  }

  /** 없으면 null. */
  public static String get(DbConfig c) {
    return PasswordSafe.getInstance().getPassword(attributes(c));
  }

  /** null 이면 지운다. */
  public static void set(DbConfig c, String password) {
    PasswordSafe.getInstance().setPassword(attributes(c), password);
  }
}
