#!/usr/bin/env python3
"""apply_git_retry.patch 자체 점검: 한 번 고침, 두 번째는 그대로, 원문이 없거나 둘이면 거절."""
import apply_git_retry as t

SAMPLE = "a\n" + t.LOG_LINE + "b\n" + t.HELPER_ANCHOR + "c\n" + t.RUN_LINE + "d\n"

fixed = t.patch(SAMPLE)
assert fixed == "a\n" + t.CONSTANTS + "b\n" + t.HELPER + "c\n" + t.RETRY + "d\n", fixed
assert t.patch(fixed) is None
for bad in ("a\n", SAMPLE + t.RUN_LINE):
    try:
        t.patch(bad)
        raise AssertionError("거절해야 함")
    except ValueError:
        pass
print("apply_git_retry_check 통과")
