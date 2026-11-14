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
import ei.db.DbKind;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
  /** DB 에서 찾지 못한 표 카드의 종류. */
  private static final String DEFAULT_TABLE_TYPE = "TABLE";
  /** 배율을 백분율 글로 바꿀 때 곱하는 수 · 실제 크기 배율. */
  private static final int PERCENT = 100;
  private static final double ACTUAL_ZOOM = 1.0;
  /** 범례 꼬리표 · 안내 사이 간격(px, 배율 전). */
  private static final int LEGEND_GAP = 8;

  /** tables 가 null 이면 스키마의 모든 표. */
  public static void open(Project project, DbConfig c, String schema, List<String> tables) {
    open(project, c, schema, tables, null, Set.of(), "ERD · " + schema);
  }

  /**
   * 쿼리의 조인 그림: tables 만 그리고, 선은 DB 외래 키 대신 쿼리의 연결 조건(links)으로 긋는다.
   * unlinked(대문자 표 이름)는 다른 표와 연결 조건이 없는 표라 테두리를 빨갛게 그린다.
   */
  public static void openQuery(Project project, DbConfig c, String schema, List<String> tables, List<Meta.ForeignKey> links, Set<String> unlinked) {
    open(project, c, schema, tables, links, unlinked, "조인 그림");
  }

  /** 표 하나의 종류 · 주석(이름이 정확히 같은 것). 없으면 종류 TABLE · 빈 주석. MySQL 은 데이터베이스가 catalog 자리다(Meta 와 같다). */
  private static Meta.Table table(Connection conn, DbConfig c, String schema, String name) throws SQLException {
    boolean mysql = c.kind == DbKind.MYSQL;
    try (ResultSet rs = conn.getMetaData().getTables(mysql ? schema : null, mysql ? null : schema, name, Meta.tableTypes(c))) {
      while (rs.next()) {
        if (!name.equals(rs.getString("TABLE_NAME"))) continue;
        String remarks = rs.getString("REMARKS");
        return new Meta.Table(name, rs.getString("TABLE_TYPE"), remarks == null ? "" : remarks);
      }
    }
    return new Meta.Table(name, DEFAULT_TABLE_TYPE, "");
  }

  /** links 가 null 이면 DB 외래 키를 읽어 선을 긋는다. */
  private static void open(Project project, DbConfig c, String schema, List<String> tables, List<Meta.ForeignKey> links, Set<String> unlinked, String title) {
    new Task.Backgroundable(project, EiDbBundle.message("erd.progress", schema), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          Connection conn = DbSessions.get(project).connection(c);
          List<ErdCanvas.Card> cards = new ArrayList<>();
          List<Meta.ForeignKey> fks = new ArrayList<>();
          if (tables == null) {
            readSchema(conn, c, schema, indicator, cards, fks);
          } else {
            indicator.setIndeterminate(false);
            for (int i = 0; i < tables.size(); i++) {
              indicator.checkCanceled();
              String n = tables.get(i);
              indicator.setText2(n);
              indicator.setFraction((double) i / tables.size());
              ErdCanvas.Card card = new ErdCanvas.Card(table(conn, c, schema, n), Meta.columns(conn, c, schema, n));
              card.unlinked = unlinked.contains(n.toUpperCase(java.util.Locale.ROOT));
              cards.add(card);
              if (links == null) fks.addAll(Meta.importedKeys(conn, c, schema, n));
            }
          }
          if (links != null) fks.addAll(links);
          if (cards.isEmpty()) {
            Notify.error(project, EiDbBundle.message("erd.no.tables", schema));
            return;
          }
          ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            show(project, c, schema, cards, fks, title);
          });
        } catch (ProcessCanceledException e) {
          throw e;
        } catch (Exception e) {
          Notify.error(project, EiDbBundle.message("erd.read.failed", e.getMessage()));
        }
      }
    }.queue();
  }

  /**
   * 스키마의 모든 표를 cards · fks 에 담는다. 칼럼과 PK 는 스키마 전체를 한 번씩 읽고(표가 많아도 DB 왕복이 늘지 않게),
   * FK 만 표마다 한 번 읽어 선과 칼럼의 FK 꼬리표에 함께 쓴다. 칼럼이 하나도 안 읽힌 표는 빈 카드로 그린다.
   */
  private static void readSchema(Connection conn, DbConfig c, String schema, ProgressIndicator indicator,
                                 List<ErdCanvas.Card> cards, List<Meta.ForeignKey> fks) throws SQLException {
    List<Meta.Table> list = Meta.tables(conn, c, schema, Meta.tableTypes(c));
    indicator.checkCanceled();
    Map<String, List<Meta.ColumnRow>> rows = new HashMap<>();
    for (Meta.ColumnRow r : Meta.columnRows(conn, c, schema, null, false)) rows.computeIfAbsent(r.table(), k -> new ArrayList<>()).add(r);
    indicator.checkCanceled();
    // 스키마 이름을 모르면(드라이버가 스키마 없이 쓰는 경우) 데이터 사전을 물을 수 없어 표마다 getPrimaryKeys 로 읽는다.
    boolean bulkPk = schema != null && !schema.isEmpty();
    Map<String, List<String>> pks = bulkPk ? Meta.schemaPrimaryKeys(conn, c, schema) : Map.of();
    indicator.setIndeterminate(false);
    for (int i = 0; i < list.size(); i++) {
      indicator.checkCanceled();
      Meta.Table t = list.get(i);
      indicator.setText2(t.name());
      indicator.setFraction((double) i / list.size());
      List<Meta.ForeignKey> imported = Meta.importedKeys(conn, c, schema, t.name());
      fks.addAll(imported);
      Set<String> fkCols = new HashSet<>();
      for (Meta.ForeignKey fk : imported) fkCols.add(fk.fkColumn());
      java.util.Collection<String> pk = bulkPk ? pks.getOrDefault(t.name(), List.of()) : Meta.primaryKeys(conn, c, schema, t.name());
      List<Meta.Column> cols = new ArrayList<>();
      for (Meta.ColumnRow r : rows.getOrDefault(t.name(), List.of())) {
        Meta.Column col = r.column();
        cols.add(new Meta.Column(col.name(), col.type(), col.size(), col.scale(), pk.contains(col.name()), fkCols.contains(col.name()), col.nullable(), col.comment()));
      }
      cards.add(new ErdCanvas.Card(t, cols));
    }
  }

  private static void show(Project project, DbConfig c, String schema, List<ErdCanvas.Card> cards, List<Meta.ForeignKey> fks, String title) {
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
                                  UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
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
    Content content = ContentFactory.getInstance().createContent(panel, title, false);
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
