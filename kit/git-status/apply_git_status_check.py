#!/usr/bin/env python3
"""apply_git_status.patch 자체 점검: 파일마다 한 번 고침, 두 번째는 그대로, 원문이 없거나 둘이면 거절, 도우미 안에 원문 줄이 다시 나오지 않음."""
import apply_git_status as t

STATUS_SAMPLE = "a\n" + t.LOG_LINE + "b\n" + t.LOOP_LINE + "c\n" + t.REFRESH_LINES + "d\n" + t.HELPER_ANCHOR + "e\n"
STATUS_EXPECTED = "a\n" + t.CONSTANTS + "b\n" + t.LOOP + "c\n" + t.REFRESH + "d\n" + t.HELPER + "e\n"
REPO_SAMPLE = "a\n" + t.REPO_DOC_LINE + "b\n" + t.RECURSION_CHECK + "c\n" + t.FIND_NEW_ROOTS + "d\n" + t.INVALID_ROOTS + "e\n"
REPO_EXPECTED = ("a\n" + t.REPO_CONSTANTS + "b\n" + t.RECURSION_CHECK_POOL + "c\n" + t.FIND_NEW_ROOTS_PARALLEL + "d\n"
                 + t.REPO_HELPERS + "e\n")

GIT_REPO_SAMPLE = ("a\n" + t.GIT_REPO_IMPORT + "b\n" + t.RECENT_FIELD + "c\n" + t.INIT_WORKTREES + "d\n" + t.UPDATE_READ + "e\n"
                   + t.RECENT_READ + "f\n" + t.RECENT_HELPER_ANCHOR + "g\n" + t.CREATED_TAGS + "h\n")
GIT_REPO_EXPECTED = ("a\n" + t.GIT_REPO_IMPORTS + "b\n" + t.RECENT_FIELD_SEQ + "c\n" + t.INIT_WORKTREES_ASYNC + "d\n"
                     + t.UPDATE_READ_RECENT + "e\n" + t.RECENT_READ_ASYNC + "f\n" + t.RECENT_HELPER + "g\n" + t.CREATED_TAGS_RECENT + "h\n")
USER_SAMPLE = "a\n" + t.USER_READ + "b\n" + t.USER_HELPER_ANCHOR + "c\n"
USER_EXPECTED = "a\n" + t.USER_READ_ONCE + "b\n" + t.USER_HELPER + "c\n"

for target, sample, expected in ((t.TARGET, STATUS_SAMPLE, STATUS_EXPECTED), (t.REPO_TARGET, REPO_SAMPLE, REPO_EXPECTED),
                                 (t.GIT_REPO_TARGET, GIT_REPO_SAMPLE, GIT_REPO_EXPECTED), (t.USER_TARGET, USER_SAMPLE, USER_EXPECTED)):
    fixed = t.patch(sample, target)
    assert fixed == expected, fixed
    assert t.patch(fixed, target) is None
    # 고친 뒤 원문은 덧붙인 곳에서만 한 번 남는다(도우미 안에 같은 줄이 또 있으면 다음 판 손질 · check_kit 이 헷갈린다).
    for rel, old, new in t.EDITS:
        if rel == target:
            assert fixed.count(old) == (1 if old in new else 0), old
    for bad in ("a\n", sample + t.EDITS[[rel for rel, _, _ in t.EDITS].index(target)][1]):
        try:
            t.patch(bad, target)
            raise AssertionError("거절해야 함")
        except ValueError:
            pass
# 미리 돌린 결과가 없으면 원래 status 를 그대로 돌린다.
assert "stageAreaHolder.refresh(rootDirtyPaths)" in t.REFRESH
# 저장소 만들기: 화면 스레드 · 읽기 잠금이면 원래대로 하나씩, 만들기 스레드의 다시 들어오기는 원래 오류 처리로.
assert "!eiApp.isDispatchThread && !eiApp.isReadAccessAllowed" in t.FIND_NEW_ROOTS_PARALLEL
assert "EI_CREATING.set(false)" in t.REPO_HELPERS and "EI_CREATING.get()" in t.RECURSION_CHECK_POOL
# 저장소 등록: worktree · reflog 는 기다리지 않고, 늦게 끝난 옛 reflog 결과는 버린다. 사용자 읽기는 git 한 번.
assert "runBlockingMaybeCancellable" not in t.INIT_WORKTREES_ASYNC and "scheduleReload()" in t.INIT_WORKTREES_ASYNC
assert "collectRecentCheckoutBranches" not in t.RECENT_READ_ASYNC
assert "eiRecentSeq.get() != eiSeq" in t.RECENT_HELPER and "repositoryUpdated" in t.RECENT_HELPER
assert t.USER_READ_ONCE.count("eiReadUser(") == 1 and "GitConfigUtil.getValue(" not in t.USER_READ_ONCE
print("apply_git_status_check 통과")
