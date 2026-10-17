package ei.db.ui;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import ei.db.DbConfig;

/**
 * IDE 를 끌 때까지만 메모리에 두는 비밀번호. Passwords 와 같은 열쇠를 써서 DbSessions.connection 의 Passwords.get 이 그대로 찾는다.
 * 암호 저장소를 건드리므로 화면 스레드에서 부르지 않는다.
 * shortcut: 열쇠 만들기를 Passwords 에서 베꼈다(그쪽이 비공개). Passwords 에 「이번 실행만」 저장이 생기면 그쪽을 부른다.
 */
final class SessionPassword {
  private SessionPassword() {}

  private static String serviceName(DbConfig c) {
    return CredentialAttributesKt.generateServiceName("EI Database", c.id);
  }

  /** 저장소에 있던 값은 지우고 메모리에만 둔다(메모리 전용 열쇠로 넣으면 저장소에는 비밀번호 없이 남아 지워진다). null 이면 둘 다 지운다. */
  static void set(DbConfig c, String password) {
    PasswordSafe.getInstance().setPassword(new CredentialAttributes(serviceName(c), null, (Class<?>) null, true), password);
  }

  /** 지금 값이 저장소가 아닌 메모리에만 있으면 참. 값이 없으면 거짓. */
  static boolean memoryOnly(DbConfig c) {
    CredentialAttributes attributes = new CredentialAttributes(serviceName(c));
    Credentials stored = PasswordSafe.getInstance().get(attributes);
    return stored != null && PasswordSafe.getInstance().isPasswordStoredOnlyInMemory(attributes, stored);
  }
}
