package ei.db;

import java.util.List;

/**
 * 한 문장의 실행 결과. SELECT 면 columns · rows, 변경 문장이면 updateCount(≥0) 이고 columns 는 비어 있다.
 * rows 는 maxRows 에서 자르고 truncated 로 알린다. comments 는 칼럼 이름 → 한글 주석(모르면 없음).
 */
public record QueryResult(String sql,
                          List<String> columns,
                          List<String> columnTypes,
                          List<List<Object>> rows,
                          int updateCount,
                          long millis,
                          boolean truncated,
                          java.util.Map<String, String> comments) {

  public boolean isQuery() { return !columns.isEmpty(); }
}
