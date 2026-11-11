package local.bootservers;

import java.util.Objects;

/** BootRenameListener.oldName: 실행 설정 고유 id 에서 옛 이름 꺼내기. */
public final class BootRenameCheck {

    public static void main(String[] args) {
        eq(BootRenameListener.oldName("Application.옛 서버", "Application.새 서버", "새 서버"), "옛 서버");
        eq(BootRenameListener.oldName("Application.app.api", "Application.app", "app"), "app.api");
        // 이름이 그대로면 옮길 것이 없다.
        eq(BootRenameListener.oldName("Application.app", "Application.app", "app"), null);
        // 종류가 다르면(접두가 다름) 옛 이름으로 보지 않는다.
        eq(BootRenameListener.oldName("Gradle.app", "Application.api", "api"), null);
        eq(BootRenameListener.oldName("Application.", "Application.api", "api"), null);
        System.out.println("BootRenameCheck 통과");
    }

    private static void eq(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(actual + " != " + expected);
        }
    }
}
