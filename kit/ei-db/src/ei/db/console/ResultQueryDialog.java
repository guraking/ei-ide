package ei.db.console;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.CollectionComboBoxModel;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.FormBuilder;
import ei.db.EiDbBundle;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.ListSelectionModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 결과 표의 「피벗 · 트리 · 중복 · 건수 · 분석」 링크가 여는 고르기 창. 종류(Kind)에 맞는 칸만 보인다.
 * 칼럼은 결과 칼럼 이름 그대로 보이고 고른 것도 그 이름으로 돌려준다(SQL 표기는 부르는 쪽이 한다). 창 크기는 종류별로 기억한다.
 */
final class ResultQueryDialog extends DialogWrapper {
  enum Kind { COUNTS, DUPLICATES, WINDOW, PIVOT, TREE }

  /** 피벗의 집계 함수. */
  private static final List<String> AGGREGATES = List.of("SUM", "COUNT", "MAX", "MIN", "AVG");
  /** 「앞에서 N건만」의 상한. 0 은 자르지 않음. */
  private static final int MAX_TOP = 1000;
  /** 여러 칼럼 고르기 목록에 한 번에 보일 줄 수. */
  private static final int VISIBLE_ROWS = 8;

  private final Kind kind;
  private final JBList<String> picks;
  private final ComboBox<String> first;
  private final ComboBox<String> second;
  private final ComboBox<String> third;
  private final ComboBox<ResultQueries.Window> function = new ComboBox<>(new CollectionComboBoxModel<>(List.of(ResultQueries.Window.values())));
  private final ComboBox<String> aggregate = new ComboBox<>(new CollectionComboBoxModel<>(AGGREGATES));
  /** true: 큰 값부터(분석) · 아래로 내려가기(트리). */
  private final ComboBox<Boolean> direction = new ComboBox<>(new CollectionComboBoxModel<>(List.of(Boolean.TRUE, Boolean.FALSE)));
  private final JBIntSpinner top = new JBIntSpinner(0, 0, MAX_TOP);

  /** columns 는 결과 칼럼 이름(비어 있지 않음), preselected 는 표에서 고른 칼럼(여러 칼럼 목록에 미리 골라 둔다). */
  ResultQueryDialog(Project project, Kind kind, List<String> columns, List<String> preselected) {
    super(project);
    this.kind = kind;
    picks = new JBList<>(columns);
    picks.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    picks.setVisibleRowCount(VISIBLE_ROWS);
    List<Integer> chosen = new ArrayList<>();
    for (String name : preselected) {
      if (columns.contains(name)) chosen.add(columns.indexOf(name));
    }
    int[] indices = new int[chosen.size()];
    for (int i = 0; i < indices.length; i++) indices[i] = chosen.get(i);
    picks.setSelectedIndices(indices);
    first = new ComboBox<>(new CollectionComboBoxModel<>(new ArrayList<>(columns)));
    second = new ComboBox<>(new CollectionComboBoxModel<>(new ArrayList<>(columns)));
    third = new ComboBox<>(new CollectionComboBoxModel<>(new ArrayList<>(columns)));
    if (columns.size() > 1) second.setSelectedIndex(1);
    if (columns.size() > 2) third.setSelectedIndex(2);
    function.setRenderer(SimpleListCellRenderer.create("", f -> EiDbBundle.message("result.query.window." + f.name())));
    direction.setRenderer(SimpleListCellRenderer.create("", d -> EiDbBundle.message(
        (kind == Kind.TREE ? "result.query.tree." : "result.query.order.") + (d ? "first" : "second"))));
    function.addActionListener(e -> enableByFunction());
    enableByFunction();
    setTitle(EiDbBundle.message("result.query.title." + kind.name()));
    setOKButtonText(EiDbBundle.message("result.query.ok"));
    init();
  }

  private void enableByFunction() {
    ResultQueries.Window f = function.getItem();
    second.setEnabled(kind != Kind.WINDOW || f.value);
    top.setEnabled(f.ranking);
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    FormBuilder form = FormBuilder.createFormBuilder();
    JComponent list = ScrollPaneFactory.createScrollPane(picks);
    switch (kind) {
      case COUNTS, DUPLICATES -> form.addLabeledComponentFillVertically(EiDbBundle.message("result.query.columns"), list);
      case WINDOW -> form
          .addLabeledComponent(EiDbBundle.message("result.query.function"), function)
          .addLabeledComponent(EiDbBundle.message("result.query.order"), first)
          .addLabeledComponent(EiDbBundle.message("result.query.direction"), direction)
          .addLabeledComponent(EiDbBundle.message("result.query.value"), second)
          .addLabeledComponent(EiDbBundle.message("result.query.top"), top)
          .addLabeledComponentFillVertically(EiDbBundle.message("result.query.partition"), list);
      case PIVOT -> form
          .addLabeledComponent(EiDbBundle.message("result.query.pivot"), first)
          .addLabeledComponent(EiDbBundle.message("result.query.value"), second)
          .addLabeledComponent(EiDbBundle.message("result.query.aggregate"), aggregate)
          .addLabeledComponentFillVertically(EiDbBundle.message("result.query.rows"), list);
      case TREE -> form
          .addLabeledComponent(EiDbBundle.message("result.query.child"), first)
          .addLabeledComponent(EiDbBundle.message("result.query.parent"), second)
          .addLabeledComponent(EiDbBundle.message("result.query.name"), third)
          .addLabeledComponent(EiDbBundle.message("result.query.direction"), direction)
          .addComponentFillVertically(new javax.swing.JPanel(), 0);
    }
    return form.getPanel();
  }

  @Override
  protected @Nullable ValidationInfo doValidate() {
    if ((kind == Kind.COUNTS || kind == Kind.DUPLICATES) && picks.isSelectionEmpty()) {
      return new ValidationInfo(EiDbBundle.message("result.query.need.columns"), picks);
    }
    if ((kind == Kind.PIVOT || kind == Kind.TREE) && first.getItem().equals(second.getItem())) {
      return new ValidationInfo(EiDbBundle.message("result.query.same.column"), second);
    }
    if (kind == Kind.PIVOT && picks.getSelectedValuesList().contains(first.getItem())) {
      return new ValidationInfo(EiDbBundle.message("result.query.pivot.in.rows"), picks);
    }
    return null;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return kind == Kind.COUNTS || kind == Kind.DUPLICATES ? picks : kind == Kind.WINDOW ? function : first;
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return "ei.db.result.query." + kind.name(); }

  List<String> picked() { return picks.getSelectedValuesList(); }

  String first() { return first.getItem(); }

  String second() { return second.getItem(); }

  String third() { return third.getItem(); }

  ResultQueries.Window function() { return function.getItem(); }

  String aggregate() { return aggregate.getItem(); }

  boolean direction() { return direction.getItem(); }

  int top() { return top.getNumber(); }
}
