package ei.db.console;

import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.JBUI;
import ei.db.EiDbBundle;
import ei.db.QueryResult;

import javax.swing.DefaultListModel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * 한 접속의 변경 문장(INSERT · UPDATE · DELETE · DDL) 결과를 실행 순서대로 한 줄씩 쌓아 보이는 탭 내용.
 * 문장마다 탭을 만들지 않으려고 둔다. 머리줄은 쌓인 문장 수와 바뀐 행 수의 합. 화면 스레드에서만 쓴다.
 */
final class ChangeLog extends JPanel {
  /** 넘으면 오래된 줄부터 지운다. 머리줄의 합계는 지운 줄도 센다. */
  private static final int MAX_LINES = 2000;
  /** 한 줄에 보일 문장 앞부분의 글자 수. */
  private static final int SQL_PREVIEW = 160;

  private final DefaultListModel<String> lines = new DefaultListModel<>();
  private final JBList<String> list = new JBList<>(lines);
  private final JBLabel total = new JBLabel();
  private int statements;
  private long rows;

  ChangeLog() {
    super(new BorderLayout());
    total.setBorder(JBUI.Borders.empty(8, 12));
    list.setBorder(JBUI.Borders.empty(0, 8));
    add(total, BorderLayout.NORTH);
    add(ScrollPaneFactory.createScrollPane(list, true), BorderLayout.CENTER);
  }

  /** 결과 한 줄을 끝에 더하고 그 줄이 보이게 내린다. pending 은 커밋 전(자동 커밋이 꺼진 접속)인지. */
  void add(QueryResult result, boolean pending) {
    int count = Math.max(result.updateCount(), 0);
    statements++;
    rows += count;
    String sql = result.sql() == null ? "" : result.sql().strip().replaceAll("\\s+", " ");
    if (sql.length() > SQL_PREVIEW) sql = sql.substring(0, SQL_PREVIEW) + "…";
    lines.addElement(EiDbBundle.message(pending ? "result.updated.pending" : "result.updated.auto", count) + " · " + result.millis() + " ms · " + sql);
    while (lines.size() > MAX_LINES) lines.remove(0);
    total.setText(EiDbBundle.message("result.change.total", statements, rows));
    list.ensureIndexIsVisible(lines.size() - 1);
  }
}
