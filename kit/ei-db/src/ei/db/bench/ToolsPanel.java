package ei.db.bench;

import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.console.ConsoleRun;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 「도구」 탭: 붙여 넣은 값으로 IN 목록, DB 바꿔 쓰기, 연결 모양 바꾸기, SELECT → UPDATE · DELETE(바뀔 건수 먼저), WITH 조각 실행.
 * 바꾸는 도구는 아래 SQL 칸의 문장을 읽고 결과를 다시 그 칸에 놓는다. 바꿀 수 없으면 까닭을 알림 줄에 보인다.
 */
final class ToolsPanel {
  private final Bench bench;
  private final Supplier<String> sql;
  private final JBTabbedPane root = new JBTabbedPane();
  private final JBTextArea pasted = new JBTextArea();
  private final JBTextField inColumns = new JBTextField();
  private final JBLabel dmlCount = new JBLabel();
  private final JBList<SqlTools.Cte> cteList = new JBList<>();
  private final JBLabel cteStatus = new JBLabel();
  /** UPDATE · DELETE 로 바꾸기 전의 SELECT(건수는 이것으로 센다). */
  private String dmlSource;

  ToolsPanel(Bench bench, Supplier<String> sql) {
    this.bench = bench;
    this.sql = sql;
    root.addTab("IN 목록 만들기", inTab());
    root.addTab("DB 바꿔 쓰기", dialectTab());
    root.addTab("연결 모양 바꾸기", joinTab());
    root.addTab("SELECT → UPDATE · DELETE", dmlTab());
    root.addTab("WITH 조각 실행", cteTab());
  }

  JComponent component() { return root; }

  private JComponent inTab() {
    pasted.getEmptyText().setText("엑셀 · 결과 창에서 복사한 값을 붙여 넣으세요. 칸이 둘 이상(탭으로 나뉨)이면 (A, B) IN ((…), (…)) 로 만듭니다.");
    inColumns.getEmptyText().setText("예: CUST_ID   또는 두 칸이면  PART_NO, REV");
    JButton make = blue(new JButton("IN 만들기"));
    make.addActionListener(e -> {
      List<String> cols = new ArrayList<>();
      for (String c : inColumns.getText().split(",")) if (!c.isBlank()) cols.add(c.strip());
      try {
        SqlTools.InResult r = SqlTools.inList(pasted.getText(), cols, bench.kind());
        List<String> notes = new ArrayList<>();
        notes.add(String.format(java.util.Locale.ROOT, "값 %,d개", r.count()) + (r.duplicates() > 0 ? ", 겹친 값 " + r.duplicates() + "개는 뺐습니다" : ""));
        if (bench.kind() == DbKind.ORACLE && r.count() > SqlTools.ORACLE_IN_LIMIT) notes.add("Oracle 은 IN 하나에 " + SqlTools.ORACLE_IN_LIMIT + "개까지라 OR 로 나눴습니다.");
        bench.show(r.sql(), notes);
      } catch (IllegalArgumentException ex) {
        bench.show("", List.of(ex.getMessage()));
      }
    });
    JPanel form = FormBuilder.createFormBuilder()
        .addLabeledComponent("칼럼 (쉼표로)", inColumns)
        .addComponent(buttons(make))
        .getPanel();
    JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(6)));
    p.setBorder(JBUI.Borders.empty(8));
    p.add(ScrollPaneFactory.createScrollPane(pasted), BorderLayout.CENTER);
    p.add(form, BorderLayout.SOUTH);
    return p;
  }

  private JComponent dialectTab() {
    JButton toPg = new JButton("Oracle → PostgreSQL");
    toPg.addActionListener(e -> showResult(() -> DialectConvert.convert(sql.get(), DbKind.ORACLE, DbKind.POSTGRES)));
    JButton toOra = new JButton("PostgreSQL → Oracle");
    toOra.addActionListener(e -> showResult(() -> DialectConvert.convert(sql.get(), DbKind.POSTGRES, DbKind.ORACLE)));
    return page("아래 SQL 칸의 문장을 다른 DB 문법으로 바꿉니다(NVL ↔ COALESCE, DECODE → CASE, ROWNUM ↔ LIMIT, (+) → JOIN 등). 바꾼 곳과 손으로 볼 곳은 알림 줄에 나옵니다.",
        buttons(toPg, toOra));
  }

  private JComponent joinTab() {
    JButton toAnsi = new JButton("옛 방식 (+) → 표준 JOIN ON");
    toAnsi.addActionListener(e -> showResult(() -> {
      JoinStyleConvert.Result r = JoinStyleConvert.toAnsi(sql.get());
      return new DialectConvert.Result(r.sql(), r.notes());
    }));
    JButton toOld = new JButton("표준 JOIN ON → 옛 방식 (+)");
    toOld.addActionListener(e -> showResult(() -> {
      JoinStyleConvert.Result r = JoinStyleConvert.toOld(sql.get());
      return new DialectConvert.Result(r.sql(), r.notes());
    }));
    return page("아래 SQL 칸의 연결 모양을 바꿉니다. 옛 방식 (+) 은 Oracle 에서만 됩니다. FULL OUTER JOIN · USING 은 옛 방식으로 바꿀 수 없습니다.", buttons(toAnsi, toOld));
  }

  private JComponent dmlTab() {
    JButton update = new JButton("UPDATE 로");
    update.addActionListener(e -> toDml(true));
    JButton delete = new JButton("DELETE 로");
    delete.addActionListener(e -> toDml(false));
    JButton count = blue(new JButton("바뀔 건수 세기"));
    count.addActionListener(e -> {
      if (dmlSource == null) {
        dmlCount.setText("먼저 UPDATE · DELETE 로 바꾸세요.");
        return;
      }
      dmlCount.setText("세는 중…");
      bench.count(dmlSource, t -> dmlCount.setText("바뀔 행: " + t));
    });
    dmlCount.setForeground(UIUtil.getContextHelpForeground());
    return page("확인한 SELECT 를 같은 조건의 UPDATE · DELETE 로 바꿉니다. 실행 전에 「바뀔 건수 세기」 로 몇 건이 바뀌는지 먼저 보세요. 다른 표 조건은 EXISTS 로 옮깁니다.",
        buttons(update, delete, count), dmlCount);
  }

  private void toDml(boolean update) {
    String select = sql.get();
    try {
      SqlTools.Dml d = update ? SqlTools.toUpdate(select, bench.kind(), bench.bind()) : SqlTools.toDelete(select, bench.kind());
      dmlSource = select;
      dmlCount.setText("「바뀔 건수 세기」 로 먼저 확인하세요.");
      List<String> notes = new ArrayList<>();
      notes.add("대상 표: " + d.table());
      if (!d.note().isEmpty()) notes.add(d.note());
      bench.show(d.sql(), notes);
    } catch (IllegalArgumentException ex) {
      bench.show(select, List.of(ex.getMessage()));
    }
  }

  private JComponent cteTab() {
    JButton read = new JButton("아래 SQL 에서 단계 읽기");
    read.addActionListener(e -> {
      List<SqlTools.Cte> list = SqlTools.ctes(sql.get());
      cteList.setListData(list.toArray(new SqlTools.Cte[0]));
      cteStatus.setText(list.isEmpty() ? "WITH 로 시작하는 문장이 아닙니다." : "단계를 고르고 「이 단계까지 실행」 을 누르세요.");
    });
    JButton run = blue(new JButton("이 단계까지 실행"));
    run.addActionListener(e -> {
      int i = cteList.getSelectedIndex();
      DbConfig c = bench.config();
      if (i < 0 || c == null) {
        cteStatus.setText(c == null ? "접속을 먼저 고르세요." : "단계를 고르세요.");
        return;
      }
      try {
        ConsoleRun.runSql(bench.project, c, SqlTools.upTo(sql.get(), i));
      } catch (IllegalArgumentException ex) {
        cteStatus.setText("SQL 이 바뀌었습니다. 단계를 다시 읽으세요.");
      }
    });
    JButton show = new JButton("문장만 보기");
    show.addActionListener(e -> {
      int i = cteList.getSelectedIndex();
      if (i < 0) return;
      try {
        bench.show(SqlTools.upTo(sql.get(), i), List.of("이 단계 결과만 보는 문장입니다."));
      } catch (IllegalArgumentException ex) {
        cteStatus.setText("SQL 이 바뀌었습니다. 단계를 다시 읽으세요.");
      }
    });
    cteList.setCellRenderer(com.intellij.ui.SimpleListCellRenderer.create("", SqlTools.Cte::name));
    cteList.getEmptyText().setText("「아래 SQL 에서 단계 읽기」 를 누르세요");
    JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(6)));
    p.setBorder(JBUI.Borders.empty(8));
    JPanel top = new JPanel(new BorderLayout());
    top.add(new JBLabel("WITH 로 나눈 긴 쿼리를 단계마다 실행해 중간 결과를 봅니다. 어느 단계에서 건수가 틀어지는지 찾을 때 씁니다."), BorderLayout.NORTH);
    top.add(buttons(read, run, show), BorderLayout.SOUTH);
    p.add(top, BorderLayout.NORTH);
    p.add(ScrollPaneFactory.createScrollPane(cteList), BorderLayout.CENTER);
    p.add(cteStatus, BorderLayout.SOUTH);
    return p;
  }

  private interface Convert { DialectConvert.Result run(); }

  private void showResult(Convert c) {
    if (sql.get().isBlank()) {
      bench.show("", List.of("아래 SQL 칸에 바꿀 문장을 넣으세요."));
      return;
    }
    try {
      DialectConvert.Result r = c.run();
      bench.show(r.sql(), r.notes().isEmpty() ? List.of("바꿀 곳이 없었습니다.") : r.notes());
    } catch (IllegalArgumentException ex) {
      bench.show(sql.get(), List.of(ex.getMessage()));
    }
  }

  private static JComponent page(String text, JComponent... rows) {
    JBLabel label = new JBLabel(text);
    label.setAllowAutoWrapping(true);
    FormBuilder f = FormBuilder.createFormBuilder().addComponent(label);
    for (JComponent r : rows) f.addComponent(r);
    JPanel p = f.addComponentFillVertically(new JPanel(), 0).getPanel();
    p.setBorder(JBUI.Borders.empty(8));
    return p;
  }

  static JPanel buttons(JComponent... items) {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
    for (JComponent c : items) p.add(c);
    return p;
  }

  /** 주요 단추: 파란 바탕 흰 글. */
  static JButton blue(JButton b) {
    b.putClientProperty(com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI.DEFAULT_STYLE_KEY, true);
    return b;
  }
}
