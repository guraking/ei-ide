package local.bootservers.memo;

import java.util.Arrays;

/** SecureMemoCipher 자체 점검. 실패하면 예외로 끝난다. */
public final class SecureMemoCipherCheck {

    public static void main(String[] args) throws Exception {
        char[] pw = "비밀번호1234".toCharArray();
        String text = "운영 DB 접속\n계정: scott / 줄바꿈 · 한글 · emoji ✓";

        byte[] a = SecureMemoCipher.encrypt(pw, text);
        eq(text, SecureMemoCipher.decrypt(pw, a), "되풀기");
        eq("", SecureMemoCipher.decrypt(pw, SecureMemoCipher.encrypt(pw, "")), "빈 글");
        check(!Arrays.equals(a, SecureMemoCipher.encrypt(pw, text)), "같은 글도 매번 다른 바이트");
        check(!new String(a, java.nio.charset.StandardCharsets.UTF_8).contains("scott"), "평문이 파일에 보이지 않음");

        rejects("틀린비밀번호".toCharArray(), a, "틀린 비밀번호");
        byte[] tampered = a.clone();
        tampered[tampered.length - 1] ^= 1;
        rejects(pw, tampered, "바뀐 파일");
        rejects(pw, Arrays.copyOf(a, 10), "짧은 파일");
        rejects(pw, "not a memo file at all, long enough".getBytes(), "다른 형식");
        System.out.println("SecureMemoCipherCheck 통과");
    }

    private static void rejects(char[] pw, byte[] data, String what) throws Exception {
        try {
            SecureMemoCipher.decrypt(pw, data);
        } catch (SecureMemoCipher.WrongPasswordException expected) {
            return;
        }
        throw new AssertionError(what + ": 열리면 안 됨");
    }

    private static void eq(String expected, String actual, String what) {
        check(expected.equals(actual), what + ": 기대 [" + expected + "] 실제 [" + actual + "]");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
