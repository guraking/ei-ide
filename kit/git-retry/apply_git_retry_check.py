#!/usr/bin/env python3
"""apply_git_retry.patch 자체 점검: 한 번 고침, 두 번째는 그대로, 앞 판 손질은 되돌려 같은 결과, 원문이 없거나 둘이면 거절."""
import apply_git_retry as t

SAMPLE = "a\n" + t.LOG_LINE + "b\n" + t.HELPER_ANCHOR + "c\n" + t.RUN_LINE + "d\n"
EXPECTED = "a\n" + t.CONSTANTS + "b\n" + t.HELPER + "c\n" + t.RETRY + "d\n"
OLD = "a\n" + t.OLD_CONSTANTS + "b\n" + t.OLD_HELPER + "c\n" + t.OLD_RETRY + "d\n"

fixed = t.patch(SAMPLE)
assert fixed == EXPECTED, fixed
assert t.patch(fixed) is None
assert t.patch(OLD) == EXPECTED
# 서버 명령만 다시 하고, 쉬는 동안 취소를 짧은 간격으로 본다.
assert "EI_RETRY_COMMANDS.contains(handler.getCommand().name())" in t.HELPER
assert "EI_CANCEL_CHECK_MS" in t.RETRY and "Thread.sleep(Math.min(EI_CANCEL_CHECK_MS" in t.RETRY
for old, _ in t.OLD_EDITS:
    for _, _, new in t.EDITS:
        assert old not in new, "옛 손질이 새 손질 안에 있으면 되돌리기가 새 손질을 깨뜨린다"
for bad in ("a\n", SAMPLE + t.RUN_LINE):
    try:
        t.patch(bad)
        raise AssertionError("거절해야 함")
    except ValueError:
        pass
print("apply_git_retry_check 통과")
