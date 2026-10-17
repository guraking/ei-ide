package ei.db;

import java.util.List;

/**
 * 한 문장의 실행 결과. SELECT 면 columns · rows, 변경 문장이면 updateCount(≥0) 이고 columns 는 비어 있다.
 * rows 는 읽기 건수에서 자르고 truncated 로 알린다. binds 는 「더 보기」 때 같은 문장을 다시 실행하려고 둔다(없으면 빈 목록). comments 는 칼럼 이름 → 한글 주석(모르면 없음).
 */
public record QueryResult(String sql,
                          List<String> columns,
                          List<String> columnTypes,
                          List<List<Object>> rows,
                          int updateCount,
                          long millis,
                          boolean truncated,
                          java.util.Map<String, String> comments,
                          List<Object> binds) {

  public boolean isQuery() { return !columns.isEmpty(); }

  /**
   * 이진 칼럼(byte[] · BLOB) 값 자리. 값은 읽지 않고 길이만 둔다. 복사 · INSERT 만들기는 이 값을 실제 값으로 쓰지 말 것.
   */
  public record BinaryValue(long length) {
    @Override public String toString() { return "(이진 " + length + "바이트)"; }
  }
}
