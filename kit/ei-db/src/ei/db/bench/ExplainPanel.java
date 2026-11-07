package ei.db.bench;

import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBTextArea;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.util.ui.JBUI;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.function.Supplier;

/** 「쉬운 설명」 탭: 위 SQL 칸의 문장을 절마다 쉬운 말로 풀고, 실행 전 점검에 걸리는 것을 위에 보인다. */
final class ExplainPanel {
  private final Supplier<String> sql;
  private final JPanel root = new JPanel(new BorderLayout());
  private final JBTextArea text = new JBTextArea();

  ExplainPanel(Supplier<String> sql) {
    this.sql = sql;
    text.setEditable(false);
    text.setFont(EditorFontType.getGlobalPlainFont());
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setBorder(JBUI.Borders.empty(8));
    JButton run = new JButton("SQL 설명하기");
    run.addActionListener(e -> refresh());
    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
    top.add(run);
    root.add(top, BorderLayout.NORTH);
    root.add(ScrollPaneFactory.createScrollPane(text), BorderLayout.CENTER);
  }

  JComponent component() { return root; }

  void refresh() {
    String s = sql.get();
    if (s.isBlank()) {
      text.setText("위 SQL 칸에 문장을 넣거나 붙여 넣고 「SQL 설명하기」 를 누르세요.");
      return;
    }
    StringBuilder sb = new StringBuilder();
    java.util.List<SqlLint.Issue> issues = SqlLint.check(s);
    if (!issues.isEmpty()) {
      sb.append("■ 확인할 것\n");
      for (SqlLint.Issue i : issues) sb.append("  · ").append(i.title()).append(": ").append(i.detail()).append('\n');
      sb.append('\n');
    }
    for (SqlExplain.Step step : SqlExplain.explain(s)) {
      sb.append("■ ").append(step.part());
      if (!step.sql().isEmpty()) sb.append("   ").append(step.sql());
      sb.append('\n');
      for (String line : step.lines()) sb.append("  · ").append(line).append('\n');
      sb.append('\n');
    }
    text.setText(sb.toString().stripTrailing());
    text.setCaretPosition(0);
  }
}
