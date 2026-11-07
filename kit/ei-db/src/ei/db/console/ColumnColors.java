package ei.db.console;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import ei.db.bench.SqlParts;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * .sql 편집기(콘솔 · 쿼리 작업대 · 프로젝트 파일)에서 「별칭.칼럼」 의 칼럼을 색 없이 기본 글자색으로 칠한다.
 * TextMate SQL 문법은 칼럼 이름에 색을 넣고, STATUS · NAME 처럼 SQL 낱말과 같은 이름은 낱말 색으로 칠한다.
 * 문법 색 위 층(SYNTAX + 1)에 덮어 칠하고, 편집기를 놓을 때 문서 감시도 함께 놓는다.
 */
public final class ColumnColors implements EditorFactoryListener {
  private static final String SQL_EXTENSION = "sql";
  private static final Key<Disposable> LIFE = Key.create("ei.db.columnColors.life");
  private static final Key<List<RangeHighlighter>> MARKS = Key.create("ei.db.columnColors.marks");

  @Override
  public void editorCreated(@NotNull EditorFactoryEvent event) {
    Editor editor = event.getEditor();
    VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
    if (file == null || !SQL_EXTENSION.equalsIgnoreCase(file.getExtension())) return;
    Disposable life = Disposer.newDisposable("ei.db.columnColors");
    editor.putUserData(LIFE, life);
    // shortcut: 글자를 바꿀 때마다 문서 전체를 다시 칠한다. 수만 줄 .sql 에서 느리면 바뀐 줄 근처만 다시 칠한다.
    editor.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void documentChanged(@NotNull DocumentEvent e) { paint(editor); }
    }, life);
    paint(editor);
  }

  @Override
  public void editorReleased(@NotNull EditorFactoryEvent event) {
    Disposable life = event.getEditor().getUserData(LIFE);
    if (life != null) Disposer.dispose(life);
  }

  private static void paint(Editor editor) {
    if (editor.isDisposed()) return;
    MarkupModel markup = editor.getMarkupModel();
    List<RangeHighlighter> old = editor.getUserData(MARKS);
    if (old != null) {
      for (RangeHighlighter h : old) markup.removeHighlighter(h);
    }
    List<RangeHighlighter> marks = new ArrayList<>();
    TextAttributes look = new TextAttributes(editor.getColorsScheme().getDefaultForeground(), null, null, null, 0);
    for (int[] span : SqlParts.columnSpans(editor.getDocument().getText())) {
      marks.add(markup.addRangeHighlighter(span[0], span[1], HighlighterLayer.SYNTAX + 1, look, HighlighterTargetArea.EXACT_RANGE));
    }
    editor.putUserData(MARKS, marks);
  }
}
