package ei.db.gen;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareToggleAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.console.Badge;
import ei.db.BarAction;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.SqlRunner;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
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
  private static final String TOOLBAR_PLACE = "EiDbErd";
  private static final int MAX_TABLES = 60;
  private static final String[] TABLE_TYPES = {"TABLE"};
  /** 배율을 백분율 글로 바꿀 때 곱하는 수 · 실제 크기 배율. */
  private static final int PERCENT = 100;
  private static final double ACTUAL_ZOOM = 1.0;
  /** 범례 꼬리표 · 안내 사이 간격(px, 배율 전). */
  private static final int LEGEND_GAP = 8;

  /** tables 가 null 이면 스키마의 모든 표. */
  public static void open(Project project, DbConfig c, String schema, List<String> tables) {
    new Task.Backgroundable(project, EiDbBundle.message("erd.progress", schema), true) {
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
            Notify.error(project, EiDbBundle.message("erd.no.tables", schema));
            return;
          }
          int shown = names.size();
          ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            show(project, c, schema, cards, fks);
            if (total > shown) Notify.info(project, EiDbBundle.message("erd.truncated", total, shown));
          });
        } catch (ProcessCanceledException e) {
          throw e;
        } catch (Exception e) {
          Notify.error(project, EiDbBundle.message("erd.read.failed", e.getMessage()));
        }
      }
    }.queue();
  }

  private static void show(Project project, DbConfig c, String schema, List<ErdCanvas.Card> cards, List<Meta.ForeignKey> fks) {
    ErdCanvas canvas = new ErdCanvas(cards, fks, table -> SqlRunner.run(project, c, "SELECT * FROM " + Names.sql(c.kind, schema, table), List.of()));

    SimpleToolWindowPanel panel = new SimpleToolWindowPanel(true, true);
    ActionToolbar toolbar = BarAction.toolbar(TOOLBAR_PLACE, panel,
        new BarAction(EiDbBundle.message("erd.relayout"), null, AllIcons.Graph.Layout, canvas::autoLayout),
        new KeysOnly(canvas),
        Separator.getInstance(),
        new BarAction(EiDbBundle.message("erd.zoom.in"), null, AllIcons.Graph.ZoomIn, () -> canvas.zoomBy(ErdCanvas.ZOOM_STEP)),
        new BarAction(EiDbBundle.message("erd.zoom.out"), null, AllIcons.Graph.ZoomOut, () -> canvas.zoomBy(1 / ErdCanvas.ZOOM_STEP)),
        new ZoomReset(canvas).withText(),
        Separator.getInstance(),
        new BarAction(EiDbBundle.message("erd.save.png"), EiDbBundle.message("erd.save.png.description"), AllIcons.ToolbarDecorator.Export,
            () -> savePng(project, schema, canvas)));
    JBLabel summary = new JBLabel(EiDbBundle.message("erd.summary", canvas.tableCount(), canvas.relationCount()),
                                  UIUtil.ComponentStyle.REGULAR, UIUtil.FontColor.BRIGHTER);
    summary.setBorder(JBUI.Borders.emptyRight(LEGEND_GAP));
    JPanel top = new JPanel(new BorderLayout());
    top.add(toolbar.getComponent(), BorderLayout.CENTER);
    top.add(summary, BorderLayout.EAST);

    // 범례: PK · FK 꼬리표 + 짧은 조작 안내. 창이 좁아 잘리면 툴팁으로 전체를 본다.
    JPanel legend = new JPanel(new HorizontalLayout(LEGEND_GAP, SwingConstants.CENTER));
    legend.add(new JBLabel(EiDbBundle.message("erd.legend.pk"), Badge.pk(0), SwingConstants.LEFT));
    legend.add(new JBLabel(EiDbBundle.message("erd.legend.fk"), Badge.fk(0), SwingConstants.LEFT));
    JBLabel hint = new JBLabel(EiDbBundle.message("erd.legend"));
    hint.setForeground(Ui.muted());
    hint.setToolTipText(EiDbBundle.message("erd.legend"));
    legend.add(hint);
    legend.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor()), JBUI.Borders.empty(4, 8)));

    // 도구줄 아래 선은 SimpleToolWindowPanel 이 그리고, 스크롤 칸은 테두리 없이 둔다(선이 겹치지 않게).
    JPanel body = new JPanel(new BorderLayout());
    body.add(ScrollPaneFactory.createScrollPane(canvas, true), BorderLayout.CENTER);
    body.add(legend, BorderLayout.SOUTH);
    panel.setToolbar(top);
    panel.setContent(body);

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

  /** 실제 크기(100%)로 돌리기. 단추 글은 지금 배율(백분율)이다. */
  private static final class ZoomReset extends BarAction {
    private final ErdCanvas canvas;

    ZoomReset(ErdCanvas canvas) {
      super(EiDbBundle.message("erd.zoom.reset"), EiDbBundle.message("erd.zoom.reset"), AllIcons.Graph.ActualZoom, () -> canvas.setZoom(ACTUAL_ZOOM));
      this.canvas = canvas;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
      super.update(e);
      e.getPresentation().setText(Math.round(canvas.zoom() * PERCENT) + "%");
    }
  }

  /** 「키 칼럼만」 켜고 끄기. 끄면 모든 칼럼을 보인다. */
  private static final class KeysOnly extends DumbAwareToggleAction {
    private final ErdCanvas canvas;
    private boolean on;

    KeysOnly(ErdCanvas canvas) {
      super(EiDbBundle.message("erd.key.columns.only"), null, AllIcons.Nodes.DataColumn);
      this.canvas = canvas;
    }

    @Override public boolean isSelected(@NotNull AnActionEvent e) { return on; }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
      on = state;
      canvas.setKeysOnly(state);
    }

    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
  }

  private static void savePng(Project project, String schema, ErdCanvas canvas) {
    VirtualFileWrapper target = FileChooserFactory.getInstance()
        .createSaveFileDialog(new FileSaverDescriptor(EiDbBundle.message("erd.save.png"), EiDbBundle.message("erd.save.png.description"), "png"), project)
        .save("erd-" + schema.toLowerCase(java.util.Locale.ROOT) + ".png");
    if (target == null) return;
    try {
      if (!ImageIO.write(canvas.snapshot(), "png", target.getFile())) throw new IOException(EiDbBundle.message("erd.no.png.writer"));
      Notify.info(project, EiDbBundle.message("common.saved.to", target.getFile().getPath()));
    } catch (IOException e) {
      Notify.error(project, EiDbBundle.message("erd.save.failed", e.getMessage()));
    }
  }
}
