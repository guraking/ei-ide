package ei.db.console;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.bench.DbErrors;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.lang.reflect.Method;
import java.sql.SQLException;

/**
 * 오류 탭 위의 쉬운 설명: 무슨 오류인지 · 왜 나는지 · 어떻게 고치는지, 그리고 「오류 위치로 가기」.
 * 위치는 Oracle 드라이버가 주는 오류 위치(getErrorPosition) · PostgreSQL 의 Position · 메시지 속 이름 순으로 찾는다.
 * 실행한 문장이 열린 콘솔에 그대로 있을 때만 위치로 갈 수 있다(값 자리를 바꾼 문장은 콘솔 글과 달라 못 간다).
 */
final class ErrorHelp {
  private ErrorHelp() {}

  private static final String ORACLE_POSITION = "getErrorPosition";
  private static final int ICON_GAP = 8;

  /** 아는 오류면 설명 칸, 모르면 null. */
  static @Nullable JComponent panel(Project project, DbConfig c, String sql, Exception error) {
    SQLException se = sqlException(error);
    DbErrors.Help help = DbErrors.explain(c.kind, se == null ? 0 : se.getErrorCode(), se == null ? null : se.getSQLState(),
        ResultView.message(error), sql);
    if (help == null) return null;
    int driverPosition = oraclePosition(error);
    if (help.position() < 0 && driverPosition >= 0) {
      help = new DbErrors.Help(help.code(), help.title(), help.cause(), help.fix(), help.hints(), driverPosition, help.word());
    }
    StringBuilder sb = new StringBuilder();
    sb.append(help.code() == null ? "" : help.code() + "  ").append(help.title()).append('\n');
    sb.append("왜: ").append(help.cause()).append('\n');
    sb.append("고치기: ").append(help.fix());
    for (String h : help.hints()) sb.append("\n· ").append(h);
    JBTextArea text = new JBTextArea(sb.toString());
    text.setEditable(false);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setOpaque(false);
    JBLabel icon = new JBLabel(AllIcons.General.Information);
    icon.setVerticalAlignment(SwingConstants.TOP);
    JPanel panel = new JPanel(new BorderLayout(JBUI.scale(ICON_GAP), 0));
    panel.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor()), JBUI.Borders.empty(8, 12)));
    panel.setBackground(UIUtil.getPanelBackground());
    panel.add(icon, BorderLayout.WEST);
    panel.add(text, BorderLayout.CENTER);
    int at = DbErrors.locate(help, sql);
    if (at >= 0) {
      ActionLink go = new ActionLink("오류 위치로 가기", e -> {
        if (!jump(project, c, sql, at)) ei.db.Notify.info(project, "실행한 문장을 연 콘솔에서 찾지 못했습니다(값 자리를 바꿨거나 문장을 고쳤습니다).");
      });
      JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      row.setOpaque(false);
      row.add(go);
      panel.add(row, BorderLayout.SOUTH);
    }
    return panel;
  }

  /** 그 접속의 열린 콘솔에서 문장을 찾아 offset 으로 옮긴다. 못 찾으면 false. */
  private static boolean jump(Project project, DbConfig c, String sql, int offset) {
    FileEditorManager fem = FileEditorManager.getInstance(project);
    for (VirtualFile f : fem.getOpenFiles()) {
      if (!ConsoleOpener.isConsole(f)) continue;
      DbConfig fc = ConsoleOpener.config(project, f);
      if (fc == null || !fc.id.equals(c.id)) continue;
      for (com.intellij.openapi.fileEditor.FileEditor fe : fem.getEditors(f)) {
        if (!(fe instanceof com.intellij.openapi.fileEditor.TextEditor te)) continue;
        Editor editor = te.getEditor();
        int start = editor.getDocument().getText().indexOf(sql);
        if (start < 0) continue;
        new OpenFileDescriptor(project, f, start + offset).navigate(true);
        return true;
      }
    }
    return false;
  }

  private static @Nullable SQLException sqlException(Throwable t) {
    for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
      if (x instanceof SQLException s) return s;
    }
    return null;
  }

  /** Oracle 드라이버(OracleDatabaseException)의 0 부터 센 오류 위치. 없으면 -1. 드라이버가 빌드 경로에 없어 이름으로 부른다. */
  private static int oraclePosition(Throwable t) {
    for (Throwable x = t; x != null; x = x.getCause() == x ? null : x.getCause()) {
      try {
        Method m = x.getClass().getMethod(ORACLE_POSITION);
        Object v = m.invoke(x);
        if (v instanceof Integer i && i >= 0) return i;
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // 이 예외에는 위치가 없다. 다음 원인을 본다.
      }
    }
    return -1;
  }
}
