package ei.db.mybatis;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.options.OptPane;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import ei.db.DbConfig;
import ei.db.DbSettings;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.SchemaCache;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 매퍼 XML 의 SQL 에서 DB 에 없는 칼럼(경고)과 표(약한 경고)를 짚는다. 이름 뽑기는 {@link MapperSqlScan}.
 * 접속은 매퍼 기능들이 쓰는 것과 같다: 매퍼 실행 창에서 마지막에 고른 접속, 없으면 접속이 하나뿐일 때 그것. 스키마는 그 접속의 기본 스키마.
 * DB 를 부르지 않는다: 이미 읽어 둔 SchemaCache 만 보고(읽기를 걸지도 않는다), 표 목록이 없으면 아무것도 짚지 않으며
 * 칼럼은 칼럼 목록을 읽어 둔 표(탐색기에서 펼쳤거나 자동완성 · 작업대가 읽은 표)만 본다.
 * 표 없음은 약한 경고다: 캐시에는 기본 스키마의 표 · 뷰만 있어 동의어 · 다른 스키마(search_path)의 표를 알 수 없다. 설정에서 끌 수 있다.
 * 캐시가 채워지거나 비워지면 열린 매퍼 파일을 다시 검사한다.
 */
public final class MapperSqlInspection extends LocalInspectionTool {
  private static final String XML_EXTENSION = "xml";
  private static final AtomicBoolean RESTART_PENDING = new AtomicBoolean();

  static {
    SchemaCache.onChange(MapperSqlInspection::restartLater);
  }

  /** 설정 항목: 표 목록에 없는 표도 짚는다. */
  public boolean reportTables = true;

  @Override
  public @NotNull OptPane getOptionsPane() {
    return OptPane.pane(OptPane.checkbox("reportTables", EiDbBundle.message("inspection.mapper.sql.option.tables")));
  }

  @Override
  public ProblemDescriptor[] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
    if (!MapperDocs.isMapper(file)) return null;
    DbConfig c = connection(file.getProject());
    if (c == null) return null;
    String schema = Meta.schemaOf(c);
    List<Meta.Table> known = SchemaCache.cachedTables(c, schema);
    if (known == null || known.isEmpty()) return null;
    CharSequence text = file.getViewProvider().getContents();
    MapperDoc doc = MapperDocs.of(file);
    // 문서가 아직 커밋되지 않아 두 글이 다르면 위치가 어긋난다. 다음 검사에 맡긴다.
    if (doc.root.end != text.length()) return null;
    Map<String, String> metaNames = new HashMap<>();
    for (Meta.Table t : known) metaNames.putIfAbsent(t.name().toUpperCase(Locale.ROOT), t.name());

    MapperSqlScan.Result found = MapperSqlScan.scan(text, doc);
    List<ProblemDescriptor> out = new ArrayList<>();
    if (reportTables) {
      for (MapperSqlScan.Table t : found.tables()) {
        ProgressManager.checkCanceled();
        String bare = MapperSqlScan.bare(t.name());
        if (!inSchema(t, schema) || metaNames.containsKey(bare.toUpperCase(Locale.ROOT)) || MapperSqlScan.systemName(bare)) continue;
        out.add(manager.createProblemDescriptor(file, new TextRange(t.start(), t.end()),
            EiDbBundle.message("inspection.mapper.sql.no.table", bare, c.name), ProblemHighlightType.WEAK_WARNING, isOnTheFly));
      }
    }
    Map<String, Set<String>> columnsOf = new HashMap<>();
    for (MapperSqlScan.Column col : found.columns()) {
      ProgressManager.checkCanceled();
      if (!inSchema(col.table(), schema)) continue;
      String meta = metaNames.get(MapperSqlScan.bare(col.table().name()).toUpperCase(Locale.ROOT));
      if (meta == null) continue;
      Set<String> columns = columnsOf.computeIfAbsent(meta, m -> columnNames(c, schema, m));
      String name = MapperSqlScan.bare(col.name());
      if (columns.isEmpty() || columns.contains(name.toUpperCase(Locale.ROOT))) continue;
      out.add(manager.createProblemDescriptor(file, new TextRange(col.start(), col.end()),
          EiDbBundle.message("inspection.mapper.sql.no.column", name, meta), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly));
    }
    return out.isEmpty() ? null : out.toArray(ProblemDescriptor.EMPTY_ARRAY);
  }

  /** 매퍼 실행 창에서 마지막에 고른 접속, 없으면 접속이 하나뿐일 때 그것(주석 만들기의 DB 주석과 같은 규칙). 못 정하면 null. */
  private static DbConfig connection(Project project) {
    DbSettings settings = DbSettings.getInstance();
    DbConfig c = settings.find(PropertiesComponent.getInstance(project).getValue(MyBatisRunDialog.CONNECTION_KEY, ""));
    if (c == null && settings.connections().size() == 1) c = settings.connections().get(0);
    return c;
  }

  /** 스키마를 안 썼거나 기본 스키마를 쓴 표. 다른 스키마의 표는 캐시에 없어 보지 않는다. */
  private static boolean inSchema(MapperSqlScan.Table t, String schema) {
    return t.schema() == null || MapperSqlScan.bare(t.schema()).equalsIgnoreCase(schema);
  }

  /** 읽어 둔 칼럼 이름(대문자). 아직 읽지 않은 표는 빈 집합(검사하지 않음). 대소문자만 다른 이름은 같은 것으로 본다. */
  private static Set<String> columnNames(DbConfig c, String schema, String table) {
    List<Meta.Column> cached = SchemaCache.cachedColumns(c, schema, table);
    Set<String> out = new HashSet<>();
    if (cached == null) return out;
    for (Meta.Column col : cached) out.add(col.name().toUpperCase(Locale.ROOT));
    return out;
  }

  /** 열린 매퍼 XML 을 다시 검사하게 한다. 연달아 불려도 화면 스레드에서 한 번만 돈다. 어느 스레드에서나 부른다. */
  private static void restartLater() {
    if (!RESTART_PENDING.compareAndSet(false, true)) return;
    ApplicationManager.getApplication().invokeLater(() -> {
      RESTART_PENDING.set(false);
      for (Project project : ProjectManager.getInstance().getOpenProjects()) {
        if (project.isDisposed()) continue;
        for (VirtualFile f : FileEditorManager.getInstance(project).getOpenFiles()) {
          if (!f.isValid() || !XML_EXTENSION.equalsIgnoreCase(f.getExtension())) continue;
          PsiFile psi = ReadAction.compute(() -> {
            PsiFile p = PsiManager.getInstance(project).findFile(f);
            return p != null && MapperDocs.isMapper(p) ? p : null;
          });
          if (psi != null) DaemonCodeAnalyzer.getInstance(project).restart(psi);
        }
      }
    });
  }
}
