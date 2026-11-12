package ei.db.bench;

import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import ei.db.DbKind;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「예제 모음」 탭: 왼쪽 위 찾기 칸 · 목록, 오른쪽 위 설명 · 아래 SQL 칸(작업대가 slot 에 붙인다).
 * 위에서 옛 방식을 고르면 JOIN 을 쉼표 · WHERE 로 바꿔 보인다(JoinStyleConvert.toOldOrSame).
 * 찾기는 묶음 · 제목 · 설명 · 두 DB 의 SQL 글에서 띄어 쓴 낱말을 모두 담은 예제만 남긴다(대소문자 무시).
 */
final class ExamplePanel {
  private static final float SPLIT = 0.35f;

  private final Bench bench;
  private final SearchTextField search = new SearchTextField(false);
  private final JBList<Examples.Example> list = new JBList<>(Examples.ALL);
  private final JBTextArea description = new JBTextArea();
  private final JBSplitter root = new JBSplitter(false, SPLIT);
  /** Examples.ALL 과 같은 순서의 찾기용 소문자 글(묶음 · 제목 · 설명 · 두 DB 의 SQL). 글자를 칠 때마다 다시 만들지 않게 한 번만 만든다. */
  private final List<String> haystacks = new ArrayList<>(Examples.ALL.size());

  ExamplePanel(Bench bench, JComponent slot) {
    this.bench = bench;
    for (Examples.Example e : Examples.ALL) {
      haystacks.add((e.group() + " " + e.title() + " " + e.description() + " " + e.oracle() + " " + e.postgres()).toLowerCase(Locale.ROOT));
    }
    list.setCellRenderer(new ColoredListCellRenderer<>() {
      @Override
      protected void customizeCellRenderer(@NotNull JList<? extends Examples.Example> l, Examples.Example e, int index, boolean selected, boolean focus) {
        append(e.group() + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
        append(e.title());
      }
    });
    list.getEmptyText().setText("맞는 예제가 없습니다");
    list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) refresh(); });
    search.getTextEditor().getEmptyText().setText("찾기 (예: 중복, 누적, MERGE, 날짜)");
    search.addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) { filter(); }
    });
    description.setEditable(false);
    description.setFont(JBFont.label());
    description.setLineWrap(true);
    description.setWrapStyleWord(true);
    description.setOpaque(false);
    description.setBorder(JBUI.Borders.empty(4, 8, 8, 8));
    JPanel left = new JPanel(new BorderLayout(0, JBUI.scale(4)));
    left.add(search, BorderLayout.NORTH);
    left.add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER);
    JPanel right = new JPanel(new BorderLayout());
    right.add(description, BorderLayout.NORTH);
    right.add(slot, BorderLayout.CENTER);
    root.setSplitterProportionKey("ei.db.bench.examples.split");
    root.setHonorComponentsMinimumSize(false);
    root.setFirstComponent(left);
    root.setSecondComponent(right);
  }

  JComponent component() { return root; }

  private void filter() {
    String[] words = search.getText().strip().toLowerCase(Locale.ROOT).split("\\s+");
    Examples.Example keep = list.getSelectedValue();
    List<Examples.Example> shown = new ArrayList<>();
    for (int i = 0; i < Examples.ALL.size(); i++) {
      Examples.Example e = Examples.ALL.get(i);
      String hay = haystacks.get(i);
      boolean all = true;
      for (String w : words) if (!w.isEmpty() && !hay.contains(w)) all = false;
      if (all) shown.add(e);
    }
    list.setListData(shown.toArray(new Examples.Example[0]));
    if (keep != null && shown.contains(keep)) list.setSelectedValue(keep, true);
  }

  void refresh() {
    Examples.Example e = list.getSelectedValue();
    if (e == null) {
      description.setText("왼쪽에서 예제를 고르세요(위 찾기 칸에 낱말을 치면 거릅니다). 예제 " + Examples.ALL.size() + "개. 표 이름(ORDERS · CUSTOMER 등)은 보기입니다.");
      bench.show("", List.of());
      return;
    }
    description.setText(e.title() + "\n" + e.description());
    List<String> notes = new ArrayList<>(List.of("보기 표 이름을 실제 표로 바꿔 쓰세요."));
    String sql = e.sql(bench.kind());
    if (bench.style() == QueryBuilder.JoinStyle.OLD) {
      JoinStyleConvert.Result old = JoinStyleConvert.toOldOrSame(sql, bench.kind() == DbKind.ORACLE);
      sql = old.sql();
      notes.addAll(old.notes());
    }
    bench.show(sql, notes);
  }
}
