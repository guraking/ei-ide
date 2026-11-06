package kinggura.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 자체 점검: 경로를 읽는 파서들에 실제 git 출력을 넣어 본다(java -cp out kinggura.sync.GitPathCheck, git 은 필요 없다).
 * 출력은 따옴표 · 역슬래시 · 탭 · 줄바꿈 · 제어 문자 · 앞 공백 · " -> " · 한글 · 이모지 · 대소문자만 다른 이름이 든 저장소에서
 * git 2.x(core.quotepath=false)로 받은 것이고, 기대값은 같은 저장소의 -z 출력(감싸지 않은 이름)이다.
 * 이런 이름은 윈도우 디스크에는 못 만들어도 다른 OS 에서 커밋되어 들어올 수 있다.
 */
public class GitPathCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }

  static final String STATUS = "## develop...origin/develop [behind 1]\n M \" lead.txt\"\n M #hash%.txt\n M -dash.txt\n M Case.txt\n M \"a -> b.txt\"\n M \"back\\\\slash.txt\"\n M case.txt\n M \"ctl\\001x.txt\"\n M \"new\\nline.txt\"\nRM \"a\\\"q.txt\" -> \"renamed\\t\\\"q\\\".txt\"\n M \"space name.txt\"\n M \"tab\\there.txt\"\n M \"한글 파일.txt\"\n M 😀.txt\n?? \"untracked\\ttab.txt\"\n?? \"untracked \\\"q\\\".txt\"\n";
  static final String NAME_STATUS = "M\t lead.txt\nM\t#hash%.txt\nM\t-dash.txt\nM\tCase.txt\nM\ta -> b.txt\nM\t\"a\\\"q.txt\"\nM\t\"back\\\\slash.txt\"\nM\tcase.txt\nM\t\"ctl\\001x.txt\"\nM\t\"new\\nline.txt\"\nR050\tspace name.txt\tspace 새 이름.txt\nM\t\"tab\\there.txt\"\nM\t한글 파일.txt\nM\t😀.txt\n";
  static final String LS_FILES = "H  lead.txt\nH #hash%.txt\nH -dash.txt\nH Case.txt\nH a -> b.txt\nH \"back\\\\slash.txt\"\nH case.txt\nH \"ctl\\001x.txt\"\nH dir 폴더/in.txt\nH \"new\\nline.txt\"\nH \"renamed\\t\\\"q\\\".txt\"\nH space name.txt\nH \"tab\\there.txt\"\nH 한글 파일.txt\nH 😀.txt\n";
  static final String NAME_ONLY = " lead.txt\n#hash%.txt\n-dash.txt\nCase.txt\na -> b.txt\n\"a\\\"q.txt\"\n\"back\\\\slash.txt\"\ncase.txt\n\"ctl\\001x.txt\"\n\"new\\nline.txt\"\n\"renamed\\t\\\"q\\\".txt\"\nspace name.txt\n\"tab\\there.txt\"\n한글 파일.txt\n😀.txt\n";
  static final String LOG_NAME_ONLY = "\u0001김\u001f11-06 10:32\u001fserver change\n\n lead.txt\n#hash%.txt\n-dash.txt\nCase.txt\na -> b.txt\n\"a\\\"q.txt\"\n\"back\\\\slash.txt\"\ncase.txt\n\"ctl\\001x.txt\"\n\"new\\nline.txt\"\nspace 새 이름.txt\n\"tab\\there.txt\"\n한글 파일.txt\n😀.txt\n";
  static final String LOG_AUTHOR = "\u0001김\u001fk@x\n\n lead.txt\n#hash%.txt\n-dash.txt\nCase.txt\na -> b.txt\n\"a\\\"q.txt\"\n\"back\\\\slash.txt\"\ncase.txt\n\"ctl\\001x.txt\"\n\"new\\nline.txt\"\nspace 새 이름.txt\n\"tab\\there.txt\"\n한글 파일.txt\n😀.txt\n";
  static final String LOG_RAW = "\u001eff0c1c0391e6b15468546609128231dae976faac\u001f3e36ce05f6c61a3e46c3390072ec740ce9f33705\u001f김\u001f1793961178\u001forigin/develop, origin/HEAD\u001fserver change\n\u001fk@x\u001d\n\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\t lead.txt\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\t#hash%.txt\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\t-dash.txt\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\tCase.txt\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\ta -> b.txt\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\t\"a\\\"q.txt\"\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\t\"back\\\\slash.txt\"\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\tcase.txt\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\t\"ctl\\001x.txt\"\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\t\"new\\nline.txt\"\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb R050\tspace name.txt\tspace 새 이름.txt\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\t\"tab\\there.txt\"\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\t한글 파일.txt\n:100644 100644 626799f0f85326a8c1fc522db584e86cdfccd51f 2139d8be699ff388ab3ec85008a3906675ac67cb M\t😀.txt\n\u001e3e36ce05f6c61a3e46c3390072ec740ce9f33705\u001f\u001f김\u001f1793961178\u001fHEAD -> develop\u001fbase\n\u001fk@x\u001d\n\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\t lead.txt\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\t#hash%.txt\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\t-dash.txt\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\tCase.txt\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\ta -> b.txt\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\t\"a\\\"q.txt\"\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\t\"back\\\\slash.txt\"\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\tcase.txt\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\t\"ctl\\001x.txt\"\n:000000 100644 0000000000000000000000000000000000000000 587be6b4c3f93f93c489c0111bba5596147a26cb A\tdir 폴더/in.txt\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\t\"new\\nline.txt\"\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\tspace name.txt\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\t\"tab\\there.txt\"\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\t한글 파일.txt\n:000000 100644 0000000000000000000000000000000000000000 626799f0f85326a8c1fc522db584e86cdfccd51f A\t😀.txt\n";
  static final String LS_TREE = " lead.txt\n#hash%.txt\n-dash.txt\nCase.txt\na -> b.txt\n\"a\\\"q.txt\"\n\"back\\\\slash.txt\"\ncase.txt\n\"ctl\\001x.txt\"\ndir 폴더/in.txt\n\"new\\nline.txt\"\nspace 새 이름.txt\n\"tab\\there.txt\"\n한글 파일.txt\n😀.txt\n";

  public static void main(String[] args) throws Exception {
    List<String> status = new ArrayList<>();
    for (String line : STATUS.split("\n")) if (!line.startsWith("##") && line.length() >= 4) status.add(SyncPanel.statusPath(line.substring(3)));
    eq(new TreeSet<>(status), new TreeSet<>(List.of(" lead.txt", "#hash%.txt", "-dash.txt", "Case.txt", "a -> b.txt", "back\\slash.txt", "case.txt", "ctl\u0001x.txt", "new\nline.txt", "renamed\t\"q\".txt", "space name.txt", "tab\there.txt", "untracked\ttab.txt", "untracked \"q\".txt", "한글 파일.txt", "😀.txt")));
    eq(new TreeSet<>(SyncPanel.parseNameStatus(NAME_STATUS).keySet()), new TreeSet<>(List.of(" lead.txt", "#hash%.txt", "-dash.txt", "Case.txt", "a -> b.txt", "a\"q.txt", "back\\slash.txt", "case.txt", "ctl\u0001x.txt", "new\nline.txt", "space 새 이름.txt", "tab\there.txt", "한글 파일.txt", "😀.txt")));
    eq(new TreeSet<>(SyncPanel.lsFilesPaths(LS_FILES)), new TreeSet<>(List.of(" lead.txt", "#hash%.txt", "-dash.txt", "Case.txt", "a -> b.txt", "back\\slash.txt", "case.txt", "ctl\u0001x.txt", "dir 폴더/in.txt", "new\nline.txt", "renamed\t\"q\".txt", "space name.txt", "tab\there.txt", "한글 파일.txt", "😀.txt")));
    eq(new TreeSet<>(ReverseMerge.lines(NAME_ONLY)), new TreeSet<>(List.of(" lead.txt", "#hash%.txt", "-dash.txt", "Case.txt", "a -> b.txt", "a\"q.txt", "back\\slash.txt", "case.txt", "ctl\u0001x.txt", "new\nline.txt", "renamed\t\"q\".txt", "space name.txt", "tab\there.txt", "한글 파일.txt", "😀.txt")));
    eq(new TreeSet<>(ReverseMerge.lines(LS_TREE)), new TreeSet<>(List.of(" lead.txt", "#hash%.txt", "-dash.txt", "Case.txt", "a -> b.txt", "a\"q.txt", "back\\slash.txt", "case.txt", "ctl\u0001x.txt", "dir 폴더/in.txt", "new\nline.txt", "space 새 이름.txt", "tab\there.txt", "한글 파일.txt", "😀.txt")));
    List<String> logNames = List.of(" lead.txt", "#hash%.txt", "-dash.txt", "Case.txt", "a -> b.txt", "a\"q.txt", "back\\slash.txt", "case.txt", "ctl\u0001x.txt", "new\nline.txt", "space 새 이름.txt", "tab\there.txt", "한글 파일.txt", "😀.txt");
    eq(new TreeSet<>(ReverseMerge.parseLog(LOG_NAME_ONLY).keySet()), new TreeSet<>(logNames));
    eq(new TreeSet<>(ReverseMerge.touchedByOthers(LOG_AUTHOR, "나", "me@x")), new TreeSet<>(logNames));
    TreeSet<String> raw = new TreeSet<>();
    for (History.Entry entry : History.parseGitLog(LOG_RAW)) {
      for (History.FileChange file : entry.files()) {
        raw.add(file.path());
        raw.add(file.oldPath());
      }
    }
    eq(raw, new TreeSet<>(List.of(" lead.txt", "#hash%.txt", "-dash.txt", "Case.txt", "a -> b.txt", "a\"q.txt", "back\\slash.txt", "case.txt", "ctl\u0001x.txt", "dir 폴더/in.txt", "new\nline.txt", "space name.txt", "space 새 이름.txt", "tab\there.txt", "한글 파일.txt", "😀.txt")));
    // 감싼 경로 안의 " -> " 는 화살표가 아니다.
    eq(SyncPanel.statusPath("\"a -> b.txt\""), "a -> b.txt");
    eq(SyncPanel.statusPath("\"a\\\"q.txt\" -> \"new\\tq.txt\""), "new\tq.txt");
    eq(SyncPanel.statusPath("old.txt -> new.txt"), "new.txt");
    eq(SyncPanel.pathLine(" lead.txt\r"), " lead.txt");
    System.out.println("GitPathCheck 통과");
  }
}
