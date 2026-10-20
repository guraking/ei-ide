package local.bootservers;

import java.util.List;
import java.util.function.Function;

/**
 * 서버 묶음을 정한 순서대로 하나씩 시작하는 진행 상태. IntelliJ 에 기대지 않는다.
 * 부르는 쪽이 일정 간격으로 {@link #step} 을 부르고, 돌려받은 지시대로 서버를 시작하거나 끝을 알린다.
 * <ul>
 *   <li>이미 실행 중인 서버는 시작하지 않고 다음으로 넘어간다.</li>
 *   <li>시작을 지시한 서버가 RUNNING 이 되면 다음 서버로 넘어간다.</li>
 *   <li>시작 지시 뒤 STARTING · RUNNING 을 한 번이라도 본 다음 FAILED · STOPPED 가 되면 실패다.</li>
 *   <li>지시 직후의 FAILED · STOPPED 는 지난 실행의 기록일 수 있으므로 바로 실패로 치지 않는다.
 *       PREPARING(빌드 · 실행 준비 중)을 마지막으로 본 때(없으면 지시한 때)부터 graceMillis 가 지나도록
 *       STARTING 이 안 되면 시작이 일어나지 않은 것(오류 창을 띄우고 그만둔 경우)으로 보고 NOT_STARTED 다.</li>
 *   <li>시작 지시 뒤 timeoutMillis 가 지나도 RUNNING 이 아니면 실패다.</li>
 * </ul>
 */
public final class GroupSequence {

    /** 서버 하나의 지금 상태. PREPARING 은 프로세스는 아직 없고 빌드 · 실행 준비가 도는 중. 시작 지시 전에는 STOPPED 와 같게 본다. */
    public enum Status { STOPPED, PREPARING, STARTING, RUNNING, FAILED }

    public enum Kind { START, WAIT, DONE, FAILED, NOT_STARTED, TIMEOUT }

    /** step 의 지시. member 는 START · FAILED · NOT_STARTED · TIMEOUT 일 때 그 서버, WAIT · DONE 이면 null. */
    public record Step(Kind kind, String member) {}

    private final List<String> members;
    private final long timeoutMillis;
    private final long graceMillis;
    private int index;
    private long startedAt = -1;
    private long lastBusy;
    private boolean seenLive;
    private boolean finished;

    /** @throws IllegalArgumentException members 가 비었거나 timeoutMillis · graceMillis 가 0 이하일 때 */
    public GroupSequence(List<String> members, long timeoutMillis, long graceMillis) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("묶음에 서버가 없습니다.");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("기다림 한도는 0 보다 커야 합니다: " + timeoutMillis);
        }
        if (graceMillis <= 0) {
            throw new IllegalArgumentException("시작 확인 한도는 0 보다 커야 합니다: " + graceMillis);
        }
        this.members = List.copyOf(members);
        this.timeoutMillis = timeoutMillis;
        this.graceMillis = graceMillis;
    }

    /** 끝(DONE · FAILED · TIMEOUT)을 한 번 돌려준 뒤로는 계속 DONE 을 돌려준다. */
    public Step step(long now, Function<String, Status> status) {
        if (finished) {
            return new Step(Kind.DONE, null);
        }
        while (index < members.size()) {
            String member = members.get(index);
            Status s = status.apply(member);
            if (startedAt < 0) {
                if (s == Status.RUNNING) {
                    index++;
                    continue;
                }
                startedAt = now;
                lastBusy = now;
                seenLive = s == Status.STARTING;
                // 이미 시작 중이면 다시 시작하지 않고 기다린다.
                return s == Status.STARTING ? new Step(Kind.WAIT, null) : new Step(Kind.START, member);
            }
            if (s == Status.RUNNING) {
                index++;
                startedAt = -1;
                continue;
            }
            if (s == Status.STARTING) {
                seenLive = true;
            }
            else if (s == Status.PREPARING) {
                lastBusy = now;
            }
            else if (seenLive) {
                finished = true;
                return new Step(Kind.FAILED, member);
            }
            else if (now - lastBusy > graceMillis) {
                finished = true;
                return new Step(Kind.NOT_STARTED, member);
            }
            if (now - startedAt > timeoutMillis) {
                finished = true;
                return new Step(Kind.TIMEOUT, member);
            }
            return new Step(Kind.WAIT, null);
        }
        finished = true;
        return new Step(Kind.DONE, null);
    }

    /** 지금 차례(1부터)와 전체 수. 다 끝났으면 차례는 전체 수다. */
    public int position() {
        return Math.min(index + 1, members.size());
    }

    public int size() {
        return members.size();
    }
}
