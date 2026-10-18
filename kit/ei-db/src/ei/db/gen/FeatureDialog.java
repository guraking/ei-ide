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
import ei.db.Meta;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
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
  private static final String BAD_PATH = "경로가 올바르지 않습니다";
  private static final String MERGE = "덧붙이기";
  private static final String SKIP = "건너뛰기";
  private static final String COMMAND = "기능 한 벌 만들기";
  /** 미리보기 탭 순서. */
  private static final FeatureGen.Out[] TAB_OUTS = {
      FeatureGen.Out.XML, FeatureGen.Out.MAPPER, FeatureGen.Out.DTO,
      FeatureGen.Out.SERVICE, FeatureGen.Out.SERVICE_IMPL, FeatureGen.Out.CONTROLLER};
  private static final String[] TAB_TITLES = {"XML", "인터페이스", "DTO", "서비스", "서비스 Impl", "컨트롤러"};
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
  private final JBCheckBox makeDto = new JBCheckBox("없으면 만들기", true);
  private final JBTextField serviceField = new JBTextField();
  private final JBCheckBox split = new JBCheckBox("인터페이스 + Impl 로 나누기", false);
  private final JBTextField implField = new JBTextField();
  private final JBTextField controllerField = new JBTextField();
  private final JBCheckBox mvc = new JBCheckBox("@Controller (화면용, 기본은 @RestController)", false);
  private final JBTextField urlField = new JBTextField();
  private final Map<MapperGen.Kind, JBCheckBox> kindBoxes = new EnumMap<>(MapperGen.Kind.class);
  private final JBCheckBox oldJoin = new JBCheckBox("구식 조인 (콤마 + WHERE)", true);
  private final JBCheckBox cdata = new JBCheckBox("부등호는 CDATA", true);
  private final JBCheckBox where11 = new JBCheckBox("WHERE 1 = 1 로 시작", true);
  private final JBCheckBox sqlComments = new JBCheckBox("칼럼 주석을 SQL 주석으로", true);
  private DefaultTableModel columnModel;

  private final JBTabbedPane tabs = new JBTabbedPane();
  private final JBLabel fileLabel = new JBLabel();
  private final JBLabel badge = new JBLabel();
  private final ComboBox<String> existingChoice = new ComboBox<>(new String[] {MERGE, SKIP});
  private final Map<FeatureGen.Out, Preview> previews = new EnumMap<>(FeatureGen.Out.class);
  /** 있는 파일을 건너뛰기로 고른 출력. */
  private final Set<FeatureGen.Out> skipped = EnumSet.noneOf(FeatureGen.Out.class);
  private final Map<FeatureGen.Out, Plan> shown = new EnumMap<>(FeatureGen.Out.class);
  private final Timer refreshTimer = new Timer(REFRESH_DELAY_MS, e -> doRefresh());
  /** 콤보를 코드로 맞출 때 선택 변경을 사용자 선택으로 받지 않는다. */
  private boolean syncingChoice;

  public static void show(Project project, DbConfig c, String schema, String table) {
    new Task.Backgroundable(project, table + " 칼럼 읽는 중", true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          Connection conn = DbSessions.get(project).connection(c);
          List<Meta.Column> cols = Meta.columns(conn, c, schema, table);
          Targets.Roots roots = ReadAction.compute(() -> Targets.find(project));
          ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            if (cols.isEmpty()) {
              Notify.error(project, schema + "." + table + " 의 칼럼을 찾지 못했습니다.");
              return;
            }
            new FeatureDialog(project, c, schema, table, cols, roots).show();
          });
        } catch (ProcessCanceledException e) {
          throw e;
        } catch (Exception e) {
          Notify.error(project, table + " 칼럼을 읽지 못했습니다: " + e.getMessage());
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
    setTitle("기능 한 벌 만들기 — " + table + "  ·  " + c.name + " · " + schema);
    setOKButtonText("만들기");
    setCancelButtonText("취소");
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
      if (SKIP.equals(existingChoice.getSelectedItem())) skipped.add(o); else skipped.remove(o);
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
    oldJoin.setToolTipText("표 하나의 문장이라 조인이 없습니다");

    JPanel dtoRow = new JPanel(new BorderLayout(JBUI.scale(12), 0));
    dtoRow.add(dtoField, BorderLayout.CENTER);
    dtoRow.add(makeDto, BorderLayout.EAST);

    FormBuilder form = FormBuilder.createFormBuilder()
        .addLabeledComponent("매퍼 인터페이스", mapperField, true)
        .addLabeledComponent("XML 위치", xmlField, true)
        .addLabeledComponent("DTO", dtoRow, true)
        .addLabeledComponent("서비스", serviceField, true)
        .addComponent(split)
        .addLabeledComponent("서비스 Impl", implField, true)
        .addLabeledComponent("컨트롤러", controllerField, true)
        .addComponent(mvc)
        .addLabeledComponent("기본 URL", urlField, true)
        .addLabeledComponent("만들 문장", statements(), true)
        .addLabeledComponent("스타일", stack(oldJoin, cdata, where11, sqlComments), true);
    String note = gen().missingNote();
    if (note != null) {
      JBLabel warn = new JBLabel(note, AllIcons.General.Warning, JBLabel.LEFT);
      warn.setForeground(Ui.muted());
      form.addComponent(warn);
    }
    JPanel left = form
        .addLabeledComponentFillVertically(table + " 칼럼  " + cols.size() + "개  ·  검색 칸 = 목록 조건", columnTable())
        .getPanel();
    left.setBorder(JBUI.Borders.emptyRight(12));
    WidthTracking leftBody = new WidthTracking();
    leftBody.add(left, BorderLayout.CENTER);
    JBScrollPane leftScroll = new JBScrollPane(leftBody, JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    leftScroll.setBorder(BorderFactory.createEmptyBorder());
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
    JBLabel note = new JBLabel("이미 있는 파일은 덮어쓰지 않고 끝에 덧붙이거나 건너뜁니다", AllIcons.General.Information, JBLabel.LEFT);
    note.setForeground(Ui.muted());
    p.add(note);
    return p;
  }

  private JComponent statements() {
    MapperGen probe = gen().mapper();
    JPanel p = new JPanel();
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    for (MapperGen.Kind k : MapperGen.Kind.values()) {
      JBCheckBox box = new JBCheckBox(k == MapperGen.Kind.RESULT_MAP ? "resultMap " + probe.id(k) : probe.id(k), true);
      box.setFont(Ui.mono());
      String hint = switch (k) {
        case LIST -> "GET 목록";
        case ONE -> "GET 상세";
        case INSERT -> "POST 등록";
        case UPDATE -> "PUT 수정";
        case DELETE -> "DELETE 삭제";
        case RESULT_MAP -> "칼럼 ↔ 필드";
      };
      if (!probe.possible(k)) {
        box.setSelected(false);
        box.setEnabled(false);
        hint = probe.hasPk() ? "바꿀 칼럼 없음" : "PK 없음";
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
    columnModel = new DefaultTableModel(new Object[] {"검색", "", "칼럼", "형식"}, 0) {
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
    sp.setBorder(BorderFactory.createEmptyBorder());
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
      String why = o == FeatureGen.Out.DTO ? "DTO 는 만들지 않습니다." : "서비스를 인터페이스 + Impl 로 나누지 않습니다.";
      return new Plan(null, false, false, note(o, why), null, null);
    }
    String rel = roots.rel(path);
    String existing = Targets.read(path);
    if (existing == null) {
      String text = g.file(o);
      return new Plan(path, false, false, text, new Targets.Job(path, text, false), rel + " 새로 만듦");
    }
    String cannot = g.cannotMerge(o, existing);
    if (cannot != null) {
      String msg = rel + " 이 이미 있어 건너뜀: " + cannot;
      return new Plan(path, true, false, note(o, msg), null, msg);
    }
    if (skipped.contains(o)) {
      String msg = rel + " 이 이미 있어 건너뜀";
      return new Plan(path, true, true, note(o, msg), null, msg);
    }
    Merge.Result r = g.merge(o, existing);
    if (r.text() == null) {
      String msg = rel + " 에서 끼울 자리(닫는 태그 · 괄호)를 찾지 못해 건너뜀";
      return new Plan(path, true, true, note(o, msg), null, msg);
    }
    List<MapperGen.Block> blocks = o == FeatureGen.Out.XML ? g.xmlBlocks() : g.blocks(o);
    String report = rel + " 에 " + r.added().size() + "개 덧붙임"
        + (r.skipped().isEmpty() ? "" : " (이미 있어 건너뜀: " + String.join(", ", r.skipped()) + ")");
    Targets.Job job = r.added().isEmpty() ? null : new Targets.Job(path, r.text(), true);
    return new Plan(path, true, true, addedOnly(o, blocks, r), job, report);
  }

  /** 있는 파일에는 덧붙을 블록만 보여 준다. */
  private static String addedOnly(FeatureGen.Out o, List<MapperGen.Block> blocks, Merge.Result r) {
    StringBuilder b = new StringBuilder();
    if (!r.skipped().isEmpty()) b.append(note(o, "이미 있어 건너뜀: " + String.join(", ", r.skipped()))).append('\n');
    for (MapperGen.Block bl : blocks) if (r.added().contains(bl.id())) b.append(bl.text()).append('\n');
    return r.added().isEmpty() ? b.append(note(o, "덧붙일 것이 없습니다")).append('\n').toString() : b.toString();
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
      fileLabel.setText(BAD_PATH);
      badge.setText("");
      existingChoice.setVisible(false);
      return;
    }
    for (FeatureGen.Out o : TAB_OUTS) {
      Plan p;
      try {
        p = plan(g, o);
      } catch (IOException e) {
        p = new Plan(path(o), true, false, note(o, "파일을 읽지 못했습니다: " + e.getMessage()), null, null);
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
      badge.setText("만들지 않음");
      badge.setForeground(Ui.muted());
      existingChoice.setVisible(false);
      return;
    }
    fileLabel.setText(p.path().substring(p.path().lastIndexOf('/') + 1));
    badge.setText(p.exists() ? (p.mergeable() ? "있는 파일" : "있는 파일 · 건너뜀") : "새 파일");
    badge.setForeground(p.exists() ? Ui.muted() : Ui.SUCCESS);
    syncingChoice = true;
    existingChoice.setSelectedItem(skipped.contains(TAB_OUTS[tabs.getSelectedIndex()]) ? SKIP : MERGE);
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
    if (!FQN.matcher(mapperField.getText().strip()).matches()) return new ValidationInfo("매퍼 인터페이스 이름이 자바 이름 꼴이 아닙니다.", mapperField);
    if (!xmlField.getText().strip().endsWith(".xml")) return new ValidationInfo("XML 위치는 .xml 파일이어야 합니다.", xmlField);
    if (!validPath(xmlField.getText())) return new ValidationInfo(BAD_PATH + ".", xmlField);
    if (!FQN.matcher(dtoField.getText().strip()).matches()) return new ValidationInfo("DTO 이름이 자바 이름 꼴이 아닙니다.", dtoField);
    if (!FQN.matcher(serviceField.getText().strip()).matches()) return new ValidationInfo("서비스 이름이 자바 이름 꼴이 아닙니다.", serviceField);
    if (split.isSelected() && !FQN.matcher(implField.getText().strip()).matches()) {
      return new ValidationInfo("서비스 Impl 이름이 자바 이름 꼴이 아닙니다.", implField);
    }
    if (!FQN.matcher(controllerField.getText().strip()).matches()) return new ValidationInfo("컨트롤러 이름이 자바 이름 꼴이 아닙니다.", controllerField);
    if (!URL.matcher(baseUrl()).matches()) return new ValidationInfo("기본 URL 은 /order-item 꼴이어야 합니다.", urlField);
    Set<String> types = new LinkedHashSet<>();
    List<String> all = new ArrayList<>(List.of(dtoType(), mapperField.getText().strip(), serviceField.getText().strip(),
                                               controllerField.getText().strip()));
    if (split.isSelected()) all.add(implField.getText().strip());
    for (String t : all) {
      if (!types.add(t)) return new ValidationInfo("같은 클래스 이름이 두 번 쓰였습니다: " + t);
    }
    boolean anyMethod = false;
    Set<MapperGen.Kind> kinds = kinds();
    for (MapperGen.Kind k : METHOD_KINDS) anyMethod |= kinds.contains(k);
    if (!anyMethod) return new ValidationInfo("만들 문장(목록 · 상세 · 등록 · 수정 · 삭제)을 하나 이상 고르세요.");
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
      Notify.error(project, "파일을 읽지 못해 만들지 않았습니다: " + e.getMessage());
      return;
    }
    try {
      Targets.write(project, COMMAND, jobs);
    } catch (IOException e) {
      Notify.error(project, "파일을 쓰지 못했습니다: " + e.getMessage());
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
