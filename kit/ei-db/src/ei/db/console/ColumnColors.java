package ei.db.console;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
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
import com.intellij.util.concurrency.AppExecutorUtil;
import ei.db.bench.SqlParts;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * .sql 편집기(콘솔 · 쿼리 작업대 · 프로젝트 파일)에서 「별칭.칼럼」 을 색 없이 기본 글자색으로 칠한다.
 * 함수 이름(SUM · TO_CHAR · DATE_TRUNC · PKG.FUNC)은 모두 함수 색(테마의 함수 선언 색, 없으면 칠하지 않음)으로 칠한다.
 * TextMate SQL 문법은 칼럼 이름에 색을 넣고, STATUS · NAME 처럼 SQL 낱말과 같은 이름은 낱말 색으로, 함수는 일부(SUM 등)만 칠한다.
 * 문법 색 위 층(SYNTAX + 1)에 덮어 칠하고, 편집기를 놓을 때 문서 감시도 함께 놓는다.
 * 칠할 자리는 글을 고칠 때마다 풀 스레드에서 다시 찾고(앞 계산은 버린다) 화면 스레드에서는 칠하기만 한다: 큰 DDL 파일에서도 타자가 끊기지 않게.
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
    editor.getDocument().addDocumentListener(new DocumentListener() {
      @Override
      public void documentChanged(@NotNull DocumentEvent e) { schedule(editor, life); }
    }, life);
    schedule(editor, life);
  }

  /** 찾은 자리. 같은 문서 판(stamp)일 때만 칠한다. */
  private record Spans(long stamp, List<int[]> columns, List<int[]> functions) {}

  /** 지금 글로 자리를 풀 스레드에서 찾고 화면 스레드에서 칠한다. 같은 편집기의 앞 요청은 버린다(coalesceBy). */
  private static void schedule(Editor editor, Disposable life) {
    Document doc = editor.getDocument();
    ReadAction.nonBlocking(() -> {
          String text = doc.getImmutableCharSequence().toString();
          return new Spans(doc.getModificationStamp(), SqlParts.columnSpans(text), SqlParts.functionSpans(text));
        })
        .coalesceBy(editor, ColumnColors.class)
        .expireWith(life)
        .finishOnUiThread(ModalityState.any(), spans -> {
          if (spans.stamp() == doc.getModificationStamp()) paint(editor, spans);
        })
        .submit(AppExecutorUtil.getAppExecutorService());
  }

  @Override
  public void editorReleased(@NotNull EditorFactoryEvent event) {
    Disposable life = event.getEditor().getUserData(LIFE);
    if (life != null) Disposer.dispose(life);
  }

  private static void paint(Editor editor, Spans spans) {
    if (editor.isDisposed()) return;
    MarkupModel markup = editor.getMarkupModel();
    List<RangeHighlighter> old = editor.getUserData(MARKS);
    if (old != null) {
      for (RangeHighlighter h : old) markup.removeHighlighter(h);
    }
    List<RangeHighlighter> marks = new ArrayList<>();
    TextAttributes look = new TextAttributes(editor.getColorsScheme().getDefaultForeground(), null, null, null, 0);
    for (int[] span : spans.columns()) {
      marks.add(markup.addRangeHighlighter(span[0], span[1], HighlighterLayer.SYNTAX + 1, look, HighlighterTargetArea.EXACT_RANGE));
    }
    TextAttributes function = editor.getColorsScheme().getAttributes(DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
    if (function != null && function.getForegroundColor() != null) {
      TextAttributes only = new TextAttributes(function.getForegroundColor(), null, null, null, 0);
      for (int[] span : spans.functions()) {
        marks.add(markup.addRangeHighlighter(span[0], span[1], HighlighterLayer.SYNTAX + 1, only, HighlighterTargetArea.EXACT_RANGE));
      }
    }
    editor.putUserData(MARKS, marks);
  }
}
