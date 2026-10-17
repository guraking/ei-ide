package ei.db.gen;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.SqlRunner;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 스키마의 표들을 ERD 로 그린다. 편집기 탭용 FileEditorProvider 가 빌드 클래스패스에 없어
 * 「ERD」 도구 창(아래쪽)에 「ERD · 스키마」 탭으로 연다. 같은 도구 창에 여러 ERD 탭이 쌓인다.
 */
public final class ErdView {
  private ErdView() {}

  /** 도구 창 id(설정 저장용 열쇠라 바꾸지 않는다). 화면에는 STRIPE_TITLE 로 보인다. */
  static final String TOOL_WINDOW_ID = "EI ERD";
  private static final String STRIPE_TITLE = "ERD";
  private static final int MAX_TABLES = 60;
  private static final String[] TABLE_TYPES = {"TABLE"};
  private static final String LEGEND = "PK 주황 · FK 파랑 · 표 더블클릭 → 데이터 보기 · 칼럼에 마우스 → 형식 · 주석 · 휠 → 확대 · 빈 곳 끌기 → 이동";

  /** tables 가 null 이면 스키마의 모든 표. */
  public static void open(Project project, DbConfig c, String schema, List<String> tables) {
    new Task.Backgroundable(project, "ERD · " + schema + " 읽는 중", true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          Connection conn = DbSessions.get(project).connection(c);
          Map<String, Meta.Table> all = new HashMap<>();
          List<String> names = new ArrayList<>();
          for (Meta.Table t : Meta.tables(conn, c, schema, TABLE_TYPES)) {
            all.put(t.name(), t);
            if (tables == null) names.add(t.name());
          }
          if (tables != null) names.addAll(tables);
          int total = names.size();
          if (total > MAX_TABLES) names = new ArrayList<>(names.subList(0, MAX_TABLES));
          List<ErdCanvas.Card> cards = new ArrayList<>();
          List<Meta.ForeignKey> fks = new ArrayList<>();
          indicator.setIndeterminate(false);
          for (int i = 0; i < names.size(); i++) {
            indicator.checkCanceled();
            String n = names.get(i);
            indicator.setText2(n);
            indicator.setFraction((double) i / names.size());
            Meta.Table t = all.getOrDefault(n, new Meta.Table(n, "TABLE", ""));
            cards.add(new ErdCanvas.Card(t, Meta.columns(conn, c, schema, n)));
            fks.addAll(Meta.importedKeys(conn, c, schema, n));
          }
          if (cards.isEmpty()) {
            Notify.error(project, schema + " 에 그릴 표가 없습니다.");
            return;
          }
          int shown = names.size();
          ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            show(project, c, schema, cards, fks);
            if (total > shown) Notify.info(project, "표가 " + total + "개라 처음 " + shown + "개만 그렸습니다.");
          });
        } catch (ProcessCanceledException e) {
          throw e;
        } catch (Exception e) {
          Notify.error(project, "ERD 를 읽지 못했습니다: " + e.getMessage());
        }
      }
    }.queue();
  }

  private static void show(Project project, DbConfig c, String schema, List<ErdCanvas.Card> cards, List<Meta.ForeignKey> fks) {
    ErdCanvas canvas = new ErdCanvas(cards, fks, table -> SqlRunner.run(project, c, "SELECT * FROM " + Names.sql(c.kind, schema, table), List.of()));

    JButton relayout = new JButton("자동 배치");
    relayout.addActionListener(e -> canvas.autoLayout());
    JToggleButton allCols = new JToggleButton("전체", true);
    JToggleButton keyCols = new JToggleButton("키만");
    ButtonGroup group = new ButtonGroup();
    group.add(allCols);
    group.add(keyCols);
    allCols.addActionListener(e -> canvas.setKeysOnly(false));
    keyCols.addActionListener(e -> canvas.setKeysOnly(true));
    JButton png = new JButton("PNG 저장");
    png.addActionListener(e -> savePng(project, schema, canvas));

    JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)));
    toolbar.add(relayout);
    toolbar.add(new JBLabel("칼럼:"));
    toolbar.add(allCols);
    toolbar.add(keyCols);
    toolbar.add(png);

    JBLabel legend = new JBLabel(LEGEND);
    legend.setForeground(Ui.muted());
    legend.setBorder(JBUI.Borders.empty(4, 8));

    JPanel panel = new JPanel(new BorderLayout());
    panel.add(toolbar, BorderLayout.NORTH);
    panel.add(new JBScrollPane(canvas), BorderLayout.CENTER);
    panel.add(legend, BorderLayout.SOUTH);

    ToolWindowManager twm = ToolWindowManager.getInstance(project);
    ToolWindow tw = twm.getToolWindow(TOOL_WINDOW_ID);
    if (tw == null) {
      tw = twm.registerToolWindow(TOOL_WINDOW_ID, true, ToolWindowAnchor.BOTTOM);
      tw.setStripeTitle(STRIPE_TITLE);
    }
    Content content = ContentFactory.getInstance().createContent(panel, "ERD · " + schema, false);
    tw.getContentManager().addContent(content);
    tw.getContentManager().setSelectedContent(content);
    tw.activate(null);
  }

  private static void savePng(Project project, String schema, ErdCanvas canvas) {
    VirtualFileWrapper target = FileChooserFactory.getInstance()
        .createSaveFileDialog(new FileSaverDescriptor("PNG 저장", "ERD 를 그림 파일로 저장합니다", "png"), project)
        .save("erd-" + schema.toLowerCase(java.util.Locale.ROOT) + ".png");
    if (target == null) return;
    try {
      if (!ImageIO.write(canvas.snapshot(), "png", target.getFile())) throw new IOException("PNG 쓰기 도구가 없습니다");
      Notify.info(project, target.getFile().getPath() + " 에 저장했습니다.");
    } catch (IOException e) {
      Notify.error(project, "PNG 를 저장하지 못했습니다: " + e.getMessage());
    }
  }
}
