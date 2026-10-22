package local.bootservers.memo;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 보안 메모 파일 형식. JDK 만 쓴다.
 * <pre>MAGIC(7) | salt(16) | iv(12) | AES-256-GCM(UTF-8 본문) + 태그(16)</pre>
 * 키는 PBKDF2-HMAC-SHA256 으로 비밀번호와 salt 에서 만든다. 비밀번호는 어디에도 남지 않으며,
 * 틀린 비밀번호와 바뀐 파일은 모두 GCM 태그 검사에서 걸려 {@link WrongPasswordException} 이 된다.
 * 매 저장마다 salt · iv 를 새로 뽑으므로 같은 글도 매번 다른 바이트가 된다.
 */
public final class SecureMemoCipher {

    /** 형식 표지. 형식을 바꾸면 끝 숫자를 올린다. */
    private static final byte[] MAGIC = "EIMEMO1".getBytes(StandardCharsets.US_ASCII);
    private static final int SALT_BYTES = 16;
    /** GCM 권장 iv 길이(NIST SP 800-38D). */
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    /** OWASP 2023 권장 PBKDF2-HMAC-SHA256 반복 수. */
    private static final int ITERATIONS = 600_000;
    private static final int HEADER_BYTES = MAGIC.length + SALT_BYTES + IV_BYTES;

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureMemoCipher() {}

    /** 비밀번호가 틀렸거나 파일이 손상 · 변조됐다. 둘은 구별할 수 없다. */
    public static final class WrongPasswordException extends Exception {
        WrongPasswordException(String message) {
            super(message);
        }
    }

    public static byte[] encrypt(char[] password, String text) throws GeneralSecurityException {
        byte[] salt = random(SALT_BYTES);
        byte[] iv = random(IV_BYTES);
        byte[] body = cipher(Cipher.ENCRYPT_MODE, password, salt, iv).doFinal(text.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(HEADER_BYTES + body.length).put(MAGIC).put(salt).put(iv).put(body).array();
    }

    public static String decrypt(char[] password, byte[] data) throws GeneralSecurityException, WrongPasswordException {
        if (data.length < HEADER_BYTES + TAG_BITS / Byte.SIZE || !Arrays.equals(MAGIC, Arrays.copyOf(data, MAGIC.length))) {
            throw new WrongPasswordException("보안 메모 파일 형식이 아닙니다.");
        }
        byte[] salt = Arrays.copyOfRange(data, MAGIC.length, MAGIC.length + SALT_BYTES);
        byte[] iv = Arrays.copyOfRange(data, MAGIC.length + SALT_BYTES, HEADER_BYTES);
        try {
            byte[] plain = cipher(Cipher.DECRYPT_MODE, password, salt, iv).doFinal(data, HEADER_BYTES, data.length - HEADER_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new WrongPasswordException("비밀번호가 맞지 않습니다.");
        }
    }

    private static Cipher cipher(int mode, char[] password, byte[] salt, byte[] iv) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
        byte[] key;
        try {
            key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        Arrays.fill(key, (byte) 0);
        return cipher;
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
