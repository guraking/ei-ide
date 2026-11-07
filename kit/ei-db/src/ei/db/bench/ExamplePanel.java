package ei.db.bench;

import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JList;
import java.util.List;

/** 「예제 모음」 탭: 왼쪽 목록에서 고르면 오른쪽에 설명, 아래 미리 보기에 지금 DB 에 맞는 문장이 나온다. */
final class ExamplePanel {
  private static final float SPLIT = 0.4f;

  private final Bench bench;
  private final JBList<Examples.Example> list = new JBList<>(Examples.ALL);
  private final JBTextArea description = new JBTextArea();
  private final JBSplitter root = new JBSplitter(false, SPLIT);

  ExamplePanel(Bench bench) {
    this.bench = bench;
    list.setCellRenderer(new ColoredListCellRenderer<>() {
      @Override
      protected void customizeCellRenderer(@NotNull JList<? extends Examples.Example> l, Examples.Example e, int index, boolean selected, boolean focus) {
        append(e.group() + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
        append(e.title());
      }
    });
    list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) refresh(); });
    description.setEditable(false);
    description.setLineWrap(true);
    description.setWrapStyleWord(true);
    description.setBorder(JBUI.Borders.empty(8));
    root.setSplitterProportionKey("ei.db.bench.examples.split");
    root.setHonorComponentsMinimumSize(false);
    root.setFirstComponent(ScrollPaneFactory.createScrollPane(list));
    root.setSecondComponent(ScrollPaneFactory.createScrollPane(description));
  }

  JComponent component() { return root; }

  void refresh() {
    Examples.Example e = list.getSelectedValue();
    if (e == null) {
      description.setText("왼쪽에서 예제를 고르세요. 표 이름(ORDERS · CUSTOMER 등)은 보기입니다. 편집기에 넣은 뒤 바꿔 쓰세요.");
      bench.show("", List.of());
      return;
    }
    description.setText(e.title() + "\n\n" + e.description());
    bench.show(e.sql(bench.kind()), List.of("보기 표 이름을 실제 표로 바꿔 쓰세요."));
  }
}
