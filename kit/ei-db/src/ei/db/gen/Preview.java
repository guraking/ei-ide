package ei.db.gen;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import ei.db.FileTypes;

import javax.swing.JComponent;

/** 읽기 전용 미리보기 편집기. 확장자(xml · java)로 색을 입히고 parent 가 정리될 때 놓는다. */
final class Preview {
  private final Document doc;
  private final Editor editor;

  Preview(Project project, String extension, Disposable parent) {
    doc = EditorFactory.getInstance().createDocument("");
    editor = EditorFactory.getInstance().createViewer(doc, project);
    if (editor instanceof EditorEx ex) {
      ex.setHighlighter(EditorHighlighterFactory.getInstance()
                            .createEditorHighlighter(project, FileTypes.byExtension(extension)));
    }
    editor.getSettings().setLineNumbersShown(true);
    editor.getSettings().setFoldingOutlineShown(false);
    editor.getSettings().setAdditionalLinesCount(0);
    Disposer.register(parent, () -> EditorFactory.getInstance().releaseEditor(editor));
  }

  JComponent component() {
    return editor.getComponent();
  }

  void setText(String text) {
    String t = text.replace("\r", "");
    if (t.equals(doc.getText())) return;
    ApplicationManager.getApplication().runWriteAction(() -> doc.setText(t));
  }
}
