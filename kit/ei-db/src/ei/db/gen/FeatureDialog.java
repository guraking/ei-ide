package ei.db.gen;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 표 하나로 기능 한 벌(매퍼 XML · 매퍼 인터페이스 · DTO · 서비스 · 컨트롤러)을 만든다.
 * 이미 있는 파일은 덮어쓰지 않는다: 파일마다 덧붙이기(없는 문장 · 메서드만) 또는 건너뛰기를 고르고,
 * 덧붙일 수 없는 파일(DTO, 부르는 필드가 없는 서비스 · 컨트롤러)은 건너뛰고 알린다.
 */
public final class FeatureDialog extends DialogWrapper {

  private static final Pattern FQN = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");
  private static final Pattern URL = Pattern.compile("(/[A-Za-z0-9_.\\-]+)+");
  private static final String DTO_PACKAGE = "dto";
  private static final String SERVICE_PACKAGE = "service";
  private static final String IMPL_PACKAGE = "impl";
  private static final String CONTROLLER_PACKAGE = "controller";
  private static final int LEFT_WIDTH = 620;
  private static final int PREVIEW_WIDTH = 900;
  private static final int HEIGHT = 820;
  /** 칼럼 표가 위 칸들에 밀려 사라지지 않도록 잡아 두는 높이. 넘치면 왼쪽 전체가 스크롤된다. */
  private static final int COLUMN_TABLE_HEIGHT = 260;
  private static final int SCROLL_UNIT = 16;
  private static final int REFRESH_DELAY_MS = 300;
  private static final int COL_SEARCH = 0;
  private static final int COL_TAG = 1;
  private static final int COL_NAME = 2;
  private static final int COL_TYPE = 3;
  /** 미리보기 탭 순서. */
  private static final FeatureGen.Out[] TAB_OUTS = {
      FeatureGen.Out.XML, FeatureGen.Out.MAPPER, FeatureGen.Out.DTO,
      FeatureGen.Out.SERVICE, FeatureGen.Out.SERVICE_IMPL, FeatureGen.Out.CONTROLLER};
  private static final String[] TAB_TITLES = {"XML", EiDbBundle.message("mapper.tab.interface"), "DTO", EiDbBundle.message("connect.service"), EiDbBundle.message("feature.service.impl"), EiDbBundle.message("feature.controller")};
  private static final List<MapperGen.Kind> METHOD_KINDS = List.of(
      MapperGen.Kind.LIST, MapperGen.Kind.ONE, MapperGen.Kind.INSERT, MapperGen.Kind.UPDATE, MapperGen.Kind.DELETE);

  private final Project project;
  private final DbConfig config;
  private final String table;
  private final List<Meta.Column> cols;
  private final Targets.Roots roots;

  private final JBTextField mapperField = new JBTextField();
  private final JBTextField xmlField = new JBTextField();
  private final JBTextField dtoField = new JBTextField();
  private final JBCheckBox makeDto = new JBCheckBox(EiDbBundle.message("mapper.make.dto"), true);
  private final JBTextField serviceField = new JBTextField();
  private final JBCheckBox split = new JBCheckBox(EiDbBundle.message("feature.split"), false);
  private final JBTextField implField = new JBTextField();
  private final JBTextField controllerField = new JBTextField();
  private final JBCheckBox mvc = new JBCheckBox(EiDbBundle.message("feature.mvc"), false);
  private final JBTextField urlField = new JBTextField();
  private final Map<MapperGen.Kind, JBCheckBox> kindBoxes = new EnumMap<>(MapperGen.Kind.class);
  private final JBCheckBox oldJoin = new JBCheckBox(EiDbBundle.message("mapper.old.join"), true);
  private final JBCheckBox cdata = new JBCheckBox(EiDbBundle.message("mapper.cdata"), true);
  private final JBCheckBox where11 = new JBCheckBox(EiDbBundle.message("mapper.where11"), true);
  private final JBCheckBox sqlComments = new JBCheckBox(EiDbBundle.message("mapper.sql.comments"), true);
  private DefaultTableModel columnModel;

  private final JBTabbedPane tabs = new JBTabbedPane();
  private final JBLabel fileLabel = new JBLabel();
  private final JBLabel badge = new JBLabel();
  private final ComboBox<String> existingChoice = new ComboBox<>(new String[] {EiDbBundle.message("feature.existing.merge"), EiDbBundle.message("feature.existing.skip")});
  private final Map<FeatureGen.Out, Preview> previews = new EnumMap<>(FeatureGen.Out.class);
  /** 있는 파일을 건너뛰기로 고른 출력. */
  private final Set<FeatureGen.Out> skipped = EnumSet.noneOf(FeatureGen.Out.class);
  private final Map<FeatureGen.Out, Plan> shown = new EnumMap<>(FeatureGen.Out.class);
  private final Timer refreshTimer = new Timer(REFRESH_DELAY_MS, e -> doRefresh());
  /** 콤보를 코드로 맞출 때 선택 변경을 사용자 선택으로 받지 않는다. */
  private boolean syncingChoice;

  public static void show(Project project, DbConfig c, String schema, String table) {
    new Task.Backgroundable(project, EiDbBundle.message("validation.progress", table), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          Connection conn = DbSessions.get(project).connection(c);
          List<Meta.Column> cols = Meta.columns(conn, c, schema, table);
          Targets.Roots roots = ReadAction.compute(() -> Targets.find(project));
          ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            if (cols.isEmpty()) {
              Notify.error(project, EiDbBundle.message("mapper.no.columns", schema + "." + table));
              return;
            }
            new FeatureDialog(project, c, schema, table, cols, roots).show();
          });
        } catch (ProcessCanceledException e) {
          throw e;
        } catch (Exception e) {
          Notify.error(project, EiDbBundle.message("validation.read.failed", table, e.getMessage()));
        }
      }
    }.queue();
  }

  private FeatureDialog(Project project, DbConfig c, String schema, String table, List<Meta.Column> cols, Targets.Roots roots) {
    super(project, true);
    this.project = project;
    this.config = c;
    this.table = table;
    this.cols = cols;
    this.roots = roots;
    String entity = Names.pascal(table);
    String mapperPkg = roots.basePackage() + ".mapper";
    String base = layerBase(mapperPkg);
    mapperField.setText(mapperPkg + "." + entity + "Mapper");
    xmlField.setText(roots.rel(roots.resources() + "/mapper/" + table.toLowerCase(Locale.ROOT) + "/" + entity + "Mapper.xml"));
    dtoField.setText(entity + "Dto");
    serviceField.setText(join(base, SERVICE_PACKAGE) + "." + entity + "Service");
    implField.setText(join(base, SERVICE_PACKAGE + "." + IMPL_PACKAGE) + "." + entity + "ServiceImpl");
    controllerField.setText(join(base, CONTROLLER_PACKAGE) + "." + entity + "Controller");
    urlField.setText(FeatureGen.defaultBaseUrl(table));
    setTitle(EiDbBundle.message("feature.title", table, c.name, schema));
    setOKButtonText(EiDbBundle.message("common.generate"));
    setCancelButtonText(EiDbBundle.message("common.cancel"));
    refreshTimer.setRepeats(false);
    init();
    doRefresh();
  }

  /** 패키지 끝 마디가 층 이름(mapper 등)이면 그 위, 아니면 그대로. */
  private static String layerBase(String pkg) {
    return Targets.LAYER_SEGMENTS.contains(Names.simple(pkg)) ? Names.packageOf(pkg) : pkg;
  }

  private static String join(String base, String sub) {
    return base.isEmpty() ? sub : base + "." + sub;
  }

  @Override
  protected JComponent createCenterPanel() {
    for (int i = 0; i < TAB_OUTS.length; i++) {
      Preview p = new Preview(project, TAB_OUTS[i] == FeatureGen.Out.XML ? "xml" : "java", getDisposable());
      previews.put(TAB_OUTS[i], p);
      tabs.addTab(TAB_TITLES[i], p.component());
    }
    tabs.addChangeListener(e -> showHead());
    existingChoice.addActionListener(e -> {
      if (syncingChoice) return;
      FeatureGen.Out o = TAB_OUTS[tabs.getSelectedIndex()];
      if (EiDbBundle.message("feature.existing.skip").equals(existingChoice.getSelectedItem())) skipped.add(o); else skipped.remove(o);
      refresh();
    });

    for (JBTextField f : List.of(mapperField, xmlField, dtoField, serviceField, implField, controllerField, urlField)) {
      f.setFont(Ui.mono());
      Ui.onChange(f, this::refresh);
    }
    for (JBCheckBox b : List.of(makeDto, split, mvc, cdata, where11, sqlComments)) b.addActionListener(e -> refresh());
    split.addActionListener(e -> implField.setEnabled(split.isSelected()));
    implField.setEnabled(false);
    oldJoin.setEnabled(false);
    oldJoin.setToolTipText(EiDbBundle.message("mapper.old.join.tooltip"));

    JPanel dtoRow = new JPanel(new BorderLayout(JBUI.scale(12), 0));
    dtoRow.add(dtoField, BorderLayout.CENTER);
    dtoRow.add(makeDto, BorderLayout.EAST);

    FormBuilder form = FormBuilder.createFormBuilder()
        .addLabeledComponent(EiDbBundle.message("mapper.interface"), mapperField, true)
        .addLabeledComponent(EiDbBundle.message("mapper.xml.location"), xmlField, true)
        .addLabeledComponent("DTO", dtoRow, true)
        .addLabeledComponent(EiDbBundle.message("connect.service"), serviceField, true)
        .addComponent(split)
        .addLabeledComponent(EiDbBundle.message("feature.service.impl"), implField, true)
        .addLabeledComponent(EiDbBundle.message("feature.controller"), controllerField, true)
        .addComponent(mvc)
        .addLabeledComponent(EiDbBundle.message("feature.base.url"), urlField, true)
        .addLabeledComponent(EiDbBundle.message("mapper.statements"), statements(), true)
        .addLabeledComponent(EiDbBundle.message("mapper.style"), stack(oldJoin, cdata, where11, sqlComments), true);
    String note = gen().missingNote();
    if (note != null) {
      JBLabel warn = new JBLabel(note, AllIcons.General.Warning, JBLabel.LEFT);
      warn.setForeground(Ui.muted());
      form.addComponent(warn);
    }
    JPanel left = form
        .addLabeledComponentFillVertically(EiDbBundle.message("feature.columns.label", table, cols.size()), columnTable())
        .getPanel();
    left.setBorder(JBUI.Borders.emptyRight(12));
    WidthTracking leftBody = new WidthTracking();
    leftBody.add(left, BorderLayout.CENTER);
    JBScrollPane leftScroll = new JBScrollPane(leftBody, JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    leftScroll.setBorder(JBUI.Borders.empty());
    leftScroll.setPreferredSize(new Dimension(JBUI.scale(LEFT_WIDTH), JBUI.scale(HEIGHT)));

    JPanel head = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0));
    fileLabel.setFont(Ui.mono());
    fileLabel.setForeground(Ui.muted());
    head.add(fileLabel);
    head.add(badge);
    head.add(existingChoice);
    existingChoice.setVisible(false);
    JPanel right = new JPanel(new BorderLayout(0, JBUI.scale(6)));
    right.add(head, BorderLayout.NORTH);
    right.add(tabs, BorderLayout.CENTER);
    right.setPreferredSize(new Dimension(JBUI.scale(PREVIEW_WIDTH), JBUI.scale(HEIGHT)));
    right.setBorder(JBUI.Borders.emptyLeft(12));

    JPanel root = new JPanel(new BorderLayout());
    root.add(leftScroll, BorderLayout.WEST);
    root.add(right, BorderLayout.CENTER);
    return root;
  }

  @Override
  protected JPanel createSouthAdditionalPanel() {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    JBLabel note = new JBLabel(EiDbBundle.message("feature.append.note"), AllIcons.General.Information, JBLabel.LEFT);
    note.setForeground(Ui.muted());
    p.add(note);
    return p;
  }

  private JComponent statements() {
    MapperGen probe = gen().mapper();
    JPanel p = new JPanel();
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    for (MapperGen.Kind k : MapperGen.Kind.values()) {
      JBCheckBox box = new JBCheckBox(k == MapperGen.Kind.RESULT_MAP ? "resultMap " + probe.id(k) : probe.id(k), k != MapperGen.Kind.MERGE);
      box.setFont(Ui.mono());
      String hint = switch (k) {
        case LIST -> EiDbBundle.message("feature.hint.list");
        case ONE -> EiDbBundle.message("feature.hint.one");
        case INSERT -> EiDbBundle.message("feature.hint.insert");
        case UPDATE -> EiDbBundle.message("feature.hint.update");
        case DELETE -> EiDbBundle.message("feature.hint.delete");
        case MERGE -> EiDbBundle.message("mapper.hint.merge");
        case RESULT_MAP -> EiDbBundle.message("mapper.hint.result.map");
      };
      if (!probe.possible(k)) {
        box.setSelected(false);
        box.setEnabled(false);
        hint = EiDbBundle.message(probe.hasPk() ? "mapper.hint.no.columns" : "mapper.hint.no.pk");
      }
      box.addActionListener(e -> refresh());
      kindBoxes.put(k, box);
      JBLabel h = new JBLabel(hint);
      h.setForeground(Ui.muted());
      JPanel row = new JPanel(new BorderLayout());
      row.add(box, BorderLayout.CENTER);
      row.add(h, BorderLayout.EAST);
      p.add(row);
    }
    return p;
  }

  private static JComponent stack(JComponent... items) {
    JPanel p = new JPanel();
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    for (JComponent c : items) p.add(c);
    return p;
  }

  private JComponent columnTable() {
    columnModel = new DefaultTableModel(new Object[] {EiDbBundle.message("feature.col.search"), "", EiDbBundle.message("common.col.column"), EiDbBundle.message("common.col.type")}, 0) {
      @Override
      public boolean isCellEditable(int row, int column) { return column == COL_SEARCH; }

      @Override
      public Class<?> getColumnClass(int column) { return column == COL_SEARCH ? Boolean.class : Object.class; }
    };
    for (Meta.Column c : cols) columnModel.addRow(new Object[] {false, c.pk() ? "PK" : c.fk() ? "FK" : "", c.name(), c.typeLabel()});
    columnModel.addTableModelListener(e -> refresh());
    JBTable t = new JBTable(columnModel);
    t.setShowGrid(false);
    t.getColumnModel().getColumn(COL_SEARCH).setMaxWidth(JBUI.scale(44));
    t.getColumnModel().getColumn(COL_TAG).setMaxWidth(JBUI.scale(28));
    t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
      @Override
      public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
        super.getTableCellRendererComponent(table, value, selected, false, row, column);
        setFont(column == COL_NAME || column == COL_TYPE ? Ui.mono() : table.getFont());
        if (!selected) {
          setForeground(column == COL_TAG ? ("PK".equals(value) ? Ui.PK : Ui.FK) : column == COL_NAME ? table.getForeground() : Ui.muted());
        }
        return this;
      }
    });
    JBScrollPane sp = new JBScrollPane(t);
    sp.setBorder(JBUI.Borders.empty());
    sp.setPreferredSize(new Dimension(JBUI.scale(LEFT_WIDTH), JBUI.scale(COLUMN_TABLE_HEIGHT)));
    sp.setMinimumSize(sp.getPreferredSize());
    return sp;
  }

  // ---- 생성 ----

  private Set<MapperGen.Kind> kinds() {
    Set<MapperGen.Kind> out = EnumSet.noneOf(MapperGen.Kind.class);
    for (Map.Entry<MapperGen.Kind, JBCheckBox> e : kindBoxes.entrySet()) if (e.getValue().isSelected()) out.add(e.getKey());
    // 화면이 다 만들어지기 전(문장 칸 없음)에는 모든 문장으로 본다.
    return kindBoxes.isEmpty() ? MapperGen.allKinds() : out;
  }

  private Set<String> searchColumns() {
    Set<String> out = new HashSet<>();
    if (columnModel == null) return out;
    for (int i = 0; i < columnModel.getRowCount(); i++) {
      if (Boolean.TRUE.equals(columnModel.getValueAt(i, COL_SEARCH))) out.add((String) columnModel.getValueAt(i, COL_NAME));
    }
    return out;
  }

  /** DTO 칸에 점이 있으면 FQN 그대로, 아니면 매퍼 패키지의 층 마디(mapper 등)를 dto 로 바꾼 패키지. */
  private String dtoType() {
    String dto = dtoField.getText().strip();
    if (dto.contains(".")) return dto;
    return join(layerBase(Names.packageOf(mapperField.getText().strip())), DTO_PACKAGE) + "." + dto;
  }

  private String baseUrl() {
    String u = urlField.getText().strip();
    while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
    return u;
  }

  private FeatureGen gen() {
    MapperGen.Options style = new MapperGen.Options(oldJoin.isSelected(), cdata.isSelected(), where11.isSelected(),
                                                    sqlComments.isSelected(), config.kind);
    return new FeatureGen(new FeatureGen.Spec(table, cols, style, kinds(), dtoType(), mapperField.getText().strip(),
        serviceField.getText().strip(), split.isSelected() ? implField.getText().strip() : null,
        controllerField.getText().strip(), !mvc.isSelected(), baseUrl(), searchColumns()));
  }

  /**
   * 출력 하나의 할 일. path 가 null 이면 만들지 않는 출력. exists 는 파일이 이미 있는지, mergeable 은 덧붙이기를 고를 수 있는지.
   * job 이 null 이면 쓰지 않는다(건너뜀 · 덧붙일 것 없음).
   */
  private record Plan(String path, boolean exists, boolean mergeable, String preview, Targets.Job job, String report) {}

  private String path(FeatureGen.Out o) {
    return switch (o) {
      case DTO -> makeDto.isSelected() ? roots.javaFile(dtoType()) : null;
      case XML -> roots.abs(xmlField.getText());
      case MAPPER -> roots.javaFile(mapperField.getText().strip());
      case SERVICE -> roots.javaFile(serviceField.getText().strip());
      case SERVICE_IMPL -> split.isSelected() ? roots.javaFile(implField.getText().strip()) : null;
      case CONTROLLER -> roots.javaFile(controllerField.getText().strip());
    };
  }

  private Plan plan(FeatureGen g, FeatureGen.Out o) throws IOException {
    String path = path(o);
    if (path == null) {
      String why = EiDbBundle.message(o == FeatureGen.Out.DTO ? "feature.why.no.dto" : "feature.why.no.split");
      return new Plan(null, false, false, note(o, why), null, null);
    }
    String rel = roots.rel(path);
    String existing = Targets.read(path);
    if (existing == null) {
      String text = g.file(o);
      return new Plan(path, false, false, text, new Targets.Job(path, text, false), EiDbBundle.message("common.file.created", rel));
    }
    String cannot = g.cannotMerge(o, existing);
    if (cannot != null) {
      String msg = EiDbBundle.message("feature.exists.skipped.why", rel, cannot);
      return new Plan(path, true, false, note(o, msg), null, msg);
    }
    if (skipped.contains(o)) {
      String msg = EiDbBundle.message("feature.exists.skipped", rel);
      return new Plan(path, true, true, note(o, msg), null, msg);
    }
    Merge.Result r = g.merge(o, existing);
    if (r.text() == null) {
      String msg = EiDbBundle.message("feature.no.place", rel);
      return new Plan(path, true, true, note(o, msg), null, msg);
    }
    List<MapperGen.Block> blocks = o == FeatureGen.Out.XML ? g.xmlBlocks() : g.blocks(o);
    String report = EiDbBundle.message("mapper.summary", rel, r.added().size())
        + (r.skipped().isEmpty() ? "" : EiDbBundle.message("mapper.summary.skipped", String.join(", ", r.skipped())));
    Targets.Job job = r.added().isEmpty() ? null : new Targets.Job(path, r.text(), true);
    return new Plan(path, true, true, addedOnly(o, blocks, r), job, report);
  }

  /** 있는 파일에는 덧붙을 블록만 보여 준다. */
  private static String addedOnly(FeatureGen.Out o, List<MapperGen.Block> blocks, Merge.Result r) {
    StringBuilder b = new StringBuilder();
    if (!r.skipped().isEmpty()) b.append(note(o, EiDbBundle.message("feature.note.skipped", String.join(", ", r.skipped())))).append('\n');
    for (MapperGen.Block bl : blocks) if (r.added().contains(bl.id())) b.append(bl.text()).append('\n');
    return r.added().isEmpty() ? b.append(note(o, EiDbBundle.message("feature.note.nothing"))).append('\n').toString() : b.toString();
  }

  private static String note(FeatureGen.Out o, String text) {
    return o == FeatureGen.Out.XML ? "<!-- " + Xml.comment(text) + " -->" : "// " + text;
  }

  private void refresh() {
    refreshTimer.restart();
  }

  /** 미리보기는 화면 스레드에서 다시 만든다(입력이 멈춘 뒤 한 번). 못 읽은 파일은 그 탭에 까닭을 보인다. */
  private void doRefresh() {
    if (previews.isEmpty() || isDisposed()) return;
    FeatureGen g;
    try {
      g = gen();
      for (FeatureGen.Out o : TAB_OUTS) path(o);
    } catch (InvalidPathException e) {
      shown.clear();
      fileLabel.setText(EiDbBundle.message("mapper.bad.path"));
      badge.setText("");
      existingChoice.setVisible(false);
      return;
    }
    for (FeatureGen.Out o : TAB_OUTS) {
      Plan p;
      try {
        p = plan(g, o);
      } catch (IOException e) {
        p = new Plan(path(o), true, false, note(o, EiDbBundle.message("feature.read.failed", e.getMessage())), null, null);
      }
      shown.put(o, p);
      previews.get(o).setText(p.preview());
    }
    showHead();
  }

  private void showHead() {
    Plan p = shown.get(TAB_OUTS[tabs.getSelectedIndex()]);
    if (p == null) return;
    if (p.path() == null) {
      fileLabel.setText("");
      badge.setText(EiDbBundle.message("feature.badge.none"));
      badge.setForeground(Ui.muted());
      existingChoice.setVisible(false);
      return;
    }
    fileLabel.setText(p.path().substring(p.path().lastIndexOf('/') + 1));
    badge.setText(EiDbBundle.message(p.exists() ? (p.mergeable() ? "mapper.badge.existing" : "feature.badge.existing.skipped") : "mapper.badge.new"));
    badge.setForeground(p.exists() ? Ui.muted() : Ui.SUCCESS);
    syncingChoice = true;
    existingChoice.setSelectedItem(skipped.contains(TAB_OUTS[tabs.getSelectedIndex()]) ? EiDbBundle.message("feature.existing.skip") : EiDbBundle.message("feature.existing.merge"));
    syncingChoice = false;
    existingChoice.setVisible(p.exists() && p.mergeable());
  }

  /** 운영체제가 받지 않는 글자(윈도의 < > : " | ? * 등)가 있으면 거짓. */
  private boolean validPath(String path) {
    try {
      Path.of(roots.abs(path));
      return true;
    } catch (InvalidPathException e) {
      return false;
    }
  }

  @Override
  protected ValidationInfo doValidate() {
    if (!FQN.matcher(mapperField.getText().strip()).matches()) return new ValidationInfo(EiDbBundle.message("mapper.interface.invalid"), mapperField);
    if (!xmlField.getText().strip().endsWith(".xml")) return new ValidationInfo(EiDbBundle.message("mapper.xml.invalid"), xmlField);
    if (!validPath(xmlField.getText())) return new ValidationInfo(EiDbBundle.message("mapper.bad.path.sentence"), xmlField);
    if (!FQN.matcher(dtoField.getText().strip()).matches()) return new ValidationInfo(EiDbBundle.message("mapper.dto.invalid"), dtoField);
    if (!FQN.matcher(serviceField.getText().strip()).matches()) return new ValidationInfo(EiDbBundle.message("feature.service.invalid"), serviceField);
    if (split.isSelected() && !FQN.matcher(implField.getText().strip()).matches()) {
      return new ValidationInfo(EiDbBundle.message("feature.impl.invalid"), implField);
    }
    if (!FQN.matcher(controllerField.getText().strip()).matches()) return new ValidationInfo(EiDbBundle.message("feature.controller.invalid"), controllerField);
    if (!URL.matcher(baseUrl()).matches()) return new ValidationInfo(EiDbBundle.message("feature.url.invalid"), urlField);
    Set<String> types = new LinkedHashSet<>();
    List<String> all = new ArrayList<>(List.of(dtoType(), mapperField.getText().strip(), serviceField.getText().strip(),
                                               controllerField.getText().strip()));
    if (split.isSelected()) all.add(implField.getText().strip());
    for (String t : all) {
      if (!types.add(t)) return new ValidationInfo(EiDbBundle.message("feature.duplicate.class", t));
    }
    boolean anyMethod = false;
    Set<MapperGen.Kind> kinds = kinds();
    for (MapperGen.Kind k : METHOD_KINDS) anyMethod |= kinds.contains(k);
    if (!anyMethod) return new ValidationInfo(EiDbBundle.message("feature.no.statements"));
    return null;
  }

  @Override
  protected void dispose() {
    refreshTimer.stop();
    super.dispose();
  }

  /** 파일을 다시 읽어 할 일을 만든 뒤 한 번에 쓴다. 이미 있는 파일은 덧붙이기만 하고 덮어쓰지 않는다. */
  @Override
  protected void doOKAction() {
    FeatureGen g = gen();
    List<Targets.Job> jobs = new ArrayList<>();
    List<String> report = new ArrayList<>();
    try {
      for (FeatureGen.Out o : TAB_OUTS) {
        Plan p = plan(g, o);
        if (p.job() != null) jobs.add(p.job());
        if (p.report() != null) report.add(p.report());
      }
    } catch (IOException | InvalidPathException e) {
      Notify.error(project, EiDbBundle.message("feature.read.failed.none", e.getMessage()));
      return;
    }
    try {
      Targets.write(project, EiDbBundle.message("feature.command"), jobs);
    } catch (IOException e) {
      Notify.error(project, EiDbBundle.message("common.write.failed", e.getMessage()));
      return;
    }
    super.doOKAction();
    Targets.open(project, path(FeatureGen.Out.CONTROLLER));
    Notify.info(project, String.join("\n", report));
  }

  /** 스크롤 안에서 폭은 창에 맞추고 높이만 넘치면 세로로 스크롤되는 판. */
  private static final class WidthTracking extends JPanel implements Scrollable {
    WidthTracking() { super(new BorderLayout()); }

    @Override
    public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return JBUI.scale(SCROLL_UNIT); }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
      return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() { return true; }

    @Override
    public boolean getScrollableTracksViewportHeight() { return getParent() != null && getParent().getHeight() > getPreferredSize().height; }
  }
}
