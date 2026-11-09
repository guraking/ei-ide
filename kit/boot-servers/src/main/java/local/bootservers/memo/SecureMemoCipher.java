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
 * 키 만들기는 느리므로 메모를 연 동안은 {@link Key} 하나(그 salt 포함)로 저장하고, 저장마다 iv 만 새로 뽑는다.
 * 비밀번호를 새로 정하거나 바꿀 때 salt 를 새로 뽑는다. 같은 글도 저장마다 다른 바이트가 된다.
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

    /** 비밀번호와 salt 에서 만든 AES 키. 메모를 연 동안 들고 있다가 잠글 때 {@link #wipe()} 로 지운다. */
    public static final class Key {
        private final byte[] salt;
        private final byte[] bytes;

        private Key(byte[] salt, byte[] bytes) {
            this.salt = salt;
            this.bytes = bytes;
        }

        /** 키 바이트를 0 으로 지운다. 지운 뒤에는 쓰지 않는다. */
        public void wipe() {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** 새 비밀번호에 쓸 salt. */
    public static byte[] newSalt() {
        return random(SALT_BYTES);
    }

    /** 메모 파일 머리의 salt. 형식이 아니면 WrongPasswordException. */
    public static byte[] saltOf(byte[] data) throws WrongPasswordException {
        checkFormat(data);
        return Arrays.copyOfRange(data, MAGIC.length, MAGIC.length + SALT_BYTES);
    }

    /** PBKDF2 로 키를 만든다(ITERATIONS 번 반복이라 느리다). 비밀번호 배열은 건드리지 않는다. */
    public static Key deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_BITS);
        try {
            return new Key(salt.clone(), SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } finally {
            spec.clearPassword();
        }
    }

    /** 키의 salt 와 새 iv 로 암호화한다. 키를 다시 만들지 않는다. */
    public static byte[] encrypt(Key key, String text) throws GeneralSecurityException {
        byte[] iv = random(IV_BYTES);
        byte[] body = cipher(Cipher.ENCRYPT_MODE, key, iv).doFinal(text.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(HEADER_BYTES + body.length).put(MAGIC).put(key.salt).put(iv).put(body).array();
    }

    /** 파일의 salt 로 만든 키로 푼다. 키가 맞지 않거나 파일이 바뀌었으면 WrongPasswordException. */
    public static String decrypt(Key key, byte[] data) throws GeneralSecurityException, WrongPasswordException {
        checkFormat(data);
        byte[] iv = Arrays.copyOfRange(data, MAGIC.length + SALT_BYTES, HEADER_BYTES);
        try {
            byte[] plain = cipher(Cipher.DECRYPT_MODE, key, iv).doFinal(data, HEADER_BYTES, data.length - HEADER_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new WrongPasswordException("비밀번호가 맞지 않습니다.");
        }
    }

    /** 새 salt 로 키를 만들어 암호화한다(한 번 쓰고 키를 지운다). */
    public static byte[] encrypt(char[] password, String text) throws GeneralSecurityException {
        Key key = deriveKey(password, newSalt());
        try {
            return encrypt(key, text);
        } finally {
            key.wipe();
        }
    }

    /** 파일의 salt 로 키를 만들어 푼다(한 번 쓰고 키를 지운다). */
    public static String decrypt(char[] password, byte[] data) throws GeneralSecurityException, WrongPasswordException {
        Key key = deriveKey(password, saltOf(data));
        try {
            return decrypt(key, data);
        } finally {
            key.wipe();
        }
    }

    private static void checkFormat(byte[] data) throws WrongPasswordException {
        if (data.length < HEADER_BYTES + TAG_BITS / Byte.SIZE || !Arrays.equals(MAGIC, Arrays.copyOf(data, MAGIC.length))) {
            throw new WrongPasswordException("보안 메모 파일 형식이 아닙니다.");
        }
    }

    private static Cipher cipher(int mode, Key key, byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key.bytes, "AES"), new GCMParameterSpec(TAG_BITS, iv));
        return cipher;
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
