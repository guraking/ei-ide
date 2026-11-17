package ei.db.mybatis;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.generation.GenerateMembersUtil;
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
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PropertyUtilBase;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.SchemaCache;
import ei.db.gen.Names;
import ei.db.gen.TypeMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「DB 바뀐 것 코드에 맞추기…」: 테이블의 지금 칼럼(DB 에서 새로 읽음)과 그 테이블을 쓰는 매퍼 문장 · resultMap · DTO 를 견줘
 * 새 칼럼 넣기 · 없어진 칼럼 지우기 · DTO @Size/@Length 길이 맞추기를 미리보기로 보여 주고 고른 것만 한 명령(되돌리기 한 번)으로 고친다.
 * 매퍼 쪽 판단은 {@link ColumnSync}. 확실히 고칠 수 없는 곳은 「직접 고치세요」 항목(이동만)으로 보인다.
 * 여는 곳: 매퍼 XML 에디터 우클릭(커서가 테이블 이름 위일 때), DB 탐색기 테이블 우클릭, DTO 필드 전구(ColumnSyncIntention, 칼럼 하나).
 */
public final class ColumnSyncAction extends AnAction {

  /** 「새 칼럼」 판정에 쓰는 낱말(SQL 식별자). */
  private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");
  private static final Set<String> LOMBOK_ACCESSORS = Set.of("lombok.Data", "lombok.Getter", "lombok.Setter", "lombok.Value");
  private static final Set<String> LENGTH_ANNOTATIONS = Set.of("jakarta.validation.constraints.Size", "javax.validation.constraints.Size",
      "org.hibernate.validator.constraints.Length");
  private static final String MAX_ATTR = "max";
  private static final String RESULT_MAP_TAG = "resultMap";
  /** 미리보기에서 고친 곳 앞뒤로 함께 보이는 줄 수. */
  private static final int PREVIEW_CONTEXT_LINES = 2;
  /** jdbcType: TypeMap 의 자바 형식 → MyBatis JdbcType 이름. 없는 형식은 jdbcType 을 뗀다. */
  private static final Map<String, String> JDBC_TYPES = Map.of(
      TypeMap.STRING, "VARCHAR", TypeMap.LONG, "NUMERIC", TypeMap.INTEGER, "NUMERIC", TypeMap.DECIMAL, "NUMERIC",
      TypeMap.DATE_TIME, "TIMESTAMP", "LocalDate", "DATE", TypeMap.BYTES, "BLOB");

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    boolean ok = e.getProject() != null;
    if (ok && ActionPlaces.isPopupPlace(e.getPlace())) {
      Editor editor = e.getData(CommonDataKeys.EDITOR);
      ok = editor != null && tableAt(editor) != null;
    }
    e.getPresentation().setEnabledAndVisible(ok);
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    if (project == null) return;
    MapperSqlScan.Table t = editor == null ? null : tableAt(editor);
    if (t == null) {
      Notify.warning(project, EiDbBundle.message("columnsync.no.table.at.caret"));
      return;
    }
    DbConfig c = MapperSqlInspection.connection(project);
    if (c == null) {
      Notify.warning(project, EiDbBundle.message("columnsync.no.connection"));
      return;
    }
    open(project, c, t.schema() == null ? null : MapperSqlScan.bare(t.schema()), MapperSqlScan.bare(t.name()), null);
  }

  /** 매퍼 XML 에디터에서 커서가 올라 있는 테이블 이름(FROM · JOIN · INSERT INTO · UPDATE 뒤). 없으면 null. */
  private static @Nullable MapperSqlScan.Table tableAt(Editor editor) {
    CharSequence text = editor.getDocument().getImmutableCharSequence();
    if (!MapperDocs.isMapper(text)) return null;
    int caret = editor.getCaretModel().getOffset();
    MapperDoc doc = MapperDoc.parse(text);
    for (MapperSqlScan.Table t : MapperSqlScan.scan(text, doc).tables()) {
      if (caret >= t.start() && caret <= t.end()) return t;
    }
    return null;
  }

  /**
   * 화면 스레드에서 부른다. table 의 칼럼을 c 에서 새로 읽고(schema 가 비면 기본 스키마) 고칠 것을 모아 창을 연다.
   * onlyColumn 이 있으면 그 칼럼 하나만 넣는다(DTO 전구: 지우기 · 길이 · DTO 쪽 항목 없음).
   */
  public static void open(Project project, DbConfig c, @Nullable String schema, String table, @Nullable String onlyColumn) {
    if (DumbService.isDumb(project)) {
      Notify.warning(project, EiDbBundle.message("columnsync.dumb"));
      return;
    }
    new Task.Backgroundable(project, EiDbBundle.message("columnsync.progress"), true) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        String sch = schema == null || schema.isBlank() ? Meta.schemaOf(c) : schema;
        List<Meta.Column> cols;
        DbSessions sessions = DbSessions.get(project);
        try {
          Connection conn = sessions.connection(c);
          cols = SchemaCache.reloadColumns(conn, c, sch, table);
          String stored = Meta.storedName(c, table);
          if (cols.isEmpty() && !stored.equals(table)) cols = SchemaCache.reloadColumns(conn, c, sch, stored);
        } catch (SQLException ex) {
          Notify.error(project, EiDbBundle.message("columnsync.db.failed", table, String.valueOf(ex.getMessage())));
          return;
        } finally {
          sessions.endReadTransaction(c);
        }
        if (cols.isEmpty()) {
          Notify.warning(project, EiDbBundle.message("columnsync.no.table", table, c.name));
          return;
        }
        if (onlyColumn != null && find(cols, onlyColumn) == null) {
          Notify.warning(project, EiDbBundle.message("columnsync.no.column", onlyColumn, table));
          return;
        }
        List<Meta.Column> columns = cols;
        Plan plan = ReadAction.nonBlocking(() -> collect(project, sch, table, columns, onlyColumn))
            .inSmartMode(project).wrapProgress(indicator).executeSynchronously();
        ApplicationManager.getApplication().invokeLater(() -> {
          if (plan.rows().isEmpty()) {
            Notify.info(project, EiDbBundle.message("columnsync.in.sync", table));
            return;
          }
          ColumnSyncDialog dialog = new ColumnSyncDialog(project, plan);
          if (dialog.showAndGet()) apply(project, dialog.chosen());
        }, project.getDisposed());
      }
    }.queue();
  }

  private static @Nullable Meta.Column find(List<Meta.Column> cols, String name) {
    for (Meta.Column col : cols) if (col.name().equalsIgnoreCase(name)) return col;
    return null;
  }

  // ---------------------------------------------------------------- 모으기

  /** 고칠 것 하나(창의 한 줄). mapper 쪽이면 change, DTO 쪽이면 dto. checkable 이 거짓이면 손으로 할 일 · 알림이다. */
  record Row(String label, boolean checkable, boolean checked, VirtualFile file, String group, int at, String preview,
             @Nullable Document doc, long stamp, @Nullable ColumnSync.Change change, @Nullable DtoEdit dto) {}

  /** DTO 쪽 고칠 것. field 가 있으면 필드 넣기, sizeValue 가 있으면 길이 값 바꾸기. owner 는 고칠 클래스. */
  record DtoEdit(SmartPsiElementPointer<PsiClass> owner, long stamp, @Nullable String fieldText, boolean accessors,
                 @Nullable SmartPsiElementPointer<PsiAnnotationMemberValue> sizeValue, int newSize) {}

  /** 창에 넘길 것: 테이블 이름 · 요약 글 · 줄들. */
  record Plan(String table, String summary, List<Row> rows) {}

  /** 매퍼 파일 하나에서 이 테이블과 이어진 것. */
  private record Mapper(VirtualFile file, Document doc, CharSequence text, MapperDoc parsed, MapperSqlScan.Result scan,
                        List<MapperDoc.Node> statements, Set<MapperDoc.Node> fragments, Set<MapperDoc.Node> resultMaps) {}

  /** 읽기 접근 · 색인 끝난 상태에서 부른다. */
  static Plan collect(Project project, String schema, String table, List<Meta.Column> cols, @Nullable String onlyColumn) {
    Set<String> db = new LinkedHashSet<>();
    List<String> names = new ArrayList<>();
    for (Meta.Column col : cols) {
      db.add(col.name().toUpperCase(Locale.ROOT));
      names.add(col.name());
    }
    Map<String, String> fields = Names.uniqueCamel(names);
    List<Mapper> mappers = new ArrayList<>();
    Set<String> known = new HashSet<>();
    Set<String> usedInCode = new LinkedHashSet<>();
    Map<String, VirtualFile> dtoTypes = new LinkedHashMap<>();
    for (MapperIndex.Entry entry : sourceEntries(project)) {
      ProgressManager.checkCanceled();
      Mapper m = mapper(entry.file(), schema, table);
      if (m == null) continue;
      mappers.add(m);
      for (MapperDoc.Node st : m.statements()) {
        List<int[]> ranges = CodeFlow.ranges(m.parsed(), st);
        for (int[] r : ranges) {
          Matcher w = WORD.matcher(m.text().subSequence(r[0], Math.min(r[1], m.text().length())));
          while (w.find()) known.add(w.group().toUpperCase(Locale.ROOT));
        }
        for (MapperSqlScan.Column col : m.scan().columns()) {
          if (inside(ranges, col.start()) && MapperSqlScan.bare(col.table().name()).equalsIgnoreCase(table)) {
            usedInCode.add(MapperSqlScan.bare(col.name()).toUpperCase(Locale.ROOT));
          }
        }
        addType(dtoTypes, st.attr("parameterType"), m.file());
        addType(dtoTypes, st.attr("resultType"), m.file());
      }
      for (MapperDoc.Node rm : m.resultMaps()) {
        addType(dtoTypes, rm.attr("type"), m.file());
        for (MapperDoc.Node c : rm.children) {
          if (c.name != null && c.attr("column") != null) known.add(c.attr("column").trim().toUpperCase(Locale.ROOT));
        }
      }
    }
    List<PsiClass> dtos = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Map.Entry<String, VirtualFile> t : dtoTypes.entrySet()) {
      PsiClass c = DtoFields.find(project, t.getKey(), t.getValue());
      if (c == null || DtoFields.isMap(c) || c.getQualifiedName() == null || !seen.add(c.getQualifiedName())) continue;
      dtos.add(c);
    }
    Set<String> dtoFieldNames = new HashSet<>();
    for (PsiClass c : dtos) dtoFieldNames.addAll(DtoFields.of(c));
    Set<String> globalNew = new LinkedHashSet<>();
    for (String name : names) {
      String up = name.toUpperCase(Locale.ROOT);
      if (!known.contains(up) && !dtoFieldNames.contains(fields.get(name))) globalNew.add(up);
    }

    List<ColumnSync.NewColumn> adds = new ArrayList<>();
    Set<String> missingOk = new HashSet<>(globalNew);
    Set<String> dropped = new LinkedHashSet<>();
    for (Meta.Column col : cols) {
      String up = col.name().toUpperCase(Locale.ROOT);
      boolean add = onlyColumn == null ? globalNew.contains(up) : col.name().equalsIgnoreCase(onlyColumn);
      if (add) adds.add(newColumn(col, fields.get(col.name())));
    }
    if (onlyColumn != null) {
      missingOk.add(onlyColumn.toUpperCase(Locale.ROOT));
    } else {
      for (String used : usedInCode) if (!db.contains(used)) dropped.add(used);
    }

    List<Row> rows = new ArrayList<>();
    for (Mapper m : mappers) {
      List<ColumnSync.Change> changes = new ArrayList<>();
      for (MapperDoc.Node st : m.statements()) changes.addAll(ColumnSync.forStatement(m.text(), st, m.scan(), table, db, adds, missingOk, dropped));
      for (MapperDoc.Node f : m.fragments()) changes.addAll(ColumnSync.forStatement(m.text(), f, m.scan(), table, db, List.of(), Set.of(), dropped));
      for (MapperDoc.Node rm : m.resultMaps()) changes.addAll(ColumnSync.forResultMap(m.text(), rm, db, adds, dropped));
      for (ColumnSync.Change c : changes) rows.add(mapperRow(m, c));
    }
    int sizes = 0;
    if (onlyColumn == null) {
      for (PsiClass c : dtos) sizes += dtoRows(project, c, cols, fields, adds, dropped, rows);
    }
    String summary = EiDbBundle.message("columnsync.summary", table, adds.size(), dropped.size(), sizes);
    return new Plan(table, summary, rows);
  }

  /** 소스 쪽 매퍼 XML 들. 같은 namespace 에 소스 · 리소스 폴더의 것이 있으면 빌드 출력 복사본은 뺀다(CodeFlow 와 같은 규칙). */
  private static List<MapperIndex.Entry> sourceEntries(Project project) {
    Map<String, List<MapperIndex.Entry>> byNamespace = new LinkedHashMap<>();
    for (MapperIndex.Entry e : MapperIndex.all(project)) {
      if (e.namespace() != null) byNamespace.computeIfAbsent(e.namespace(), k -> new ArrayList<>()).add(e);
    }
    ProjectFileIndex index = ProjectFileIndex.getInstance(project);
    List<MapperIndex.Entry> out = new ArrayList<>();
    for (List<MapperIndex.Entry> same : byNamespace.values()) {
      List<MapperIndex.Entry> sources = new ArrayList<>();
      for (MapperIndex.Entry e : same) if (index.isInSourceContent(e.file())) sources.add(e);
      out.addAll(sources.isEmpty() ? same : sources);
    }
    return out;
  }

  /** file 이 table 을 쓰는 문장을 가지면 그 문장 · 포함 조각 · resultMap, 아니면 null. */
  private static @Nullable Mapper mapper(VirtualFile file, String schema, String table) {
    Document d = file.isValid() ? FileDocumentManager.getInstance().getDocument(file) : null;
    if (d == null) return null;
    CharSequence text = d.getImmutableCharSequence();
    MapperDoc doc = MapperDoc.parse(text);
    if (doc.namespace == null) return null;
    MapperSqlScan.Result scan = MapperSqlScan.scan(text, doc);
    List<MapperDoc.Node> statements = new ArrayList<>();
    Set<MapperDoc.Node> fragments = new LinkedHashSet<>();
    Set<String> mapIds = new HashSet<>();
    for (MapperDoc.Node st : doc.statements) {
      List<int[]> ranges = CodeFlow.ranges(doc, st);
      boolean uses = false;
      for (MapperSqlScan.Table t : scan.tables()) {
        if (inside(ranges, t.start()) && MapperSqlScan.bare(t.name()).equalsIgnoreCase(table)
            && (t.schema() == null || MapperSqlScan.bare(t.schema()).equalsIgnoreCase(schema))) {
          uses = true;
          break;
        }
      }
      if (!uses) continue;
      statements.add(st);
      for (MapperDoc.Node f : doc.fragments.values()) {
        if (inside(ranges, f.start)) fragments.add(f);
      }
      String rm = st.attr(RESULT_MAP_TAG);
      if (rm != null) {
        for (String id : rm.split(",")) {
          String local = id.trim();
          if (doc.namespace != null && local.startsWith(doc.namespace + ".")) local = local.substring(doc.namespace.length() + 1);
          mapIds.add(local);
        }
      }
    }
    if (statements.isEmpty()) return null;
    Set<MapperDoc.Node> maps = new LinkedHashSet<>();
    for (MapperDoc.Node root : doc.root.children) {
      if (!"mapper".equals(root.name)) continue;
      for (MapperDoc.Node c : root.children) {
        if (RESULT_MAP_TAG.equals(c.name) && mapIds.contains(c.attr("id"))) maps.add(c);
      }
    }
    return new Mapper(file, d, text, doc, scan, statements, fragments, maps);
  }

  private static boolean inside(List<int[]> ranges, int at) {
    for (int[] r : ranges) if (at >= r[0] && at < r[1]) return true;
    return false;
  }

  private static void addType(Map<String, VirtualFile> types, @Nullable String type, VirtualFile from) {
    if (type != null && !type.isBlank()) types.putIfAbsent(type.trim(), from);
  }

  private static ColumnSync.NewColumn newColumn(Meta.Column col, String field) {
    String java = TypeMap.forColumn(col.type(), col.size(), col.scale());
    return new ColumnSync.NewColumn(col.name(), field, col.comment() == null ? "" : col.comment(), JDBC_TYPES.get(java));
  }

  private static Row mapperRow(Mapper m, ColumnSync.Change c) {
    String part = EiDbBundle.message("columnsync.part." + c.part().name().toLowerCase(Locale.ROOT));
    String label = EiDbBundle.message("columnsync.row." + c.kind().name().toLowerCase(Locale.ROOT), c.column(), c.owner(), part);
    boolean checkable = !c.edits().isEmpty();
    return new Row(label, checkable, checkable && c.checked(), m.file(), m.file().getName(), c.at(), preview(m.text(), c), m.doc(),
        m.doc().getModificationStamp(), c, null);
  }

  /** 고친 뒤 글에서 고친 곳 앞뒤 몇 줄. 손으로 할 일이면 그 자리 줄. */
  private static String preview(CharSequence text, ColumnSync.Change c) {
    if (c.edits().isEmpty()) return lines(text, c.at(), c.at());
    int from = Integer.MAX_VALUE;
    int to = 0;
    int grow = 0;
    for (ColumnSync.Edit e : c.edits()) {
      from = Math.min(from, e.start());
      to = Math.max(to, e.end());
      grow += e.text().length() - (e.end() - e.start());
    }
    String after = ColumnSync.apply(text, c.edits());
    return lines(after, from, Math.min(after.length(), to + Math.max(grow, 0)));
  }

  private static String lines(CharSequence text, int from, int to) {
    int a = Math.max(0, Math.min(from, text.length()));
    int b = Math.max(a, Math.min(to, text.length()));
    for (int n = 0; n <= PREVIEW_CONTEXT_LINES && a > 0; n++) {
      a--;
      while (a > 0 && text.charAt(a - 1) != '\n') a--;
    }
    for (int n = 0; n <= PREVIEW_CONTEXT_LINES && b < text.length(); n++) {
      while (b < text.length() && text.charAt(b) != '\n') b++;
      if (n < PREVIEW_CONTEXT_LINES && b < text.length()) b++;
    }
    return text.subSequence(a, b).toString().replace("\r", "");
  }

  /** DTO 하나의 필드 넣기 · 길이 맞추기 · 없어진 칼럼 필드 알림 줄. 길이 줄 수를 돌려준다. */
  private static int dtoRows(Project project, PsiClass c, List<Meta.Column> cols, Map<String, String> fields,
                             List<ColumnSync.NewColumn> adds, Set<String> dropped, List<Row> rows) {
    PsiFile file = c.getContainingFile();
    VirtualFile vf = file == null ? null : file.getVirtualFile();
    if (vf == null || c instanceof PsiCompiledElement || !c.isWritable()) return 0;
    SmartPointerManager pointers = SmartPointerManager.getInstance(project);
    SmartPsiElementPointer<PsiClass> owner = pointers.createSmartPsiElementPointer(c);
    long stamp = file.getModificationStamp();
    String group = c.getName() + ".java";
    boolean accessors = hasAccessors(c);
    for (ColumnSync.NewColumn nc : adds) {
      if (c.findFieldByName(nc.field(), true) != null) continue;
      Meta.Column col = find(cols, nc.name());
      String text = fieldText(nc, col);
      String label = EiDbBundle.message(accessors ? "columnsync.row.dto.add.accessors" : "columnsync.row.dto.add", nc.field(), typeOf(col));
      rows.add(new Row(label, true, true, vf, group, c.getTextOffset(), text, null, 0, null,
          new DtoEdit(owner, stamp, text, accessors, null, 0)));
    }
    int sizes = 0;
    for (PsiField f : c.getFields()) {
      if (f.hasModifierProperty(PsiModifier.STATIC)) continue;
      Meta.Column col = null;
      for (Meta.Column k : cols) if (f.getName().equals(fields.get(k.name()))) col = k;
      if (col == null || col.size() <= 0 || !TypeMap.STRING.equals(TypeMap.forColumn(col.type(), col.size(), col.scale()))) continue;
      for (PsiAnnotation a : f.getAnnotations()) {
        if (a.getQualifiedName() == null || !LENGTH_ANNOTATIONS.contains(a.getQualifiedName())) continue;
        PsiAnnotationMemberValue v = a.findDeclaredAttributeValue(MAX_ATTR);
        if (!(v instanceof PsiLiteralExpression lit) || !(lit.getValue() instanceof Integer max) || max == col.size()) continue;
        String name = a.getNameReferenceElement() == null ? "Size" : a.getNameReferenceElement().getReferenceName();
        rows.add(new Row(EiDbBundle.message("columnsync.row.dto.size", f.getName(), name, max, col.size()), true, true, vf, group,
            v.getTextOffset(), "@" + name + "(max = " + max + ")  →  @" + name + "(max = " + col.size() + ")", null, 0, null,
            new DtoEdit(owner, stamp, null, false, pointers.createSmartPsiElementPointer(v), col.size())));
        sizes++;
      }
    }
    for (String d : dropped) {
      PsiField f = c.findFieldByName(Names.camel(d), false);
      if (f == null) continue;
      int uses = ReferencesSearch.search(f, GlobalSearchScope.projectScope(project)).findAll().size();
      rows.add(new Row(EiDbBundle.message("columnsync.row.dto.dropped", f.getName(), d, uses), false, false, vf, group, f.getTextOffset(),
          f.getText(), null, 0, null, null));
    }
    return sizes;
  }

  /** Lombok 으로 접근자를 만들지 않고, 필드 가운데 하나라도 소스에 getter 가 있으면 true. */
  private static boolean hasAccessors(PsiClass c) {
    for (String lombok : LOMBOK_ACCESSORS) if (c.hasAnnotation(lombok)) return false;
    for (PsiField f : c.getFields()) {
      if (f.hasModifierProperty(PsiModifier.STATIC)) continue;
      PsiMethod getter = PropertyUtilBase.findPropertyGetter(c, f.getName(), false, false);
      if (getter != null && getter.isPhysical()) return true;
    }
    return false;
  }

  private static String typeOf(@Nullable Meta.Column col) {
    return col == null ? TypeMap.STRING : TypeMap.forColumn(col.type(), col.size(), col.scale());
  }

  /** 넣을 필드 글(Javadoc 은 칼럼 주석). 형식은 패키지까지 써 두고 넣은 뒤 줄인다. */
  private static String fieldText(ColumnSync.NewColumn nc, @Nullable Meta.Column col) {
    String simple = typeOf(col);
    String type = TypeMap.IMPORTS.getOrDefault(simple, simple);
    String comment = nc.comment().replace("*/", "* /").replace("\r", " ").replace("\n", " ").trim();
    return (comment.isEmpty() ? "" : "/** " + comment + " */\n") + "private " + type + " " + nc.field() + ";";
  }

  // ---------------------------------------------------------------- 고치기

  private record Replace(int from, int to, String text) {}

  /** 화면 스레드에서 부른다. 모은 뒤 파일이 바뀌었으면 아무것도 고치지 않는다. */
  static void apply(Project project, List<Row> chosen) {
    Map<Document, List<ColumnSync.Change>> byDoc = new LinkedHashMap<>();
    Set<VirtualFile> files = new LinkedHashSet<>();
    List<DtoEdit> dtoEdits = new ArrayList<>();
    for (Row r : chosen) {
      if (r.change() != null && r.doc() != null) {
        if (r.doc().getModificationStamp() != r.stamp()) {
          Notify.warning(project, EiDbBundle.message("columnsync.changed", r.file().getName()));
          return;
        }
        byDoc.computeIfAbsent(r.doc(), k -> new ArrayList<>()).add(r.change());
      } else if (r.dto() != null) {
        PsiClass c = r.dto().owner().getElement();
        PsiFile f = c == null ? null : c.getContainingFile();
        if (f == null || f.getModificationStamp() != r.dto().stamp()) {
          Notify.warning(project, EiDbBundle.message("columnsync.changed", r.file().getName()));
          return;
        }
        dtoEdits.add(r.dto());
      } else {
        continue;
      }
      files.add(r.file());
    }
    if (files.isEmpty()) return;
    if (!FileModificationService.getInstance().prepareVirtualFilesForWrite(project, files)) {
      Notify.error(project, EiDbBundle.message("columnsync.read.only"));
      return;
    }
    // 문서마다 바꿀 범위와 그 자리에 넣을 글. 겹치는 고칠 곳이 있으면 쓰기 전에 멈춘다.
    Map<Document, Replace> replaced = new LinkedHashMap<>();
    int[] done = {0};
    for (Map.Entry<Document, List<ColumnSync.Change>> e : byDoc.entrySet()) {
      List<ColumnSync.Edit> edits = ColumnSync.edits(e.getValue());
      CharSequence before = e.getKey().getImmutableCharSequence();
      String after;
      try {
        after = ColumnSync.apply(before, edits);
      } catch (IllegalArgumentException ex) {
        Notify.error(project, EiDbBundle.message("columnsync.overlap", String.valueOf(FileDocumentManager.getInstance().getFile(e.getKey()))));
        return;
      }
      int from = Integer.MAX_VALUE;
      int to = 0;
      for (ColumnSync.Edit edit : edits) {
        from = Math.min(from, edit.start());
        to = Math.max(to, edit.end());
      }
      // 고친 범위만 바꿔 그 밖의 접힘 · 책갈피를 지킨다.
      replaced.put(e.getKey(), new Replace(from, to, after.substring(from, after.length() - (before.length() - to))));
      done[0] += e.getValue().size();
    }
    WriteCommandAction.writeCommandAction(project).withName(EiDbBundle.message("columnsync.title")).run(() -> {
      PsiDocumentManager psiDocs = PsiDocumentManager.getInstance(project);
      for (Map.Entry<Document, Replace> e : replaced.entrySet()) {
        e.getKey().replaceString(e.getValue().from(), e.getValue().to(), e.getValue().text());
        psiDocs.commitDocument(e.getKey());
      }
      PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
      for (DtoEdit d : dtoEdits) {
        PsiClass c = d.owner().getElement();
        if (c == null) continue;
        if (d.fieldText() != null) {
          PsiField[] own = c.getFields();
          PsiField field = factory.createFieldFromText(d.fieldText(), c);
          PsiField added = (PsiField) (own.length == 0 ? c.add(field) : c.addAfter(field, own[own.length - 1]));
          JavaCodeStyleManager.getInstance(project).shortenClassReferences(added);
          if (d.accessors()) {
            c.add(GenerateMembersUtil.generateSimpleGetterPrototype(added));
            c.add(GenerateMembersUtil.generateSimpleSetterPrototype(added));
          }
          done[0]++;
        } else if (d.sizeValue() != null && d.sizeValue().getElement() != null) {
          PsiAnnotationMemberValue v = d.sizeValue().getElement();
          v.replace(factory.createExpressionFromText(String.valueOf(d.newSize()), v));
          done[0]++;
        }
      }
    });
    Notify.info(project, EiDbBundle.message("columnsync.done", done[0]));
  }
}
