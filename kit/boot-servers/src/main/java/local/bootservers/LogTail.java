package local.bootservers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * 로그 파일 끝 따라 읽기(tail -f). IntelliJ 에 기대지 않는다. 한 스레드에서만 부른다.
 * <ul>
 *   <li>처음 읽을 때 파일이 initialBytes 보다 크면 끝의 initialBytes 만, 첫 줄바꿈 뒤부터 읽는다.</li>
 *   <li>줄바꿈으로 끝난 줄까지만 돌려주고, 끝나지 않은 줄은 다음 poll 로 미룬다.
 *       한 번에 MAX_READ_BYTES 를 채우고도 줄바꿈이 없으면 그만큼을 그대로 돌려준다(아주 긴 한 줄에서 멈추지 않게).</li>
 *   <li>파일이 읽은 자리보다 작아지거나 만든 시각이 바뀌면 순환(새 파일)으로 보고 처음부터 읽는다.</li>
 *   <li>파일이 없으면 MISSING 을 한 번 알리고, 다시 생기면 APPEARED 와 함께 처음부터 읽는다.</li>
 *   <li>글은 UTF-8 로 풀고, UTF-8 이 아니면 fallback 문자셋(보통 OS 기본, 한국어 윈도는 MS949)으로 푼다.</li>
 * </ul>
 */
public final class LogTail {

    // poll 한 번에 읽는 최대 크기. 1초 간격 poll 에서 초당 이만큼까지는 밀리지 않고 따라가고, 그보다 많이 쌓이면 다음 poll 로 나눠 넣는다.
    static final int MAX_READ_BYTES = 4 * 1024 * 1024;

    public enum Event { NONE, MISSING, APPEARED, ROTATED, ERROR }

    /** text 는 새로 읽은 글(없으면 ""), error 는 event 가 ERROR 일 때 이유. */
    public record Poll(Event event, String text, String error) {}

    private final Path file;
    private final Charset fallback;
    private final long initialBytes;
    private long position = -1;
    private boolean skipPartialLine;
    private boolean missing;
    private FileTime created;
    private String lastError;

    public LogTail(Path file, Charset fallback, long initialBytes) {
        this.file = file;
        this.fallback = fallback;
        this.initialBytes = initialBytes;
    }

    public Path file() {
        return file;
    }

    public Poll poll() {
        try {
            Poll p = read();
            lastError = null;
            return p;
        }
        catch (NoSuchFileException e) {
            return missing();
        }
        catch (IOException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // 같은 이유가 이어지면 한 번만 알린다.
            if (reason.equals(lastError)) {
                return new Poll(Event.NONE, "", null);
            }
            lastError = reason;
            return new Poll(Event.ERROR, "", reason);
        }
    }

    private Poll missing() {
        if (missing) {
            return new Poll(Event.NONE, "", null);
        }
        missing = true;
        position = 0;
        skipPartialLine = false;
        created = null;
        return new Poll(Event.MISSING, "", null);
    }

    private Poll read() throws IOException {
        if (!Files.exists(file)) {
            return missing();
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return read(ch);
        }
    }

    private Poll read(FileChannel ch) throws IOException {
        // 크기는 연 채널에서 읽는다. 따로 읽으면 그 사이 순환된 다른 파일의 크기를 볼 수 있다.
        long size = ch.size();
        FileTime creationTime = Files.readAttributes(file, BasicFileAttributes.class).creationTime();
        Event event = Event.NONE;
        if (missing) {
            missing = false;
            event = Event.APPEARED;
        }
        else if (position < 0) {
            position = Math.max(0, size - initialBytes);
            skipPartialLine = position > 0;
        }
        else if (size < position || created != null && !created.equals(creationTime)) {
            position = 0;
            skipPartialLine = false;
            event = Event.ROTATED;
        }
        created = creationTime;
        if (size <= position) {
            return new Poll(event, "", null);
        }
        byte[] bytes = readBytes(ch, size);
        int start = 0;
        if (skipPartialLine) {
            int nl = indexOf(bytes, 0);
            if (nl < 0) {
                position += bytes.length;
                return new Poll(event, "", null);
            }
            start = nl + 1;
            skipPartialLine = false;
        }
        int end = lastNewline(bytes) + 1;
        if (end <= start) {
            end = bytes.length == MAX_READ_BYTES ? bytes.length : start;
        }
        position += end;
        return new Poll(event, decode(bytes, start, end - start), null);
    }

    private byte[] readBytes(FileChannel ch, long size) throws IOException {
        int length = (int) Math.min(MAX_READ_BYTES, size - position);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        ch.position(position);
        while (buffer.hasRemaining() && ch.read(buffer) > 0) {
            // 다 채우거나 파일 끝까지 읽는다.
        }
        byte[] bytes = new byte[buffer.position()];
        buffer.flip().get(bytes);
        return bytes;
    }

    private static int indexOf(byte[] bytes, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int lastNewline(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private String decode(byte[] bytes, int offset, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
        }
        catch (CharacterCodingException e) {
            return new String(bytes, offset, length, fallback);
        }
    }
}
