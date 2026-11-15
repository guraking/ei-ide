package ei.db.mybatis;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.refactoring.rename.RenameProcessor;
import ei.db.DbConfig;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.SchemaCache;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 「칼럼 이름 바꾸기…」: 프로젝트의 모든 매퍼 XML(MapperIndex) 에서 옛 칼럼을 찾아 미리보기로 고른 곳만 한 명령으로 바꾸고,
 * DTO 도 바꾸면 짝인 DTO 필드(ColumnRename.fields: 매퍼 만들기와 같은 이름)와 그 getter · setter 를 이름 바꾸기 리팩터링으로 바꾼다.
 * 편집기 오른쪽 클릭(매퍼 XML 에서만 보임, 커서 낱말을 옛 이름으로)과 소스 메뉴에서 연다.
 */
public final class ColumnRenameAction extends AnAction {

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    boolean ok = e.getProject() != null;
    if (ok && ActionPlaces.isPopupPlace(e.getPlace())) {
      Editor editor = e.getData(CommonDataKeys.EDITOR);
      ok = editor != null && MapperDocs.isMapper(editor.getDocument().getCharsSequence());
    }
    e.getPresentation().setEnabledAndVisible(ok);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) return;
    if (DumbService.isDumb(project)) {
      Notify.warning(project, EiDbBundle.message("columnrename.dumb"));
      return;
    }
    ColumnRenameDialog dialog = new ColumnRenameDialog(project, initialWord(e.getData(CommonDataKeys.EDITOR)));
    if (!dialog.showAndGet()) return;
    ColumnRenameDialog.Input in = dialog.input();
    new Task.Backgroundable(project, EiDbBundle.message("columnrename.progress"), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        List<String> columns = columns(project, in.table());
        String[] props = ColumnRename.fields(in.oldName().trim(), in.newName().trim(), columns);
        List<ColumnRenameDialog.Row> rows = ReadAction.nonBlocking(() -> collect(project, in, columns))
            .inSmartMode(project).wrapProgress(indicator).executeSynchronously();
        ApplicationManager.getApplication().invokeLater(() -> {
          if (rows.isEmpty()) {
            Notify.info(project, EiDbBundle.message("columnrename.not.found", in.oldName()));
            return;
          }
          ColumnRenameDialog.Preview preview = new ColumnRenameDialog.Preview(project, in, rows);
          if (preview.showAndGet()) apply(project, in, preview.chosen(), props);
        }, project.getDisposed());
      }
    }.queue();
  }

  /** 매퍼 XML 편집기면 선택 글(없으면 커서 낱말), 아니면 빈 글. */
  private static String initialWord(Editor editor) {
    if (editor == null) return "";
    CharSequence text = editor.getDocument().getCharsSequence();
    if (!MapperDocs.isMapper(text)) return "";
    String selected = editor.getSelectionModel().getSelectedText();
    if (selected != null && ColumnRename.NAME.matcher(selected.trim()).matches()) return selected.trim();
    return ColumnRename.wordAt(text, editor.getCaretModel().getOffset());
  }

  /**
   * 표를 적었고 매퍼 SQL 검사와 같은 접속(MapperSqlInspection.connection)에서 그 표 칼럼을 읽어 두었으면 칼럼 이름들(표 순서), 아니면 null.
   * DB 를 기다리지 않는다(열린 접속이면 뒤에서 읽어 두기만 한다).
   */
  private static List<String> columns(Project project, String table) {
    if (table == null || table.isBlank()) return null;
    DbConfig c = MapperSqlInspection.connection(project);
    if (c == null) return null;
    List<Meta.Column> cols = SchemaCache.columns(project, c, table.trim());
    if (cols == null || cols.isEmpty()) return null;
    List<String> out = new ArrayList<>();
    for (Meta.Column col : cols) out.add(col.name());
    return out;
  }

  /** 읽기 접근 안에서 부른다. columns 는 DTO 필드 이름을 정할 그 표 칼럼(null 이면 모름). */
  private static List<ColumnRenameDialog.Row> collect(Project project, ColumnRenameDialog.Input in, List<String> columns) {
    List<ColumnRenameDialog.Row> rows = new ArrayList<>();
    for (MapperIndex.Entry entry : MapperIndex.all(project)) {
      ProgressManager.checkCanceled();
      Document doc = FileDocumentManager.getInstance().getDocument(entry.file());
      if (doc == null) continue;
      CharSequence text = doc.getImmutableCharSequence();
      for (ColumnRename.Hit h : ColumnRename.find(text, in.oldName(), in.newName(), in.table(), in.renameDto(), columns)) {
        int line = doc.getLineNumber(h.start());
        String lineText = text.subSequence(doc.getLineStartOffset(line), doc.getLineEndOffset(line)).toString().strip();
        rows.add(new ColumnRenameDialog.Row(entry.file(), doc, doc.getModificationStamp(), h, line + 1, lineText));
      }
    }
    return rows;
  }

  /** 화면 스레드에서 부른다. 찾은 뒤 문서가 바뀌었으면 아무것도 바꾸지 않는다. */
  private static void apply(Project project, ColumnRenameDialog.Input in, List<ColumnRenameDialog.Row> chosen, String[] props) {
    Map<Document, List<ColumnRenameDialog.Row>> byDoc = new LinkedHashMap<>();
    Set<VirtualFile> files = new LinkedHashSet<>();
    for (ColumnRenameDialog.Row r : chosen) {
      if (r.doc().getModificationStamp() != r.stamp()) {
        Notify.error(project, EiDbBundle.message("columnrename.changed", r.file().getName()));
        return;
      }
      byDoc.computeIfAbsent(r.doc(), k -> new ArrayList<>()).add(r);
      files.add(r.file());
    }
    if (!FileModificationService.getInstance().prepareVirtualFilesForWrite(project, files)) {
      Notify.error(project, EiDbBundle.message("columnrename.read.only"));
      return;
    }
    WriteCommandAction.writeCommandAction(project).withName(EiDbBundle.message("columnrename.title")).run(() -> {
      for (List<ColumnRenameDialog.Row> rows : byDoc.values()) {
        rows.sort(Comparator.comparingInt((ColumnRenameDialog.Row r) -> r.hit().start()).reversed());
        for (ColumnRenameDialog.Row r : rows) r.doc().replaceString(r.hit().start(), r.hit().end(), r.hit().replacement());
      }
    });
    PsiDocumentManager.getInstance(project).commitAllDocuments();
    String done = EiDbBundle.message("columnrename.done", chosen.size(), in.oldName(), in.newName());
    if (!in.renameDto()) {
      Notify.info(project, done);
      return;
    }
    renameDtoFields(project, chosen, done, props[0], props[1]);
  }

  /**
   * 고른 줄 가운데 resultMap property · #{} 의 DTO 형식에서 oldProp 필드를 찾아 newProp 로 이름 바꾼다(ColumnRename.fields 로 정한 이름).
   * 소스에 있는 getter · setter 도 함께 바꾸고, 소스에 없는 것(Lombok 따위)은 필드만 바꾼다.
   */
  private static void renameDtoFields(Project project, List<ColumnRenameDialog.Row> chosen, String done, String oldProp, String newProp) {
    Set<PsiField> fields = new LinkedHashSet<>();
    Set<String> missing = new TreeSet<>();
    for (ColumnRenameDialog.Row r : chosen) {
      String type = r.hit().dtoType();
      if (r.hit().kind() == ColumnRename.Kind.COLUMN || type == null || type.isBlank()) continue;
      PsiClass c = DtoFields.find(project, type);
      PsiField f = c == null ? null : c.findFieldByName(oldProp, true);
      if (f == null || f instanceof PsiCompiledElement || !f.isWritable()) missing.add(type.trim());
      else fields.add(f);
    }
    for (PsiField f : fields) {
      RenameProcessor processor = new RenameProcessor(project, f, newProp, false, false);
      PsiClass owner = f.getContainingClass();
      if (owner != null) {
        PsiMethod getter = PropertyUtilBase.findPropertyGetter(owner, oldProp, false, false);
        if (getter != null && getter.isPhysical()) processor.addElement(getter, PropertyUtilBase.suggestGetterName(newProp, f.getType()));
        PsiMethod setter = PropertyUtilBase.findPropertySetter(owner, oldProp, false, false);
        if (setter != null && setter.isPhysical()) processor.addElement(setter, PropertyUtilBase.suggestSetterName(newProp));
      }
      processor.setPreviewUsages(false);
      processor.run();
    }
    if (!fields.isEmpty()) done += EiDbBundle.message("columnrename.dto.done", fields.size(), newProp);
    Notify.info(project, done);
    if (!missing.isEmpty()) {
      Notify.warning(project, EiDbBundle.message("columnrename.dto.missing", String.join(", ", missing), oldProp));
    }
  }
}
