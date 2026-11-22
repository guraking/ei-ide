#!/usr/bin/env python3
"""apply_exit_fast.patch 자체 점검: 한 번 고침, 두 번째는 그대로, 원문이 없거나 둘이면 거절."""
import apply_exit_fast as t

SAMPLE = "a\n" + t.LOG_LINE + "b\n" + t.CLOSE_WAIT + "c\n" + t.AUTOMAKE_WAIT + "d\n"
EXPECTED = "a\n" + t.CONSTANTS + "b\n" + t.CLOSE_WAIT_NEW + "c\n" + t.AUTOMAKE_WAIT_NEW + "d\n"

fixed = t.patch(SAMPLE)
assert fixed == EXPECTED, fixed
assert t.patch(fixed) is None
# 끄는 중이 아닐 때는 원래 15초를 그대로 쓴다.
assert "isExitInProgress() ? EI_EXIT_BUILD_WAIT_SECONDS : 15" in t.CLOSE_WAIT_NEW
for bad in ("a\n", SAMPLE + t.AUTOMAKE_WAIT):
    try:
        t.patch(bad)
        raise AssertionError("거절해야 함")
    except ValueError:
        pass
print("apply_exit_fast_check 통과")
