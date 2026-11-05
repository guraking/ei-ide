#!/usr/bin/env python3
"""apply_textmate.patch 자체 점검: 한 번 고침, 두 번째는 그대로, 원문이 없거나 둘이면 거절."""
import apply_textmate as t

SAMPLE = "a\n" + t.ORIGINAL + "b\n"

fixed = t.patch(SAMPLE)
assert fixed == "a\n" + t.PATCHED + "b\n", fixed
assert t.patch(fixed) is None
for bad in ("a\n", SAMPLE + t.ORIGINAL):
    try:
        t.patch(bad)
        raise AssertionError("거절해야 함")
    except ValueError:
        pass
print("apply_textmate_check 통과")
