package ei.db.mybatis;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderEx;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import ei.db.DbConfig;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.SchemaCache;
import ei.db.gen.Names;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DTO 필드 전구 「매퍼에 이 필드 넣기」: 필드의 클래스를 형식으로 쓰는 매퍼가 그 필드를 아직 #{} · property 로 쓰지 않고,
 * 그 매퍼가 쓰는 테이블의 칼럼(이미 읽어 둔 SchemaCache 만 봄, DB 를 부르지 않음) 가운데 이름이 맞는 것(카멜 ↔ 스네이크)이 있으면 보인다.
 * 누르면 「DB 바뀐 것 코드에 맞추기」 창을 그 칼럼 하나로 연다.
 * 매퍼 파일마다 요약(형식 · 필드 이름 · 테이블)을 파일 수정 표시와 함께 기억해, 바뀐 파일만 다시 읽는다.
 */
public final class ColumnSyncIntention implements IntentionAction {

  /** 매퍼 XML 하나의 요약. types 는 형식 속성 값(소문자), props 는 #{} 의 마디 · property 값, tables 는 쓰는 테이블(대문자). */
  private record Summary(long stamp, Set<String> types, Set<String> props, Set<String> tables) {}

  /** 찾은 칼럼: 테이블 메타 이름과 칼럼 이름. */
  private record Hit(String table, String column) {}

  private static final Key<Map<VirtualFile, Summary>> SUMMARIES = Key.create("ei.db.mybatis.ColumnSyncIntention.summaries");
  private static final Set<String> TYPE_ATTRS = Set.of("parameterType", "resultType", "type", "ofType", "javaType");
  private static final Pattern PLACEHOLDER = Pattern.compile("[#$]\\{\\s*([A-Za-z_][A-Za-z0-9_.]*)");

  @Override
  public @NotNull String getText() { return EiDbBundle.message("columnsync.intention.text"); }

  @Override
  public @NotNull String getFamilyName() { return getText(); }

  @Override
  public boolean startInWriteAction() { return false; }

  /** 창을 여는 일이라 미리보기에서 돌리지 않는다. */
  @Override
  public @NotNull IntentionPreviewInfo generatePreview(@NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
    return IntentionPreviewInfo.EMPTY;
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
    return editor != null && hit(project, editor, file) != null;
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
    Hit h = editor == null ? null : hit(project, editor, file);
    DbConfig c = MapperSqlInspection.connection(project);
    if (h == null || c == null) {
      Notify.warning(project, EiDbBundle.message("columnsync.intention.gone"));
      return;
    }
    ColumnSyncAction.open(project, c, null, h.table(), h.column());
  }

  private static @Nullable Hit hit(Project project, Editor editor, PsiFile file) {
    if (!(file instanceof PsiJavaFile) || DumbService.isDumb(project)) return null;
    PsiField f = PsiTreeUtil.getParentOfType(file.findElementAt(editor.getCaretModel().getOffset()), PsiField.class, false);
    PsiClass owner = f == null ? null : f.getContainingClass();
    if (owner == null || owner.getQualifiedName() == null || f.hasModifierProperty(PsiModifier.STATIC)) return null;
    DbConfig c = MapperSqlInspection.connection(project);
    if (c == null) return null;
    String schema = Meta.schemaOf(c);
    List<Meta.Table> known = SchemaCache.cachedTables(c, schema);
    if (known == null || known.isEmpty()) return null;
    Set<String> names = Set.of(owner.getQualifiedName().toLowerCase(Locale.ROOT), String.valueOf(owner.getName()).toLowerCase(Locale.ROOT));
    Set<String> tables = new HashSet<>();
    boolean used = false;
    for (MapperIndex.Entry e : MapperIndex.all(project)) {
      if (e.namespace() == null) continue;
      Summary s = summary(project, e.file());
      if (s == null || !intersects(s.types(), names)) continue;
      if (s.props().contains(f.getName())) return null;
      used = true;
      tables.addAll(s.tables());
    }
    if (!used) return null;
    for (Meta.Table t : known) {
      if (!tables.contains(t.name().toUpperCase(Locale.ROOT))) continue;
      List<Meta.Column> cols = SchemaCache.cachedColumns(c, schema, t.name());
      if (cols == null) continue;
      for (Meta.Column col : cols) {
        if (Names.camel(col.name()).equals(f.getName())) return new Hit(t.name(), col.name());
      }
    }
    return null;
  }

  private static boolean intersects(Set<String> a, Set<String> b) {
    for (String s : b) if (a.contains(s)) return true;
    return false;
  }

  /**
   * 파일 요약. 수정 표시가 같으면 기억한 것. 화면 스레드에서는 새로 읽지 않는다(없으면 null): 전구를 그리는 백그라운드 검사가 먼저 채운다.
   */
  private static @Nullable Summary summary(Project project, VirtualFile file) {
    Map<VirtualFile, Summary> cache = ((UserDataHolderEx) project).putUserDataIfAbsent(SUMMARIES, new ConcurrentHashMap<>());
    if (!file.isValid()) {
      cache.remove(file);
      return null;
    }
    Document d = FileDocumentManager.getInstance().getCachedDocument(file);
    long stamp = d != null ? d.getModificationStamp() : file.getModificationStamp();
    Summary s = cache.get(file);
    if (s != null && s.stamp() == stamp) return s;
    if (ApplicationManager.getApplication().isDispatchThread()) return null;
    CharSequence text;
    try {
      text = d != null ? d.getImmutableCharSequence() : VfsUtilCore.loadText(file);
    } catch (IOException ex) {
      // 읽지 못한 파일은 이번 판단에서 빠질 뿐이고 다음에 다시 읽는다.
      return null;
    }
    MapperDoc doc = MapperDoc.parse(text);
    Set<String> types = new HashSet<>();
    Set<String> props = new HashSet<>();
    collect(doc.root, types, props);
    Matcher m = PLACEHOLDER.matcher(text);
    while (m.find()) {
      for (String part : m.group(1).split("\\.")) props.add(part);
    }
    Set<String> tables = new HashSet<>();
    for (MapperSqlScan.Table t : MapperSqlScan.scan(text, doc).tables()) tables.add(MapperSqlScan.bare(t.name()).toUpperCase(Locale.ROOT));
    s = new Summary(stamp, types, props, tables);
    cache.put(file, s);
    return s;
  }

  private static void collect(MapperDoc.Node n, Set<String> types, Set<String> props) {
    for (MapperDoc.Node c : n.children) {
      if (c.name == null) continue;
      for (String a : TYPE_ATTRS) {
        String v = c.attr(a);
        if (v == null || v.isBlank()) continue;
        String t = v.trim().toLowerCase(Locale.ROOT);
        types.add(t);
        types.add(t.substring(t.lastIndexOf('.') + 1));
      }
      String p = c.attr("property");
      if (p != null) props.add(p.trim());
      collect(c, types, props);
    }
  }
}
