package ei.db.mybatis;

import com.intellij.application.options.CodeStyle;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import ei.db.EiDbBundle;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 편집기 오른쪽 클릭 「MyBatis 태그 넣기」: 매퍼 XML 에서만 보인다. 항목마다 {@link MyBatisTags.Tag} 하나를
 * 고른 줄에 감싸거나 커서 자리에 넣는다. 들여쓰기 한 단계는 그 파일의 코드 스타일을 따른다.
 */
public final class TagGroup extends ActionGroup implements DumbAware {
  private static final String MAPPER_MARK = "<mapper";

  private final AnAction[] children = build();

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    e.getPresentation().setEnabledAndVisible(editor != null && StringUtil.contains(editor.getDocument().getCharsSequence(), MAPPER_MARK));
  }

  @Override
  public AnAction @NotNull [] getChildren(@Nullable AnActionEvent e) {
    return children;
  }

  private static AnAction[] build() {
    List<AnAction> out = new ArrayList<>();
    int section = -1;
    for (MyBatisTags.Tag t : MyBatisTags.Tag.values()) {
      if (section >= 0 && t.section != section) out.add(Separator.getInstance());
      section = t.section;
      out.add(new Insert(t));
    }
    return out.toArray(AnAction.EMPTY_ARRAY);
  }

  private static final class Insert extends DumbAwareAction {
    private final MyBatisTags.Tag tag;

    Insert(MyBatisTags.Tag tag) {
      // 설명은 메뉴에서 항목 위에 마우스를 올리면 보인다.
      super(tag.label, EiDbBundle.message("tags.desc." + tag.name()), null);
      this.tag = tag;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Editor editor = e.getData(CommonDataKeys.EDITOR);
      Project project = e.getProject();
      if (editor == null || project == null) return;
      Document document = editor.getDocument();
      SelectionModel sel = editor.getSelectionModel();
      int start = sel.hasSelection() ? sel.getSelectionStart() : editor.getCaretModel().getOffset();
      int end = sel.hasSelection() ? sel.getSelectionEnd() : start;
      MyBatisTags.Edit ed;
      try {
        ed = MyBatisTags.edit(document.getText(), start, end, tag, indentUnit(e.getData(CommonDataKeys.PSI_FILE)));
      } catch (IllegalArgumentException ex) {
        Notify.error(project, ex.getMessage());
        return;
      }
      if (!FileDocumentManager.getInstance().requestWriting(document, project)) {
        Notify.error(project, EiDbBundle.message("tags.read.only"));
        return;
      }
      WriteCommandAction.runWriteCommandAction(project, EiDbBundle.message("tags.command", tag.label), null,
          () -> document.replaceString(ed.start(), ed.end(), ed.text()));
      sel.removeSelection();
      editor.getCaretModel().moveToOffset(ed.caret());
      editor.getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
    }

    /** 파일이 없으면(드문 경우) 기본 코드 스타일의 XML 들여쓰기를 쓴다. */
    private static String indentUnit(@Nullable PsiFile file) {
      CommonCodeStyleSettings.IndentOptions o = file != null ? CodeStyle.getIndentOptions(file) : CodeStyle.getDefaultSettings().getIndentOptions();
      return o.USE_TAB_CHARACTER ? "\t" : " ".repeat(Math.max(1, o.INDENT_SIZE));
    }
  }
}
