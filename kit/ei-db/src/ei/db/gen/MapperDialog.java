package ei.db.gen;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
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
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 표 하나로 매퍼 XML · 인터페이스 · DTO 뼈대를 만든다. 이미 있는 파일은 덮어쓰지 않고 없는 문장 · 메서드만 덧붙인다. */
public final class MapperDialog extends DialogWrapper {

  private static final Pattern FQN = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");
  private static final String DTO_PACKAGE = "dto";
  private static final int LEFT_WIDTH = 440;
  private static final int PREVIEW_WIDTH = 640;
  private static final int HEIGHT = 640;
  /** 입력이 멈춘 뒤 이만큼 지나서 미리보기를 다시 만든다(글자마다 디스크를 읽지 않게). */
  private static final int REFRESH_DELAY_MS = 300;
  private static final String BAD_PATH = "경로가 올바르지 않습니다";

  private final Project project;
  private final DbConfig config;
  private final String table;
  private final List<Meta.Column> cols;
  private final Targets.Roots roots;

  private final JBTextField mapperField = new JBTextField();
  private final JBCheckBox makeInterface = new JBCheckBox("자바 인터페이스도 만들기", true);
  private final JBTextField xmlField = new JBTextField();
  private final JBTextField dtoField = new JBTextField();
  private final JBCheckBox makeDto = new JBCheckBox("없으면 만들기", true);
  private final Map<MapperGen.Kind, JBCheckBox> kindBoxes = new EnumMap<>(MapperGen.Kind.class);
  private final JBCheckBox oldJoin = new JBCheckBox("구식 조인 (콤마 + WHERE)", true);
  private final JBCheckBox cdata = new JBCheckBox("부등호는 CDATA", true);
  private final JBCheckBox where11 = new JBCheckBox("WHERE 1 = 1 로 시작", true);
  private final JBCheckBox sqlComments = new JBCheckBox("칼럼 주석을 SQL 주석으로", true);

  private final JBTabbedPane tabs = new JBTabbedPane();
  private final JBLabel fileLabel = new JBLabel();
  private final JBLabel badge = new JBLabel();
  private Preview xmlPreview;
  private Preview interfacePreview;
  private Preview dtoPreview;
  private final Timer refreshTimer = new Timer(REFRESH_DELAY_MS, e -> startRefresh());
  /** 늦게 끝난 이전 계산이 새 결과를 덮지 않게 매번 올린다. 화면 스레드에서만 바꾼다. */
  private int refreshSeq;

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
            new MapperDialog(project, c, schema, table, cols, roots).show();
          });
        } catch (ProcessCanceledException e) {
          throw e;
        } catch (Exception e) {
          Notify.error(project, table + " 칼럼을 읽지 못했습니다: " + e.getMessage());
        }
      }
    }.queue();
  }

  private MapperDialog(Project project, DbConfig c, String schema, String table, List<Meta.Column> cols, Targets.Roots roots) {
    super(project, true);
    this.project = project;
    this.config = c;
    this.table = table;
    this.cols = cols;
    this.roots = roots;
    String entity = Names.pascal(table);
    mapperField.setText(roots.basePackage() + ".mapper." + entity + "Mapper");
    xmlField.setText(roots.rel(roots.resources() + "/mapper/" + table.toLowerCase(Locale.ROOT) + "/" + entity + "Mapper.xml"));
    dtoField.setText(entity + "Dto");
    setTitle("매퍼 뼈대 만들기 — " + table + "  ·  " + c.name + " · " + schema);
    setOKButtonText("만들기");
    setCancelButtonText("취소");
    refreshTimer.setRepeats(false);
    init();
    startRefresh();
  }

  @Override
  protected JComponent createCenterPanel() {
    xmlPreview = new Preview(project, "xml", getDisposable());
    interfacePreview = new Preview(project, "java", getDisposable());
    dtoPreview = new Preview(project, "java", getDisposable());
    tabs.addTab("XML", xmlPreview.component());
    tabs.addTab("인터페이스", interfacePreview.component());
    tabs.addTab("DTO", dtoPreview.component());
    tabs.addChangeListener(e -> refresh());

    for (JBTextField f : List.of(mapperField, xmlField, dtoField)) {
      f.setFont(Ui.mono());
      Ui.onChange(f, this::refresh);
    }
    for (JBCheckBox b : List.of(makeInterface, makeDto, cdata, where11, sqlComments)) b.addActionListener(e -> refresh());
    oldJoin.setEnabled(false);
    oldJoin.setToolTipText("표 하나의 문장이라 조인이 없습니다");

    JPanel dtoRow = new JPanel(new BorderLayout(JBUI.scale(12), 0));
    dtoRow.add(dtoField, BorderLayout.CENTER);
    dtoRow.add(makeDto, BorderLayout.EAST);

    JPanel left = FormBuilder.createFormBuilder()
        .addLabeledComponent("매퍼 인터페이스", mapperField, true)
        .addComponent(makeInterface)
        .addLabeledComponent("XML 위치", xmlField, true)
        .addLabeledComponent("DTO", dtoRow, true)
        .addLabeledComponent("만들 문장", statements(), true)
        .addLabeledComponent("스타일", stack(oldJoin, cdata, where11, sqlComments), true)
        .addLabeledComponentFillVertically(table + " 칼럼  " + cols.size() + "개", columnTable())
        .getPanel();
    left.setBorder(JBUI.Borders.emptyRight(12));
    left.setPreferredSize(new Dimension(JBUI.scale(LEFT_WIDTH), JBUI.scale(HEIGHT)));

    JPanel head = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0));
    fileLabel.setFont(Ui.mono());
    fileLabel.setForeground(Ui.muted());
    head.add(fileLabel);
    head.add(badge);
    JPanel right = new JPanel(new BorderLayout(0, JBUI.scale(6)));
    right.add(head, BorderLayout.NORTH);
    right.add(tabs, BorderLayout.CENTER);
    right.setPreferredSize(new Dimension(JBUI.scale(PREVIEW_WIDTH), JBUI.scale(HEIGHT)));
    right.setBorder(JBUI.Borders.emptyLeft(12));

    JPanel root = new JPanel(new BorderLayout());
    root.add(left, BorderLayout.WEST);
    root.add(right, BorderLayout.CENTER);
    return root;
  }

  @Override
  protected JPanel createSouthAdditionalPanel() {
    JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    JBLabel note = new JBLabel("이미 있는 파일은 덮어쓰지 않고 끝에 덧붙입니다", AllIcons.General.Information, JBLabel.LEFT);
    note.setForeground(Ui.muted());
    p.add(note);
    return p;
  }

  private JComponent statements() {
    MapperGen probe = gen();
    JPanel p = new JPanel();
    p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
    for (MapperGen.Kind k : MapperGen.Kind.values()) {
      JBCheckBox box = new JBCheckBox(k == MapperGen.Kind.RESULT_MAP ? "resultMap " + probe.id(k) : probe.id(k), k != MapperGen.Kind.DELETE);
      box.setFont(Ui.mono());
      String hint = switch (k) {
        case LIST -> "조건 검색";
        case ONE -> "PK";
        case INSERT -> "등록";
        case UPDATE -> "수정";
        case DELETE -> "삭제";
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
    DefaultTableModel model = new DefaultTableModel(new Object[] {"", "칼럼", "형식", "주석"}, 0) {
      @Override
      public boolean isCellEditable(int row, int column) { return false; }
    };
    for (Meta.Column c : cols) model.addRow(new Object[] {c.pk() ? "PK" : c.fk() ? "FK" : "", c.name(), c.typeLabel(), c.comment()});
    JBTable t = new JBTable(model);
    t.setShowGrid(false);
    t.getTableHeader().setVisible(false);
    t.setTableHeader(null);
    t.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(28));
    t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
      @Override
      public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
        super.getTableCellRendererComponent(table, value, selected, false, row, column);
        setFont(column == 1 || column == 2 ? Ui.mono() : table.getFont());
        if (!selected) {
          setForeground(column == 0 ? ("PK".equals(value) ? Ui.PK : Ui.FK) : column == 1 ? table.getForeground() : Ui.muted());
        }
        return this;
      }
    });
    JBScrollPane sp = new JBScrollPane(t);
    sp.setBorder(BorderFactory.createEmptyBorder());
    return sp;
  }

  // ---- 생성 ----

  private Set<MapperGen.Kind> kinds() {
    Set<MapperGen.Kind> out = EnumSet.noneOf(MapperGen.Kind.class);
    for (Map.Entry<MapperGen.Kind, JBCheckBox> e : kindBoxes.entrySet()) if (e.getValue().isSelected()) out.add(e.getKey());
    return out;
  }

  /** DTO 칸에 점이 있으면 FQN 그대로, 아니면 매퍼 패키지의 층 마디(mapper 등)를 dto 로 바꾼 패키지. */
  private String dtoType() {
    String dto = dtoField.getText().strip();
    if (dto.contains(".")) return dto;
    String pkg = Names.packageOf(mapperField.getText().strip());
    String layer = Names.simple(pkg);
    String base = Targets.LAYER_SEGMENTS.contains(layer) ? Names.packageOf(pkg) : pkg;
    return base.isEmpty() ? DTO_PACKAGE + "." + dto : base + "." + DTO_PACKAGE + "." + dto;
  }

  private MapperGen gen() {
    MapperGen.Options o = new MapperGen.Options(oldJoin.isSelected(), cdata.isSelected(), where11.isSelected(),
                                                sqlComments.isSelected(), config.kind);
    return new MapperGen(table, cols, mapperField.getText().strip(), dtoType(), o);
  }

  private void refresh() {
    refreshTimer.restart();
  }

  private record Inputs(MapperGen gen, Set<MapperGen.Kind> kinds, String xml, String mapper, String dtoType,
                        boolean makeInterface, boolean makeDto, int tab) {}

  /** error 가 있으면 경로가 잘못돼 미리보기를 바꾸지 않는다. */
  private record Shown(String xml, String itf, String dto, String path, boolean exists, String error) {}

  /** 입력은 화면 스레드에서 모으고, 파일 읽기 · 합치기는 백그라운드에서 한 뒤 결과를 화면에 넣는다. */
  private void startRefresh() {
    if (xmlPreview == null || kindBoxes.isEmpty() || isDisposed()) return;
    Inputs in = new Inputs(gen(), kinds(), xmlField.getText(), mapperField.getText().strip(), dtoType(),
                           makeInterface.isSelected(), makeDto.isSelected(), tabs.getSelectedIndex());
    int seq = ++refreshSeq;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      Shown shown = ReadAction.compute(() -> compute(in));
      ApplicationManager.getApplication().invokeLater(() -> {
        if (seq == refreshSeq && !isDisposed()) apply(shown);
      }, ModalityState.any());
    });
  }

  private Shown compute(Inputs in) {
    try {
      MapperGen g = in.gen();
      Set<MapperGen.Kind> kinds = in.kinds();
      String xmlPath = roots.abs(in.xml());
      String xmlExisting = readQuietly(xmlPath);
      String xml = xmlExisting == null ? g.xmlFile(kinds) : addedOnly(g.xmlBlocks(kinds), Merge.xml(xmlExisting, g.xmlBlocks(kinds)));
      String itfPath = roots.javaFile(in.mapper());
      String itfExisting = readQuietly(itfPath);
      String itf;
      if (!in.makeInterface()) {
        itf = "// 자바 인터페이스는 만들지 않습니다.";
      } else if (itfExisting == null) {
        itf = g.interfaceFile(kinds);
      } else {
        itf = addedOnly(g.methods(kinds), Merge.java(itfExisting, g.methods(kinds), g.interfaceImports(kinds)));
      }
      String dtoPath = roots.javaFile(in.dtoType());
      boolean dtoExists = Targets.exists(dtoPath);
      String dto;
      if (!in.makeDto()) {
        dto = "// DTO 는 만들지 않습니다.";
      } else if (dtoExists) {
        dto = "// " + roots.rel(dtoPath) + " 이 이미 있어 만들지 않습니다.";
      } else {
        dto = g.dtoFile();
      }
      int tab = in.tab();
      String path = tab == 0 ? xmlPath : tab == 1 ? itfPath : dtoPath;
      boolean exists = tab == 0 ? xmlExisting != null : tab == 1 ? itfExisting != null : dtoExists;
      return new Shown(xml, itf, dto, path, exists, null);
    } catch (InvalidPathException e) {
      return new Shown(null, null, null, null, false, BAD_PATH);
    }
  }

  private void apply(Shown s) {
    if (s.error() != null) {
      fileLabel.setText(s.error());
      badge.setText("");
      return;
    }
    xmlPreview.setText(s.xml());
    interfacePreview.setText(s.itf());
    dtoPreview.setText(s.dto());
    fileLabel.setText(s.path().substring(s.path().lastIndexOf('/') + 1));
    badge.setText(s.exists() ? "있는 파일" : "새 파일");
    badge.setForeground(s.exists() ? Ui.muted() : Ui.SUCCESS);
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

  /** 있는 파일에는 덧붙을 블록만 보여 준다. */
  private static String addedOnly(List<MapperGen.Block> blocks, Merge.Result r) {
    if (r.text() == null) return "// 끼울 자리(닫는 태그 · 괄호)를 찾지 못했습니다.";
    StringBuilder b = new StringBuilder();
    if (!r.skipped().isEmpty()) b.append("<!-- 이미 있어 건너뜀: ").append(String.join(", ", r.skipped())).append(" -->\n");
    for (MapperGen.Block bl : blocks) if (r.added().contains(bl.id())) b.append(bl.text()).append('\n');
    return r.added().isEmpty() ? b.append("<!-- 덧붙일 것이 없습니다 -->\n").toString() : b.toString();
  }

  /** 미리보기용. 못 읽으면 새 파일로 보이게 null(실제 쓰기 때 다시 읽어 실패를 알린다). */
  private static String readQuietly(String path) {
    try {
      return Targets.read(path);
    } catch (IOException e) {
      return null;
    }
  }

  @Override
  protected ValidationInfo doValidate() {
    if (!FQN.matcher(mapperField.getText().strip()).matches()) return new ValidationInfo("매퍼 인터페이스 이름이 자바 이름 꼴이 아닙니다.", mapperField);
    if (!xmlField.getText().strip().endsWith(".xml")) return new ValidationInfo("XML 위치는 .xml 파일이어야 합니다.", xmlField);
    if (!validPath(xmlField.getText())) return new ValidationInfo(BAD_PATH + ".", xmlField);
    if (!FQN.matcher(dtoField.getText().strip()).matches()) return new ValidationInfo("DTO 이름이 자바 이름 꼴이 아닙니다.", dtoField);
    if (kinds().isEmpty()) return new ValidationInfo("만들 문장을 하나 이상 고르세요.");
    return null;
  }

  @Override
  protected void dispose() {
    refreshTimer.stop();
    super.dispose();
  }

  @Override
  protected void doOKAction() {
    MapperGen g = gen();
    Set<MapperGen.Kind> kinds = kinds();
    List<Targets.Job> jobs = new ArrayList<>();
    List<String> report = new ArrayList<>();
    String xmlPath = roots.abs(xmlField.getText());
    try {
      String xml = Targets.read(xmlPath);
      if (xml == null) {
        jobs.add(new Targets.Job(xmlPath, g.xmlFile(kinds), false));
        report.add(roots.rel(xmlPath) + " 새로 만듦");
      } else {
        Merge.Result r = Merge.xml(xml, g.xmlBlocks(kinds));
        if (r.text() == null) {
          setErrorText(roots.rel(xmlPath) + " 에서 </mapper> 를 찾지 못했습니다.", xmlField);
          return;
        }
        jobs.add(new Targets.Job(xmlPath, r.text(), !r.added().isEmpty()));
        report.add(summary(roots.rel(xmlPath), r));
      }
      if (makeInterface.isSelected()) {
        String itfPath = roots.javaFile(mapperField.getText().strip());
        String itf = Targets.read(itfPath);
        if (itf == null) {
          jobs.add(new Targets.Job(itfPath, g.interfaceFile(kinds), false));
          report.add(roots.rel(itfPath) + " 새로 만듦");
        } else {
          Merge.Result r = Merge.java(itf, g.methods(kinds), g.interfaceImports(kinds));
          if (r.text() == null) {
            setErrorText(roots.rel(itfPath) + " 에서 닫는 괄호를 찾지 못했습니다.", mapperField);
            return;
          }
          jobs.add(new Targets.Job(itfPath, r.text(), !r.added().isEmpty()));
          report.add(summary(roots.rel(itfPath), r));
        }
      }
      if (makeDto.isSelected()) {
        String dtoPath = roots.javaFile(dtoType());
        if (!Targets.exists(dtoPath)) report.add(roots.rel(dtoPath) + " 새로 만듦");
        jobs.add(new Targets.Job(dtoPath, g.dtoFile(), false));
      }
      Targets.write(project, "매퍼 뼈대 만들기", jobs);
      super.doOKAction();
      Targets.open(project, xmlPath);
      Notify.info(project, String.join("\n", report));
    } catch (IOException e) {
      setErrorText("파일을 쓰지 못했습니다: " + e.getMessage());
    }
  }

  private static String summary(String file, Merge.Result r) {
    String s = file + " 에 " + r.added().size() + "개 덧붙임";
    return r.skipped().isEmpty() ? s : s + " (이미 있어 건너뜀: " + String.join(", ", r.skipped()) + ")";
  }
}
