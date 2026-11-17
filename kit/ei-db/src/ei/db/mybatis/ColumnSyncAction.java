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
import com.intellij.psi.PsiParameter;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.psi.util.PsiUtil;
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

/**
 * 「DB 바뀐 것 코드에 맞추기…」: 테이블의 지금 칼럼(DB 에서 새로 읽음)과 그 테이블을 쓰는 매퍼 문장 · resultMap · DTO 를 견줘
 * 새 칼럼 넣기 · 없어진 칼럼 지우기 · DTO @Size/@Length 길이 맞추기를 미리보기로 보여 주고 고른 것만 한 명령(되돌리기 한 번)으로 고친다.
 * 매퍼 쪽 판단은 {@link ColumnSync}. 확실히 고칠 수 없는 곳은 「직접 고치세요」 항목(이동만)으로 보인다.
 * 여는 곳: 매퍼 XML 에디터 우클릭(커서가 테이블 이름 위일 때), DB 탐색기 테이블 우클릭, DTO 필드 전구(ColumnSyncIntention, 칼럼 하나).
 */
public final class ColumnSyncAction extends AnAction {

  private static final Set<String> LOMBOK_ACCESSORS = Set.of("lombok.Data", "lombok.Getter", "lombok.Setter", "lombok.Value");
  private static final Set<String> LENGTH_ANNOTATIONS = Set.of("jakarta.validation.constraints.Size", "javax.validation.constraints.Size",
      "org.hibernate.validator.constraints.Length");
  private static final String MAX_ATTR = "max";
  private static final String RESULT_MAP_TAG = "resultMap";
  private static final String PARAMETER_TYPE_ATTR = "parameterType";
  private static final String RESULT_TYPE_ATTR = "resultType";
  private static final String TYPE_ATTR = "type";
  private static final String COLUMN_ATTR = "column";
  private static final String ID_ATTR = "id";
  private static final String JAVA_PACKAGE = "java.";
  /** 길이 주석 이름을 읽지 못할 때 미리보기에 쓰는 이름. */
  private static final String SIZE_NAME = "Size";
  /** 길이 맞추기를 내는 DB 형식: 이름에 CHAR 가 든 것(CHAR · VARCHAR · VARCHAR2 · NCHAR …). CLOB 은 길이가 아니라 뺀다. */
  private static final String CHAR_TYPE = "CHAR";
  private static final String CLOB_TYPE = "CLOB";
  /** 미리보기에서 고친 곳 앞뒤로 함께 보이는 줄 수. */
  private static final int PREVIEW_CONTEXT_LINES = 2;
  /**
   * jdbcType: TypeMap 의 자바 형식 → MyBatis JdbcType 이름. DB 형식이 CLOB 이면 CLOB_TYPE.
   * 없는 형식은 모르는 것으로 두어, 본보기 줄에 jdbcType 이 있으면 복제하지 않고 「직접 넣으세요」 가 된다.
   */
  private static final Map<String, String> JDBC_TYPES = Map.of(
      TypeMap.STRING, "VARCHAR", TypeMap.LONG, "NUMERIC", TypeMap.INTEGER, "NUMERIC", TypeMap.DECIMAL, "NUMERIC",
      TypeMap.DATE_TIME, "TIMESTAMP", TypeMap.TIME, "TIME", TypeMap.OFFSET_DATE_TIME, "TIMESTAMP_WITH_TIMEZONE", TypeMap.BYTES, "BLOB");

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
          // SQL 에 적힌 이름으로 없으면 DB 가 담는 꼴(Oracle 대문자 · PostgreSQL 소문자)로 스키마 · 테이블을 함께 바꿔 한 번 더 읽는다.
          String storedSchema = schema == null || schema.isBlank() ? sch : Meta.storedName(c, sch);
          String stored = Meta.storedName(c, table);
          if (cols.isEmpty() && (!stored.equals(table) || !storedSchema.equals(sch))) {
            cols = SchemaCache.reloadColumns(conn, c, storedSchema, stored);
          }
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
          // 모달이 아니다: 확인하면 창이 apply 를 부른다.
          new ColumnSyncDialog(project, plan).show();
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

  /**
   * 읽기 접근 · 색인 끝난 상태에서 부른다.
   * 「새 칼럼」 은 이 테이블을 쓰는 어떤 문장(테이블에 붙은 칼럼)도, 이 테이블 resultMap 의 column 도 쓰지 않는 DB 칼럼이다(DTO 필드는 보지 않는다).
   * 목록마다 그 목록이 갖지 않은 칼럼은 ColumnSync 가 판단하고, 넣기의 기본 체크는 값을 나르는 클래스가 그 필드를 가지거나 이번에 얻을 때만 남긴다.
   */
  static Plan collect(Project project, String schema, String table, List<Meta.Column> cols, @Nullable String onlyColumn) {
    Set<String> db = new LinkedHashSet<>();
    List<String> names = new ArrayList<>();
    for (Meta.Column col : cols) {
      db.add(col.name().toUpperCase(Locale.ROOT));
      names.add(col.name());
    }
    Map<String, String> fields = Names.uniqueCamel(names);
    List<Mapper> mappers = new ArrayList<>();
    Set<String> usedInCode = new LinkedHashSet<>();
    Set<String> inMaps = new HashSet<>();
    Map<String, VirtualFile> dtoTypes = new LinkedHashMap<>();
    for (MapperIndex.Entry entry : sourceEntries(project)) {
      ProgressManager.checkCanceled();
      Mapper m = mapper(entry.file(), schema, table);
      if (m == null) continue;
      mappers.add(m);
      for (MapperDoc.Node st : m.statements()) {
        List<int[]> ranges = CodeFlow.ranges(m.parsed(), st);
        for (MapperSqlScan.Column col : m.scan().columns()) {
          if (inside(ranges, col.start()) && MapperSqlScan.bare(col.table().name()).equalsIgnoreCase(table)) {
            usedInCode.add(MapperSqlScan.bare(col.name()).toUpperCase(Locale.ROOT));
          }
        }
        addType(dtoTypes, st.attr(PARAMETER_TYPE_ATTR), m.file());
        addType(dtoTypes, st.attr(RESULT_TYPE_ATTR), m.file());
      }
      for (MapperDoc.Node rm : m.resultMaps()) {
        addType(dtoTypes, rm.attr(TYPE_ATTR), m.file());
        for (MapperDoc.Node c : rm.children) {
          if (c.name != null && c.attr(COLUMN_ATTR) != null) inMaps.add(c.attr(COLUMN_ATTR).trim().toUpperCase(Locale.ROOT));
        }
      }
    }
    Map<String, PsiClass> dtos = new LinkedHashMap<>();
    for (Map.Entry<String, VirtualFile> t : dtoTypes.entrySet()) addDto(dtos, DtoFields.find(project, t.getKey(), t.getValue()));

    // candidates 는 목록마다 견줄 칼럼(칼럼 하나만이면 그것), fresh · adds 는 새 칼럼.
    List<ColumnSync.NewColumn> candidates = new ArrayList<>();
    List<ColumnSync.NewColumn> adds = new ArrayList<>();
    Set<String> fresh = new LinkedHashSet<>();
    for (Meta.Column col : cols) {
      String up = col.name().toUpperCase(Locale.ROOT);
      boolean isFresh = onlyColumn == null ? !usedInCode.contains(up) && !inMaps.contains(up) : col.name().equalsIgnoreCase(onlyColumn);
      if (onlyColumn != null && !isFresh) continue;
      ColumnSync.NewColumn nc = newColumn(col, fields.get(col.name()));
      candidates.add(nc);
      if (isFresh) {
        fresh.add(up);
        adds.add(nc);
      }
    }
    Set<String> dropped = new LinkedHashSet<>();
    if (onlyColumn == null) {
      for (String used : usedInCode) if (!db.contains(used)) dropped.add(used);
    }

    // needed 는 이번에 필드를 넣을 DTO(기본 체크), checkedMaps 는 체크된 SELECT 의 결과를 받는 resultMap id.
    Set<String> needed = new HashSet<>();
    Set<String> checkedMaps = new HashSet<>();
    List<Row> rows = new ArrayList<>();
    for (Mapper m : mappers) {
      for (MapperDoc.Node st : m.statements()) {
        PsiClass carrier = carrier(project, m, st);
        addDto(dtos, carrier);
        for (ColumnSync.Change c : ColumnSync.forStatement(m.text(), m.parsed(), st, m.scan(), table, db, candidates, fresh, dropped)) {
          boolean checked = c.checked();
          if (checked && c.kind() == ColumnSync.Kind.ADD && c.part() == ColumnSync.Part.SELECT) {
            resultTargets(project, m, st, needed, checkedMaps);
          } else if (checked && c.kind() == ColumnSync.Kind.ADD) {
            checked = carries(carrier, fields.get(c.column()), fresh.contains(c.column().toUpperCase(Locale.ROOT)), needed);
          }
          rows.add(mapperRow(m, c, checked));
        }
      }
      for (MapperDoc.Node f : m.fragments()) {
        for (ColumnSync.Change c : ColumnSync.forFragment(m.text(), m.parsed(), f, m.scan(), table, db, candidates, fresh, dropped)) {
          boolean checked = c.checked();
          // 조각의 SELECT 목록은 그 조각을 부르는 문장들의 결과로 간다. INSERT · UPDATE 넣기는 나르는 클래스를 가릴 수 없어 끈다.
          if (checked && c.kind() == ColumnSync.Kind.ADD && c.part() == ColumnSync.Part.SELECT) {
            for (MapperDoc.Node st : m.statements()) {
              if (inside(CodeFlow.ranges(m.parsed(), st), f.start)) resultTargets(project, m, st, needed, checkedMaps);
            }
          } else if (c.kind() == ColumnSync.Kind.ADD) {
            checked = false;
          }
          rows.add(mapperRow(m, c, checked));
        }
      }
      // resultMap 은 문장을 다 본 뒤에: 체크된 SELECT 가 받는 것만, 형식 클래스가 그 필드를 가지거나 얻을 때만 체크한다.
      for (MapperDoc.Node rm : m.resultMaps()) {
        String type = rm.attr(TYPE_ATTR);
        PsiClass owner = type == null ? null : usable(DtoFields.find(project, type, m.file()));
        boolean wanted = checkedMaps.contains(rm.attr(ID_ATTR));
        for (ColumnSync.Change c : ColumnSync.forResultMap(m.text(), rm, db, adds, dropped)) {
          boolean checked = c.checked();
          if (checked && c.kind() == ColumnSync.Kind.ADD) checked = wanted && carries(owner, fields.get(c.column()), true, needed);
          rows.add(mapperRow(m, c, checked));
        }
      }
    }
    int sizes = 0;
    if (onlyColumn == null) {
      for (PsiClass c : dtos.values()) sizes += dtoRows(project, c, cols, fields, adds, dropped, needed, rows);
    }
    String summary = EiDbBundle.message("columnsync.summary", table, adds.size(), dropped.size(), sizes);
    return new Plan(table, summary, rows);
  }

  /** 쓸 수 있는 DTO(Map 이 아닌 프로젝트 클래스)면 넣는다. */
  private static void addDto(Map<String, PsiClass> dtos, @Nullable PsiClass c) {
    PsiClass u = usable(c);
    if (u != null) dtos.putIfAbsent(u.getQualifiedName(), u);
  }

  private static @Nullable PsiClass usable(@Nullable PsiClass c) {
    return c == null || DtoFields.isMap(c) || c.getQualifiedName() == null || c.getQualifiedName().startsWith(JAVA_PACKAGE) ? null : c;
  }

  /**
   * 문장 값을 나르는 클래스: parameterType, 없으면 namespace 인터페이스의 같은 이름 메서드(하나뿐이고 파라미터가 하나일 때)의 파라미터 형식.
   * Map · 자바 기본 클래스이거나 가릴 수 없으면 null.
   */
  private static @Nullable PsiClass carrier(Project project, Mapper m, MapperDoc.Node st) {
    String pt = st.attr(PARAMETER_TYPE_ATTR);
    if (pt != null && !pt.isBlank()) return usable(DtoFields.find(project, pt, m.file()));
    String id = st.attr(ID_ATTR);
    PsiClass ns = m.parsed().namespace == null || id == null ? null
        : JavaPsiFacade.getInstance(project).findClass(m.parsed().namespace, GlobalSearchScope.allScope(project));
    if (ns == null) return null;
    PsiMethod[] methods = ns.findMethodsByName(id, true);
    if (methods.length != 1 || methods[0].getParameterList().getParametersCount() != 1) return null;
    PsiParameter p = methods[0].getParameterList().getParameters()[0];
    return usable(PsiUtil.resolveClassInType(p.getType()));
  }

  /**
   * 클래스 c 가 field 를 가지거나(getter 포함), 새 칼럼이고 c 가 이번에 필드를 얻을 수 있으면 true.
   * 얻을 수 있어 true 면 c 를 needed 에 넣는다(그 DTO 의 필드 넣기 줄이 기본 체크로 나온다).
   */
  private static boolean carries(@Nullable PsiClass c, @Nullable String field, boolean isFresh, Set<String> needed) {
    if (c == null || field == null) return false;
    if (DtoFields.properties(c).contains(field)) return true;
    if (!isFresh || !canGain(c)) return false;
    needed.add(c.getQualifiedName());
    return true;
  }

  /** 필드를 넣을 수 있는 클래스: 소스에 있고 쓸 수 있으며 record · 인터페이스 · enum · 주석 형식이 아닌 것. */
  private static boolean canGain(PsiClass c) {
    return !(c instanceof PsiCompiledElement) && c.isWritable() && !c.isRecord() && !c.isInterface() && !c.isEnum() && !c.isAnnotationType();
  }

  /** SELECT 문장의 결과를 받는 곳: resultType 클래스는 needed 로(새 칼럼 필드를 받게), resultMap id 는 checkedMaps 로. */
  private static void resultTargets(Project project, Mapper m, MapperDoc.Node st, Set<String> needed, Set<String> checkedMaps) {
    String type = st.attr(RESULT_TYPE_ATTR);
    PsiClass rt = type == null ? null : usable(DtoFields.find(project, type, m.file()));
    if (rt != null && canGain(rt)) needed.add(rt.getQualifiedName());
    String rm = st.attr(RESULT_MAP_TAG);
    if (rm == null) return;
    for (String id : rm.split(",")) checkedMaps.add(localId(m.parsed(), id));
  }

  private static String localId(MapperDoc doc, String id) {
    String local = id.trim();
    return doc.namespace != null && local.startsWith(doc.namespace + ".") ? local.substring(doc.namespace.length() + 1) : local;
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
        for (String id : rm.split(",")) mapIds.add(localId(doc, id));
      }
    }
    if (statements.isEmpty()) return null;
    Set<MapperDoc.Node> maps = new LinkedHashSet<>();
    for (MapperDoc.Node root : doc.root.children) {
      if (!"mapper".equals(root.name)) continue;
      for (MapperDoc.Node c : root.children) {
        if (RESULT_MAP_TAG.equals(c.name) && mapIds.contains(c.attr(ID_ATTR))) maps.add(c);
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
    boolean clob = col.type() != null && col.type().toUpperCase(Locale.ROOT).contains(CLOB_TYPE);
    return new ColumnSync.NewColumn(col.name(), field, col.comment() == null ? "" : col.comment(), clob ? CLOB_TYPE : JDBC_TYPES.get(java));
  }

  private static Row mapperRow(Mapper m, ColumnSync.Change c, boolean checked) {
    String part = EiDbBundle.message("columnsync.part." + c.part().name().toLowerCase(Locale.ROOT));
    String label = EiDbBundle.message("columnsync.row." + c.kind().name().toLowerCase(Locale.ROOT), c.column(), c.owner(), part);
    boolean checkable = !c.edits().isEmpty();
    return new Row(label, checkable, checkable && checked, m.file(), m.file().getName(), c.at(), preview(m.text(), c), m.doc(),
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

  /**
   * DTO 하나의 필드 넣기 · 길이 맞추기 · 없어진 칼럼 필드 알림 줄. 길이 줄 수를 돌려준다.
   * 필드 넣기는 새 칼럼만, 기본 체크는 매퍼 쪽 체크된 고칠 것이 그 필드를 쓰는 클래스(needed)만. record 는 필드를 넣지 않는다.
   * 길이 맞추기는 문자 칼럼만, 체크 끈 채로 낸다(길이를 일부러 다르게 둔 곳이 있어서).
   */
  private static int dtoRows(Project project, PsiClass c, List<Meta.Column> cols, Map<String, String> fields,
                             List<ColumnSync.NewColumn> adds, Set<String> dropped, Set<String> needed, List<Row> rows) {
    PsiFile file = c.getContainingFile();
    VirtualFile vf = file == null ? null : file.getVirtualFile();
    if (vf == null || c instanceof PsiCompiledElement || !c.isWritable() || c.isInterface() || c.isEnum() || c.isAnnotationType()) return 0;
    SmartPointerManager pointers = SmartPointerManager.getInstance(project);
    SmartPsiElementPointer<PsiClass> owner = pointers.createSmartPsiElementPointer(c);
    long stamp = file.getModificationStamp();
    String group = c.getName() + ".java";
    boolean accessors = hasAccessors(c);
    boolean checked = needed.contains(c.getQualifiedName());
    for (ColumnSync.NewColumn nc : c.isRecord() ? List.<ColumnSync.NewColumn>of() : adds) {
      if (c.findFieldByName(nc.field(), true) != null) continue;
      Meta.Column col = find(cols, nc.name());
      String text = fieldText(nc, col);
      String label = EiDbBundle.message(accessors ? "columnsync.row.dto.add.accessors" : "columnsync.row.dto.add", nc.field(), typeOf(col));
      rows.add(new Row(label, true, checked, vf, group, c.getTextOffset(), text, null, 0, null,
          new DtoEdit(owner, stamp, text, accessors, null, 0)));
    }
    int sizes = 0;
    for (PsiField f : c.getFields()) {
      if (f.hasModifierProperty(PsiModifier.STATIC)) continue;
      Meta.Column col = null;
      for (Meta.Column k : cols) if (f.getName().equals(fields.get(k.name()))) col = k;
      if (col == null || col.size() <= 0 || !isChar(col)) continue;
      for (PsiAnnotation a : f.getAnnotations()) {
        if (a.getQualifiedName() == null || !LENGTH_ANNOTATIONS.contains(a.getQualifiedName())) continue;
        PsiAnnotationMemberValue v = a.findDeclaredAttributeValue(MAX_ATTR);
        if (!(v instanceof PsiLiteralExpression lit) || !(lit.getValue() instanceof Integer max) || max == col.size()) continue;
        String name = a.getNameReferenceElement() == null ? SIZE_NAME : a.getNameReferenceElement().getReferenceName();
        rows.add(new Row(EiDbBundle.message("columnsync.row.dto.size", f.getName(), name, max, col.size()), true, false, vf, group,
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

  private static boolean isChar(Meta.Column col) {
    String t = col.type() == null ? "" : col.type().toUpperCase(Locale.ROOT);
    return t.contains(CHAR_TYPE) && !t.contains(CLOB_TYPE);
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
