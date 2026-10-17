package ei.db.mybatis;

import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import ei.db.DbConfig;
import ei.db.DbSettings;
import ei.db.Notify;
import ei.db.SqlRunner;
import ei.db.console.ConsoleOpener;
import org.jetbrains.annotations.NotNull;

import javax.swing.event.HyperlinkEvent;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 실행 콘솔 오른쪽 클릭 메뉴의 MyBatis 로그 동작들. 선택 글이 있으면 그것을, 없으면 커서 줄과 그 위 줄들을 읽는다.
 * 로그(Preparing)를 찾을 때만 보인다.
 */
public final class LogActions {
  private LogActions() {}

  /** 커서 위로 Preparing 을 찾는 줄 수와, 그 아래로 Parameters 를 찾는 줄 수. */
  private static final int LOOK_BACK = 200;
  private static final int LOOK_AHEAD = 20;

  static Editor editor(AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    if (editor != null) return editor;
    ConsoleView console = e.getData(LangDataKeys.CONSOLE_VIEW);
    return console instanceof ConsoleViewImpl impl ? impl.getEditor() : null;
  }

  static MyBatisLog.Entry find(AnActionEvent e) {
    Editor editor = editor(e);
    if (editor == null) return null;
    String selected = editor.getSelectionModel().getSelectedText();
    if (selected != null && !selected.isBlank()) return MyBatisLog.parse(selected);
    Document doc = editor.getDocument();
    if (doc.getLineCount() == 0) return null;
    int caretLine = doc.getLineNumber(Math.min(editor.getCaretModel().getOffset(), doc.getTextLength()));
    int from = Math.max(0, caretLine - LOOK_BACK);
    int to = Math.min(doc.getLineCount() - 1, caretLine + LOOK_AHEAD);
    CharSequence text = doc.getImmutableCharSequence();
    List<String> lines = new ArrayList<>();
    for (int k = from; k <= to; k++) lines.add(text.subSequence(doc.getLineStartOffset(k), doc.getLineEndOffset(k)).toString());
    return MyBatisLog.parse(lines, caretLine - from);
  }

  /** 마지막에 쓴 접속을 앞에 둔 목록. */
  static List<DbConfig> connections(Project project) {
    List<DbConfig> all = new ArrayList<>(DbSettings.getInstance().connections());
    DbConfig last = DbSettings.getInstance().find(PropertiesComponent.getInstance(project).getValue(MyBatisRunDialog.CONNECTION_KEY, ""));
    if (last != null && all.remove(last)) all.add(0, last);
    return all;
  }

  /** 접속이 하나면 바로, 여럿이면 고르는 목록을 띄운다. */
  static void chooseConnection(Project project, Editor editor, Consumer<DbConfig> then) {
    List<DbConfig> list = connections(project);
    if (list.isEmpty()) {
      Notify.error(project, "접속이 없습니다. 먼저 데이터베이스 접속을 추가하세요.");
      return;
    }
    if (list.size() == 1) {
      then.accept(list.get(0));
      return;
    }
    JBPopupFactory.getInstance().createPopupChooserBuilder(list)
        .setTitle("실행할 접속")
        .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("", MyBatisRunDialog::connectionLabel))
        .setItemChosenCallback(then::accept)
        .createPopup()
        .showInBestPositionFor(editor);
  }

  static void runInConsole(Project project, DbConfig c, MyBatisLog.Entry entry) {
    PropertiesComponent.getInstance(project).setValue(MyBatisRunDialog.CONNECTION_KEY, c.id);
    ConsoleOpener.open(project, c, SqlValues.inline(entry.sql, entry.values, c.kind));
    SqlRunner.run(project, c, entry.sql, new ArrayList<>(entry.values));
  }

  private abstract static class LogAction extends AnAction {
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override
    public void update(@NotNull AnActionEvent e) {
      MyBatisLog.Entry entry = e.getProject() == null ? null : find(e);
      e.getPresentation().setEnabledAndVisible(entry != null && visible(e, entry));
    }

    boolean visible(AnActionEvent e, MyBatisLog.Entry entry) { return true; }
  }

  /** 「SQL 로 바꿔 복사」: 값을 채운 SQL 을 복사하고 풍선으로 보여 준다. */
  public static final class Copy extends LogAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Project project = e.getProject();
      MyBatisLog.Entry entry = find(e);
      Editor editor = editor(e);
      if (project == null || entry == null || editor == null) return;
      String sql = entry.runnableSql();
      CopyPasteManager.getInstance().setContents(new StringSelection(sql + ";"));
      int marks = MyBatisLog.anchors(entry.sql, "?").size();
      String count = marks == entry.values.size() ? "값 " + marks + "개 채움"
                                                  : "? " + marks + "개 · 값 " + entry.values.size() + "개 — 수가 다름";
      List<DbConfig> list = connections(project);
      String target = list.isEmpty() ? "" : MyBatisRunDialog.connectionLabel(list.get(0)) + (list.size() > 1 ? " 외" : "") + " · ";
      String html = "<html><b>SQL 로 바꿔 복사했습니다</b> &nbsp;" + count
                    + "<pre>" + StringUtil.escapeXmlEntities(sql) + ";</pre>"
                    + target + "<a href=\"run\">콘솔에서 실행</a> &nbsp; <a href=\"close\">닫기</a></html>";
      Balloon[] holder = new Balloon[1];
      holder[0] = JBPopupFactory.getInstance()
          .createHtmlTextBalloonBuilder(html, MessageType.INFO, ev -> {
            if (ev.getEventType() != HyperlinkEvent.EventType.ACTIVATED) return;
            holder[0].hide();
            if ("run".equals(ev.getDescription())) chooseConnection(project, editor, c -> runInConsole(project, c, entry));
          })
          .setHideOnLinkClick(false)
          .createBalloon();
      holder[0].show(JBPopupFactory.getInstance().guessBestPopupLocation(editor), Balloon.Position.below);
    }
  }

  /** 「데이터베이스 콘솔에서 실행」. */
  public static final class Run extends LogAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Project project = e.getProject();
      MyBatisLog.Entry entry = find(e);
      Editor editor = editor(e);
      if (project == null || entry == null || editor == null) return;
      chooseConnection(project, editor, c -> runInConsole(project, c, entry));
    }
  }

  /** 「MyBatis 실행 창으로 열기」: 로거 이름으로 매퍼 XML 을 찾아 로그 값을 채운 실행 창을 연다. */
  public static final class Open extends LogAction {
    @Override
    boolean visible(AnActionEvent e, MyBatisLog.Entry entry) {
      if (entry.statementId() == null) return false;
      e.getPresentation().setText("MyBatis 실행 창으로 열기 (" + entry.mapperName() + "." + entry.statementId() + ")");
      return true;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Project project = e.getProject();
      MyBatisLog.Entry entry = find(e);
      if (project == null || entry == null || entry.statementId() == null) return;
      new Task.Backgroundable(project, "매퍼 XML 찾는 중", true) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          Found found = ReadAction.nonBlocking(() -> search(project, entry)).inSmartMode(project).wrapProgress(indicator).executeSynchronously();
          ApplicationManager.getApplication().invokeLater(() -> open(project, entry, found), project.getDisposed());
        }
      }.queue();
    }

    /** 찾은 매퍼 문서 · 문장과 실행 창에 넘길 클래스 정보. */
    private record Found(MapperDoc doc, MapperDoc.Node statement, DtoFields.ForRun types) {}

    private static void open(Project project, MyBatisLog.Entry entry, Found found) {
      if (found == null) {
        Notify.error(project, "프로젝트에서 " + entry.mapperName() + "." + entry.statementId() + " 문장이 있는 매퍼 XML 을 찾지 못했습니다.");
        return;
      }
      new MyBatisRunDialog(project, found.doc(), found.statement(), MyBatisLog.prefill(found.doc(), found.statement(), entry), found.types()).show();
    }

    /**
     * 매퍼 색인에서 네임스페이스가 매퍼 이름으로 끝나고 그 문장이 있는 XML 을 고른다. 줄여 찍힌 패키지까지 맞는 것이 먼저.
     * 읽기 접근 안에서 부른다. 없으면 null.
     */
    private static Found search(Project project, MyBatisLog.Entry entry) {
      String mapper = entry.mapperName();
      MapperIndex.Entry best = null;
      for (MapperIndex.Entry e : MapperIndex.all(project)) {
        ProgressManager.checkCanceled();
        String ns = e.namespace();
        if (!(ns.equals(mapper) || ns.endsWith("." + mapper)) || !e.ids().containsKey(entry.statementId())) continue;
        if (MyBatisLog.loggerMatches(entry.loggerNamespace(), ns)) {
          best = e;
          break;
        }
        if (best == null) best = e;
      }
      String text = best == null ? null : text(best.file());
      if (text == null) return null;
      MapperDoc doc = MapperDoc.parse(text);
      MapperDoc.Node s = doc.statement(entry.statementId());
      return s == null ? null : new Found(doc, s, DtoFields.forRun(project, doc, s));
    }

    private static String text(VirtualFile file) {
      Document cached = FileDocumentManager.getInstance().getCachedDocument(file);
      if (cached != null) return cached.getText();
      try {
        return VfsUtilCore.loadText(file);
      } catch (IOException e) {
        // 읽지 못하는 파일은 후보에서 뺀다. 찾지 못하면 호출한 쪽에서 알린다.
        return null;
      }
    }
  }
}
