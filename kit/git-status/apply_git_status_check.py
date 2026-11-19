#!/usr/bin/env python3
"""apply_git_status.patch 자체 점검: 한 번 고침, 두 번째는 그대로, 원문이 없거나 둘이면 거절, 도우미 안에 원문 줄이 다시 나오지 않음."""
import apply_git_status as t

SAMPLE = "a\n" + t.LOG_LINE + "b\n" + t.LOOP_LINE + "c\n" + t.REFRESH_LINES + "d\n" + t.HELPER_ANCHOR + "e\n"
EXPECTED = "a\n" + t.CONSTANTS + "b\n" + t.LOOP + "c\n" + t.REFRESH + "d\n" + t.HELPER + "e\n"

fixed = t.patch(SAMPLE)
assert fixed == EXPECTED, fixed
assert t.patch(fixed) is None
# 고친 뒤 원문은 덧붙인 곳에서만 한 번 남는다(도우미 안에 같은 줄이 또 있으면 다음 판 손질 · check_kit 이 헷갈린다).
for _, old, new in t.EDITS:
    assert fixed.count(old) == (1 if old in new else 0), old
# 미리 돌린 결과가 없으면 원래 status 를 그대로 돌린다.
assert "stageAreaHolder.refresh(rootDirtyPaths)" in t.REFRESH
for bad in ("a\n", SAMPLE + t.LOOP_LINE):
    try:
        t.patch(bad)
        raise AssertionError("거절해야 함")
    except ValueError:
        pass
print("apply_git_status_check 통과")
