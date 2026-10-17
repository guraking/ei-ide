package ei.db.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 콘솔 글을 문장으로 나눈다. 순수 자바(플랫폼 의존 없음).
 * 경계: 따옴표 · 주석 밖의 ; (문장에 포함), 빈 줄, / 만 있는 줄(문장에서 뺀다).
 * PL/SQL 블록(BEGIN · DECLARE · CREATE PROCEDURE/FUNCTION/PACKAGE/TRIGGER/TYPE BODY)은 BEGIN·CASE 와 END 의 짝이
 * 맞은 뒤의 ; 에서만 끝나고 빈 줄로 나누지 않는다. PostgreSQL 의 $$ 본문은 하나의 글자열로 본다.
 * 문장 앞의 공백 · 주석은 문장에 넣지 않는다.
 */
public final class SqlSplitter {
  private SqlSplitter() {}

  /** text 안의 [start, end) 위치와 그 글. */
  public record Stmt(int start, int end, String text) {}

  private static final Set<String> NOT_BLOCK_END = Set.of("IF", "LOOP", "WHILE", "REPEAT");
  private static final Set<String> CREATE_MODIFIERS = Set.of("OR", "REPLACE", "EDITIONABLE", "NONEDITIONABLE");
  private static final Set<String> CREATE_BLOCKS = Set.of("PROCEDURE", "FUNCTION", "PACKAGE", "TRIGGER");

  public static List<Stmt> split(String text) {
    List<Stmt> out = new ArrayList<>();
    Seg seg = new Seg();
    int n = text.length();
    int i = 0;
    while (i < n) {
      char ch = text.charAt(i);
      char next = i + 1 < n ? text.charAt(i + 1) : 0;
      if (ch == '-' && next == '-') {
        i = indexOrEnd(text, "\n", i);
        continue;
      }
      if (ch == '/' && next == '*') {
        int close = text.indexOf("*/", i + 2);
        i = close < 0 ? n : close + 2;
        continue;
      }
      if (ch == '\n') {
        int lineStart = text.lastIndexOf('\n', i - 1) + 1;
        String line = text.substring(lineStart, i).strip();
        if (line.equals("/") || (line.isEmpty() && !seg.block)) {
          seg.flush(text, out, lineStart);
          seg = new Seg();
        }
        i++;
        continue;
      }
      if (Character.isWhitespace(ch)) {
        i++;
        continue;
      }
      seg.code(i);
      if (ch == ';') {
        seg.lastCode = i + 1;
        if (seg.endsAtSemicolon()) {
          seg.flush(text, out, i + 1);
          seg = new Seg();
        } else {
          seg.words.add(";");
        }
        i++;
        continue;
      }
      int end = quotedEnd(text, i);
      if (end > i) {
        if (ch == '$') seg.dollar = true;
        seg.lastCode = end;
        i = end;
        continue;
      }
      if (Character.isLetter(ch) || ch == '_') {
        int j = i + 1;
        while (j < n && isIdent(text.charAt(j))) j++;
        seg.word(text.substring(i, j).toUpperCase(Locale.ROOT));
        seg.lastCode = j;
        i = j;
        continue;
      }
      seg.lastCode = i + 1;
      i++;
    }
    // 마지막 줄의 / 는 줄바꿈 없이 끝날 수 있다.
    int lastLine = text.lastIndexOf('\n') + 1;
    if (text.substring(lastLine).strip().equals("/")) seg.lastCode = Math.min(seg.lastCode, lastLine);
    seg.flush(text, out, n);
    return out;
  }

  /**
   * 커서가 있는 문장. 커서가 문장 안(끝 바로 뒤 포함)이면 그 문장, 앞 문장이 끝난 줄에 있으면 앞 문장,
   * 아니면 뒤 문장, 뒤가 없으면 마지막 문장. 문장이 없으면 null.
   */
  public static Stmt at(String text, int caret) {
    List<Stmt> all = split(text);
    Stmt before = null;
    for (Stmt s : all) {
      if (caret >= s.start && caret <= s.end) return s;
      if (s.end < caret) {
        before = s;
        continue;
      }
      if (before != null && text.lastIndexOf('\n', caret - 1) < before.end) return before;
      return s;
    }
    return before;
  }

  private static int indexOrEnd(String text, String what, int from) {
    int k = text.indexOf(what, from);
    return k < 0 ? text.length() : k;
  }

  private static boolean isIdent(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
  }

  /** i 에서 시작하는 따옴표 글(' " ` q'[ ]' $tag$)의 끝 다음 위치. 따옴표가 아니면 i. 닫히지 않으면 글 끝. */
  private static int quotedEnd(String text, int i) {
    int n = text.length();
    char ch = text.charAt(i);
    boolean wordStart = i == 0 || !isIdent(text.charAt(i - 1));
    if ((ch == 'q' || ch == 'Q') && wordStart && i + 2 < n && text.charAt(i + 1) == '\'') {
      char open = text.charAt(i + 2);
      int k = "([{<".indexOf(open);
      char close = k >= 0 ? ")]}>".charAt(k) : open;
      int end = text.indexOf(close + "'", i + 3);
      return end < 0 ? n : end + 2;
    }
    if (ch == '\'' || ch == '"' || ch == '`') {
      int j = i + 1;
      while (j < n) {
        if (text.charAt(j) == ch) {
          if (j + 1 < n && text.charAt(j + 1) == ch) { j += 2; continue; }
          return j + 1;
        }
        j++;
      }
      return n;
    }
    if (ch == '$' && wordStart) {
      int j = i + 1;
      while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) j++;
      if (j < n && text.charAt(j) == '$') {
        String tag = text.substring(i, j + 1);
        int end = text.indexOf(tag, j + 1);
        return end < 0 ? n : end + tag.length();
      }
    }
    return i;
  }

  /** 문장 하나를 모으는 중의 상태. */
  private static final class Seg {
    int start = -1;
    int lastCode = -1;
    boolean block;
    boolean decided;
    boolean dollar;
    int depth;
    boolean endSeen;
    /** 블록 판정과 END 다음 낱말 확인용. ; 는 ";" 로 들어간다. */
    final List<String> words = new ArrayList<>();
    boolean afterEnd;
    int depthBeforeEnd;

    void code(int i) {
      if (start < 0) start = i;
    }

    void word(String w) {
      words.add(w);
      if (!decided) decide();
      if (!block) return;
      if (afterEnd && NOT_BLOCK_END.contains(w)) {
        // END IF · END LOOP 는 여는 쪽을 세지 않았으므로 되돌린다.
        depth = depthBeforeEnd;
        endSeen = false;
      }
      afterEnd = false;
      switch (w) {
        case "BEGIN", "CASE" -> { depth++; endSeen = false; }
        case "END" -> {
          depthBeforeEnd = depth;
          depth = Math.max(0, depth - 1);
          endSeen = depth == 0;
          afterEnd = true;
        }
        default -> { }
      }
    }

    /** 처음 몇 낱말로 블록인지 정한다. BEGIN 바로 뒤가 ; 이면(트랜잭션 시작) 블록이 아니다. */
    private void decide() {
      String first = words.get(0);
      if (first.equals("BEGIN") || first.equals("DECLARE")) {
        block = true;
        decided = true;
        return;
      }
      if (!first.equals("CREATE")) {
        decided = true;
        return;
      }
      String last = words.get(words.size() - 1);
      if (words.size() == 1 || CREATE_MODIFIERS.contains(last)) return;
      if (last.equals("TYPE")) return;
      if (words.get(words.size() - 2).equals("TYPE")) {
        block = last.equals("BODY");
      } else {
        block = CREATE_BLOCKS.contains(last);
        // 패키지는 AS/IS 가 한 겹을 연다: 패키지 안 프로시저의 END 에서 끝나지 않게 한다.
        if (last.equals("PACKAGE")) depth = 1;
      }
      decided = true;
    }

    boolean endsAtSemicolon() {
      afterEnd = false;
      if (!block || (words.size() == 1 && words.get(0).equals("BEGIN"))) return true;
      return depth == 0 && (endSeen || dollar);
    }

    void flush(String text, List<Stmt> out, int limit) {
      if (start < 0) return;
      int end = Math.min(lastCode, limit);
      while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
      if (end <= start || text.substring(start, end).equals(";")) return;
      out.add(new Stmt(start, end, text.substring(start, end)));
    }
  }
}
