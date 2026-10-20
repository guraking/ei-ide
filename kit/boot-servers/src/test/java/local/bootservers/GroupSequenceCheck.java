package local.bootservers;

import local.bootservers.GroupSequence.Kind;
import local.bootservers.GroupSequence.Status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** GroupSequence 자체 점검. 실패하면 예외로 끝난다. */
public final class GroupSequenceCheck {

    private static final long TIMEOUT = 1000;
    private static final long GRACE = 100;

    public static void main(String[] args) {
        inOrder();
        skipsRunning();
        staleFailureWaits();
        notStarted();
        failureAfterStart();
        timeout();
        rejectsBadInput();
        System.out.println("GroupSequenceCheck 통과");
    }

    /** 설정 → 게이트웨이 → 업무: 앞 서버가 RUNNING 이 되어야 다음을 시작한다. */
    private static void inOrder() {
        Map<String, Status> st = states("설정", "게이트웨이", "업무");
        GroupSequence seq = new GroupSequence(List.of("설정", "게이트웨이", "업무"), TIMEOUT, GRACE);
        expect(seq.step(0, st::get), Kind.START, "설정");
        st.put("설정", Status.STARTING);
        expect(seq.step(10, st::get), Kind.WAIT, null);
        st.put("설정", Status.RUNNING);
        expect(seq.step(20, st::get), Kind.START, "게이트웨이");
        check(seq.position() == 2, "차례 2");
        st.put("게이트웨이", Status.RUNNING);
        expect(seq.step(30, st::get), Kind.START, "업무");
        st.put("업무", Status.RUNNING);
        expect(seq.step(40, st::get), Kind.DONE, null);
        expect(seq.step(50, st::get), Kind.DONE, null);
    }

    private static void skipsRunning() {
        Map<String, Status> st = states("a", "b");
        st.put("a", Status.RUNNING);
        GroupSequence seq = new GroupSequence(List.of("a", "b"), TIMEOUT, GRACE);
        expect(seq.step(0, st::get), Kind.START, "b");
        // 이미 시작 중인 서버는 다시 시작하지 않는다.
        Map<String, Status> st2 = states("c");
        st2.put("c", Status.STARTING);
        GroupSequence seq2 = new GroupSequence(List.of("c"), TIMEOUT, GRACE);
        expect(seq2.step(0, st2::get), Kind.WAIT, null);
        st2.put("c", Status.RUNNING);
        expect(seq2.step(1, st2::get), Kind.DONE, null);
    }

    /** 지난 실행의 FAILED 는 시작 직후 실패로 치지 않고, PREPARING(빌드 중)이 이어지는 동안은 한도를 늦춘다. */
    private static void staleFailureWaits() {
        Map<String, Status> st = states("a");
        st.put("a", Status.FAILED);
        GroupSequence seq = new GroupSequence(List.of("a"), TIMEOUT, GRACE);
        expect(seq.step(0, st::get), Kind.START, "a");
        expect(seq.step(GRACE, st::get), Kind.WAIT, null);
        st.put("a", Status.PREPARING);
        expect(seq.step(GRACE + 50, st::get), Kind.WAIT, null);
        expect(seq.step(GRACE * 3, st::get), Kind.WAIT, null);
        // 빌드가 끝나고 프로세스가 뜨기까지의 짧은 틈.
        st.put("a", Status.FAILED);
        expect(seq.step(GRACE * 4, st::get), Kind.WAIT, null);
        st.put("a", Status.STARTING);
        expect(seq.step(GRACE * 5, st::get), Kind.WAIT, null);
        st.put("a", Status.RUNNING);
        expect(seq.step(GRACE * 6, st::get), Kind.DONE, null);
    }

    /** 시작 지시 뒤 오류 창만 띄우고 프로세스를 만들지 않으면(그대로 STOPPED · FAILED) 한도 뒤 NOT_STARTED. */
    private static void notStarted() {
        Map<String, Status> st = states("a", "b");
        GroupSequence seq = new GroupSequence(List.of("a", "b"), TIMEOUT, GRACE);
        expect(seq.step(0, st::get), Kind.START, "a");
        expect(seq.step(GRACE, st::get), Kind.WAIT, null);
        expect(seq.step(GRACE + 1, st::get), Kind.NOT_STARTED, "a");
        expect(seq.step(GRACE + 2, st::get), Kind.DONE, null);
        // 빌드 실패: PREPARING 뒤 다시 STOPPED 로 돌아오면 마지막 PREPARING 부터 잰다.
        Map<String, Status> st2 = states("x");
        GroupSequence seq2 = new GroupSequence(List.of("x"), TIMEOUT, GRACE);
        expect(seq2.step(0, st2::get), Kind.START, "x");
        st2.put("x", Status.PREPARING);
        expect(seq2.step(500, st2::get), Kind.WAIT, null);
        st2.put("x", Status.STOPPED);
        expect(seq2.step(500 + GRACE, st2::get), Kind.WAIT, null);
        expect(seq2.step(500 + GRACE + 1, st2::get), Kind.NOT_STARTED, "x");
        // 시작 지시 전의 PREPARING(다른 빌드)은 STOPPED 와 같이 시작한다.
        Map<String, Status> st3 = states("y");
        st3.put("y", Status.PREPARING);
        expect(new GroupSequence(List.of("y"), TIMEOUT, GRACE).step(0, st3::get), Kind.START, "y");
    }

    private static void failureAfterStart() {
        Map<String, Status> st = states("a", "b");
        GroupSequence seq = new GroupSequence(List.of("a", "b"), TIMEOUT, GRACE);
        expect(seq.step(0, st::get), Kind.START, "a");
        st.put("a", Status.STARTING);
        expect(seq.step(10, st::get), Kind.WAIT, null);
        st.put("a", Status.FAILED);
        expect(seq.step(20, st::get), Kind.FAILED, "a");
        expect(seq.step(30, st::get), Kind.DONE, null);
        // 시작 중에 꺼져도(STOPPED) 실패다.
        Map<String, Status> st2 = states("x");
        GroupSequence seq2 = new GroupSequence(List.of("x"), TIMEOUT, GRACE);
        expect(seq2.step(0, st2::get), Kind.START, "x");
        st2.put("x", Status.STARTING);
        seq2.step(1, st2::get);
        st2.put("x", Status.STOPPED);
        expect(seq2.step(2, st2::get), Kind.FAILED, "x");
    }

    private static void timeout() {
        Map<String, Status> st = states("a", "b");
        GroupSequence seq = new GroupSequence(List.of("a", "b"), TIMEOUT, GRACE);
        expect(seq.step(0, st::get), Kind.START, "a");
        st.put("a", Status.STARTING);
        expect(seq.step(TIMEOUT, st::get), Kind.WAIT, null);
        expect(seq.step(TIMEOUT + 1, st::get), Kind.TIMEOUT, "a");
    }

    private static void rejectsBadInput() {
        boolean empty = false;
        try {
            new GroupSequence(List.of(), TIMEOUT, GRACE);
        }
        catch (IllegalArgumentException e) {
            empty = true;
        }
        check(empty, "빈 묶음은 거절");
        boolean zero = false;
        try {
            new GroupSequence(List.of("a"), 0, GRACE);
        }
        catch (IllegalArgumentException e) {
            zero = true;
        }
        check(zero, "한도 0 은 거절");
        boolean zeroGrace = false;
        try {
            new GroupSequence(List.of("a"), TIMEOUT, 0);
        }
        catch (IllegalArgumentException e) {
            zeroGrace = true;
        }
        check(zeroGrace, "시작 확인 한도 0 은 거절");
    }

    private static Map<String, Status> states(String... names) {
        Map<String, Status> map = new HashMap<>();
        for (String n : names) {
            map.put(n, Status.STOPPED);
        }
        return map;
    }

    private static void expect(GroupSequence.Step step, Kind kind, String member) {
        check(step.kind() == kind && java.util.Objects.equals(step.member(), member),
                "기대 " + kind + " " + member + ", 실제 " + step);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
