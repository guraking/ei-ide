package local.bootservers;

/** ThemeMigration.renameScheme 자체 점검. 실패하면 예외로 끝난다. */
public final class ThemeMigrationCheck {

    public static void main(String[] args) {
        String old = "<scheme name=\"_@user_EI Graphite\" version=\"142\" parent_scheme=\"Darcula\">\n"
                + "  <metaInfo>\n    <property name=\"originalScheme\">EI Graphite</property>\n  </metaInfo>\n"
                + "  <option name=\"EDITOR_FONT_NAME\" value=\"EI Mono\" />\n</scheme>\n";
        String renamed = ThemeMigration.renameScheme(old, "EI Graphite", "Moa Graphite");
        check(renamed.contains("name=\"_@user_Moa Graphite\""), "이름 " + renamed);
        check(renamed.contains(">Moa Graphite<"), "원래 구성표 " + renamed);
        check(renamed.contains("value=\"Moa Mono\""), "글꼴 " + renamed);
        check(renamed.contains("parent_scheme=\"Darcula\""), "부모는 그대로 " + renamed);
        check(!renamed.contains("EI "), "옛 이름이 남음 " + renamed);
        System.out.println("ThemeMigrationCheck 통과");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
