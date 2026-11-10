package ei.db.mybatis;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiDocCommentOwner;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.Processor;
import ei.db.Comments;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.DbSettings;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 편집기 우클릭 「주석 만들기」.
 * 자바: 커서가 있는 메서드(없으면 클래스) 위에 한국어 문서 주석을 만든다. 매퍼 메서드는 그 XML 문장, 컨트롤러 · 서비스 메서드는
 * 부르는 매퍼 문장까지 따라가 표 · 조건 칼럼과 DB 의 한글 주석을 넣는다. 이미 주석이 있으면 바꿀지 묻는다.
 * 매퍼 XML: 아이디 주석이 없는 모든 문장의 SQL 에 「/* UserMapper.selectUserList *&#47;」 를 넣는다.
 * 주석은 문서 글로 넣어(PSI 서식 적용 없음) 사용자의 Javadoc 서식 설정이 줄을 합치거나 &lt;p&gt; 를 넣지 않게 한다.
 */
public final class DocCommentAction extends AnAction {
  private static final String MAPPER_MARK = "<mapper";
  private static final String XML_EXT = "xml";
  /** 서비스 → 서비스 → … 로 따라가는 최대 깊이. */
  private static final int MAX_DEPTH = 3;
  /** 인터페이스 메서드 하나에서 찾는 구현 수 상한. */
  private static final int MAX_IMPLS = 3;
  /** 한 메서드 주석에 넣는 매퍼 문장 수 상한. */
  private static final int MAX_SQLS = 10;
  private static final String REQUEST_MAPPING = "RequestMapping";
  private static final String MAPPING_SUFFIX = "Mapping";
  private static final Pattern HTTP_METHOD = Pattern.compile("\\b(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\b");

  /** 자바 쪽 재료. offset · stamp 는 넣을 자리와 그때의 문서 판(바뀌었으면 넣지 않는다). */
  private record Target(DocComment.Method method, String className, int start, int end,
                        String indent, boolean replace, long stamp, Path dir) {}

  /** DB 에서 읽은 표 · 칼럼 주석과, 읽지 못했으면 그 이유. */
  private record DbComments(Map<String, String> tables, Map<String, String> columns, String problem) {}

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    boolean ok = editor != null && e.getProject() != null && file != null
        && (isMapperXml(file, editor) || ownerAt(file, editor.getCaretModel().getOffset()) != null);
    e.getPresentation().setEnabledAndVisible(ok);
  }

  private static boolean isMapperXml(PsiFile file, Editor editor) {
    VirtualFile vf = file.getVirtualFile();
    return vf != null && XML_EXT.equalsIgnoreCase(vf.getExtension()) && StringUtil.contains(editor.getDocument().getCharsSequence(), MAPPER_MARK);
  }

  /** 커서를 감싼 메서드, 없으면 이름 있는 클래스. 자바 파일이 아니면 null. 읽기 접근 안에서 부른다. */
  private static @Nullable PsiDocCommentOwner ownerAt(PsiFile file, int offset) {
    if (!(file instanceof PsiJavaFile)) return null;
    PsiElement at = file.findElementAt(offset);
    PsiMethod m = PsiTreeUtil.getParentOfType(at, PsiMethod.class, false);
    if (m != null) return m;
    PsiClass c = PsiTreeUtil.getParentOfType(at, PsiClass.class, false);
    while (c instanceof PsiAnonymousClass) c = PsiTreeUtil.getParentOfType(c, PsiClass.class, true);
    return c != null && c.getName() != null ? c : null;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    if (project == null || editor == null || file == null) return;
    if (isMapperXml(file, editor)) {
      sqlIds(project, editor.getDocument(), file);
      return;
    }
    if (DumbService.isDumb(project)) {
      Notify.warning(project, EiDbBundle.message("doccomment.dumb"));
      return;
    }
    DocComment.Dict dict = DocCommentSettings.dict(project);
    if (dict == null) return;
    PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
    int offset = editor.getCaretModel().getOffset();
    Object[] found = new Object[3];
    try {
      ProgressManager.getInstance().runProcessWithProgressSynchronously((ThrowableComputable<Void, RuntimeException>) () -> {
        Target t = ReadAction.compute(() -> target(project, file, editor.getDocument(), offset));
        found[0] = t;
        if (t != null) {
          found[1] = dbComments(project, t.method());
          found[2] = DocCommentSettings.author(t.dir());
        }
        return null;
      }, EiDbBundle.message("doccomment.progress"), true, project);
    } catch (ProcessCanceledException ex) {
      return; // 사용자가 진행 창에서 취소한 경우다.
    } catch (IndexNotReadyException ex) {
      Notify.warning(project, EiDbBundle.message("doccomment.dumb"));
      return;
    }
    Target t = (Target) found[0];
    if (t == null) {
      Notify.warning(project, EiDbBundle.message("doccomment.no.target"));
      return;
    }
    if (t.replace() && Messages.showYesNoDialog(project, EiDbBundle.message("doccomment.replace"), EiDbBundle.message("doccomment.title"),
        EiDbBundle.message("doccomment.replace.ok"), EiDbBundle.message("common.cancel"), Messages.getQuestionIcon()) != Messages.YES) {
      return;
    }
    DbComments db = (DbComments) found[1];
    DocComment.Tail tail = new DocComment.Tail((String) found[2], DocCommentSettings.withDate() ? LocalDate.now().toString() : null);
    String text = t.method() == null
        ? DocComment.forClass(t.className(), dict, tail)
        : DocComment.forMethod(t.method(), dict, db == null ? Map.of() : db.tables(), db == null ? Map.of() : db.columns(), tail);
    Document document = editor.getDocument();
    if (document.getModificationStamp() != t.stamp()) {
      Notify.warning(project, EiDbBundle.message("doccomment.changed"));
      return;
    }
    String placed = text.replace("\n", "\n" + t.indent()) + (t.replace() ? "" : "\n" + t.indent());
    WriteCommandAction.writeCommandAction(project, file).withName(EiDbBundle.message("doccomment.title"))
        .run(() -> document.replaceString(t.start(), t.end(), placed));
    if (db != null && db.problem() != null) Notify.info(project, db.problem());
  }

  // ---------------------------------------------------------------- 매퍼 XML

  private static void sqlIds(Project project, Document document, PsiFile file) {
    CharSequence text = document.getImmutableCharSequence();
    MapperDoc doc = MapperDoc.parse(text);
    if (doc.statements.isEmpty()) {
      Notify.warning(project, EiDbBundle.message("doccomment.sqlid.no.statement"));
      return;
    }
    VirtualFile vf = file.getVirtualFile();
    List<SqlIdComment.Insert> plan = SqlIdComment.plan(text, doc, vf == null ? file.getName() : vf.getNameWithoutExtension());
    if (plan.isEmpty()) {
      Notify.info(project, EiDbBundle.message("doccomment.sqlid.none"));
      return;
    }
    WriteCommandAction.writeCommandAction(project, file).withName(EiDbBundle.message("doccomment.sqlid.title")).run(() -> {
      for (int i = plan.size() - 1; i >= 0; i--) document.insertString(plan.get(i).offset(), plan.get(i).text());
    });
    Notify.info(project, EiDbBundle.message("doccomment.sqlid.done", plan.size()));
  }

  // ---------------------------------------------------------------- 자바 재료

  /** 읽기 접근 안에서 부른다. 대상이 없으면 null. */
  private static @Nullable Target target(Project project, PsiFile file, Document document, int offset) {
    PsiDocCommentOwner owner = ownerAt(file, offset);
    if (owner == null) return null;
    PsiDocComment old = owner.getDocComment();
    int start = old != null ? old.getTextRange().getStartOffset() : owner.getTextRange().getStartOffset();
    int end = old != null ? old.getTextRange().getEndOffset() : start;
    int lineStart = document.getLineStartOffset(document.getLineNumber(start));
    String before = document.getCharsSequence().subSequence(lineStart, start).toString();
    String indent = before.isBlank() ? before : "";
    VirtualFile vf = file.getVirtualFile();
    Path dir = null;
    if (vf != null && vf.getParent() != null && vf.isInLocalFileSystem()) {
      try {
        dir = Path.of(vf.getParent().getPath());
      } catch (InvalidPathException ignored) {
        // 경로로 바꿀 수 없는 위치면 Git 이름 없이 만든다.
      }
    }
    DocComment.Method method = owner instanceof PsiMethod m ? method(project, m) : null;
    return new Target(method, owner.getName(), start, end, indent, old != null, document.getModificationStamp(), dir);
  }

  private static DocComment.Method method(Project project, PsiMethod m) {
    List<DocComment.Param> params = new ArrayList<>();
    for (PsiParameter p : m.getParameterList().getParameters()) params.add(new DocComment.Param(p.getName(), p.getType().getPresentableText()));
    PsiType rt = m.isConstructor() ? null : m.getReturnType();
    List<String> throwsTypes = new ArrayList<>();
    for (PsiClassType t : m.getThrowsList().getReferencedTypes()) throwsTypes.add(t.getPresentableText());
    return new DocComment.Method(m.getName(), params, rt == null ? null : rt.getPresentableText(), throwsTypes, url(m), sqls(project, m));
  }

  /** 매핑 어노테이션이 있으면 「GET /user/list」 꼴(메서드를 모르면 주소만). 없으면 null. */
  private static @Nullable String url(PsiMethod m) {
    PsiAnnotation mapping = mappingAnnotation(m.getAnnotations());
    if (mapping == null) return null;
    PsiClass c = m.getContainingClass();
    PsiAnnotation classMapping = c == null ? null : mappingAnnotation(c.getAnnotations());
    String path = join(classMapping == null ? "" : path(classMapping), path(mapping));
    String name = shortName(mapping);
    String verb = null;
    if (REQUEST_MAPPING.equals(name)) {
      PsiAnnotationMemberValue v = mapping.findDeclaredAttributeValue("method");
      Matcher hm = v == null ? null : HTTP_METHOD.matcher(v.getText());
      if (hm != null && hm.find()) verb = hm.group(1);
    } else if (name != null) {
      verb = name.substring(0, name.length() - MAPPING_SUFFIX.length()).toUpperCase(Locale.ROOT);
    }
    return verb == null ? path : verb + " " + path;
  }

  private static @Nullable PsiAnnotation mappingAnnotation(PsiAnnotation[] annotations) {
    for (PsiAnnotation a : annotations) {
      if (LayerRules.MAPPING_ANNOTATIONS.contains(shortName(a))) return a;
    }
    return null;
  }

  private static @Nullable String shortName(PsiAnnotation a) {
    PsiJavaCodeReferenceElement ref = a.getNameReferenceElement();
    return ref == null ? null : ref.getReferenceName();
  }

  /** value 또는 path 의 첫 값. 글자 상수가 아니면 쓴 그대로. 없으면 빈 글. */
  private static String path(PsiAnnotation a) {
    PsiAnnotationMemberValue v = a.findDeclaredAttributeValue("value");
    if (v == null) v = a.findDeclaredAttributeValue("path");
    if (v instanceof PsiArrayInitializerMemberValue arr) {
      PsiAnnotationMemberValue[] items = arr.getInitializers();
      v = items.length > 0 ? items[0] : null;
    }
    if (v == null) return "";
    if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) return s;
    return v.getText();
  }

  /** 클래스 주소와 메서드 주소를 「/」 하나로 잇는다. 둘 다 비면 「/」. */
  private static String join(String a, String b) {
    String s = ("/" + a + "/" + b).replaceAll("/+", "/");
    return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  /** 매퍼 메서드면 그 문장, 아니면 본문의 「필드.메서드()」 호출을 따라가 닿은 매퍼 문장들. */
  private static List<DocComment.Sql> sqls(Project project, PsiMethod m) {
    List<DocComment.Sql> out = new ArrayList<>();
    PsiClass c = m.getContainingClass();
    if (c == null) return out;
    Map<VirtualFile, MapperDoc> docs = new HashMap<>();
    if (c.isInterface()) {
      List<MapperIndex.Entry> xmls = MapperIndex.find(project, c.getQualifiedName());
      if (!xmls.isEmpty()) {
        addStatements(xmls, m.getName(), out, docs);
        return out;
      }
    }
    Set<PsiMethod> seen = new HashSet<>();
    seen.add(m);
    walk(project, m, 0, out, seen, docs);
    return out;
  }

  private static void walk(Project project, PsiMethod method, int depth, List<DocComment.Sql> out, Set<PsiMethod> seen, Map<VirtualFile, MapperDoc> docs) {
    if (depth >= MAX_DEPTH || method.getBody() == null) return;
    GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
    for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiMethodCallExpression.class)) {
      ProgressManager.checkCanceled();
      if (out.size() >= MAX_SQLS) return;
      PsiClass type = fieldClass(call);
      String name = call.getMethodExpression().getReferenceName();
      if (type == null || name == null) continue;
      List<MapperIndex.Entry> xmls = type.isInterface() ? MapperIndex.find(project, type.getQualifiedName()) : List.of();
      if (!xmls.isEmpty()) {
        addStatements(xmls, name, out, docs);
        continue;
      }
      VirtualFile vf = PsiUtilCore.getVirtualFile(type);
      if (vf == null || !scope.contains(vf) || !LayerRules.isBean(type.isInterface(), LayerNav.annotationNames(type))) continue;
      PsiMethod called = call.resolveMethod();
      if (called == null) continue;
      for (PsiMethod impl : implementations(called, scope)) {
        if (seen.add(impl)) walk(project, impl, depth + 1, out, seen, docs);
      }
    }
  }

  /** 호출 대상이 필드면 그 필드 형식의 클래스. 아니면 null. */
  private static @Nullable PsiClass fieldClass(PsiMethodCallExpression call) {
    PsiExpression q = call.getMethodExpression().getQualifierExpression();
    if (!(q instanceof PsiReferenceExpression r) || !(r.resolve() instanceof PsiField f)) return null;
    return f.getType() instanceof PsiClassType t ? t.resolve() : null;
  }

  private static List<PsiMethod> implementations(PsiMethod called, GlobalSearchScope scope) {
    if (called.getBody() != null) return List.of(called);
    List<PsiMethod> found = new ArrayList<>();
    OverridingMethodsSearch.search(called, scope, true).forEach((Processor<PsiMethod>) impl -> {
      ProgressManager.checkCanceled();
      if (impl.getBody() != null) found.add(impl);
      return found.size() < MAX_IMPLS;
    });
    return found;
  }

  /** 매퍼 XML 들에서 id 문장의 SQL 글. 같은 이름은 한 번만. */
  private static void addStatements(List<MapperIndex.Entry> xmls, String id, List<DocComment.Sql> out, Map<VirtualFile, MapperDoc> docs) {
    for (MapperIndex.Entry e : xmls) {
      if (!e.ids().containsKey(id)) continue;
      MapperDoc doc = docs.computeIfAbsent(e.file(), DocCommentAction::parse);
      MapperDoc.Node s = doc == null ? null : doc.statement(id);
      if (s == null) continue;
      String label = SqlIdComment.label(doc, e.file().getNameWithoutExtension(), id);
      boolean dup = false;
      for (DocComment.Sql q : out) dup |= q.label().equals(label);
      if (!dup) out.add(new DocComment.Sql(label, doc.plainText(s)));
    }
  }

  private static @Nullable MapperDoc parse(VirtualFile f) {
    Document d = FileDocumentManager.getInstance().getDocument(f);
    return d == null ? null : MapperDoc.parse(d.getImmutableCharSequence());
  }

  // ---------------------------------------------------------------- DB 주석

  /**
   * 매퍼 쿼리 실행에 마지막으로 쓴 접속(없으면 접속이 하나뿐일 때 그것)에서 표 · 칼럼 주석을 읽는다.
   * SQL 이 없으면 null. 접속을 못 고르거나 읽지 못하면 빈 맵과 이유.
   */
  private static @Nullable DbComments dbComments(Project project, DocComment.Method m) {
    if (m == null || m.sqls().isEmpty()) return null;
    Set<String> tables = new LinkedHashSet<>();
    for (DocComment.Sql s : m.sqls()) tables.addAll(DocComment.facts(s.text()).tables());
    if (tables.isEmpty()) return null;
    DbSettings settings = DbSettings.getInstance();
    DbConfig c = settings.find(PropertiesComponent.getInstance(project).getValue(MyBatisRunDialog.CONNECTION_KEY, ""));
    if (c == null && settings.connections().size() == 1) c = settings.connections().get(0);
    if (c == null) return new DbComments(Map.of(), Map.of(), EiDbBundle.message("doccomment.db.no.connection"));
    Map<String, String> tableComments = new LinkedHashMap<>();
    Map<String, String> columnComments = new LinkedHashMap<>();
    try {
      Connection conn = DbSessions.get(project).connection(c);
      String schema = Meta.schemaOf(c);
      for (String t : tables) {
        ProgressManager.checkCanceled();
        String name = c.kind == DbKind.ORACLE ? t : t.toLowerCase(Locale.ROOT);
        String tc = tableComment(conn, c, schema, name);
        if (tc != null) tableComments.put(t, tc);
        for (Map.Entry<String, String> col : Comments.ofTable(conn, c, schema, name).entrySet()) columnComments.putIfAbsent(col.getKey(), col.getValue());
      }
    } catch (SQLException ex) {
      return new DbComments(tableComments, columnComments, EiDbBundle.message("doccomment.db.failed", c.name, ex.getMessage()));
    }
    return new DbComments(tableComments, columnComments, null);
  }

  /** 표 하나의 주석(REMARKS). 없으면 null. MySQL 은 데이터베이스가 catalog 자리다(Meta 와 같다). */
  private static @Nullable String tableComment(Connection conn, DbConfig c, String schema, String table) throws SQLException {
    DatabaseMetaData md = conn.getMetaData();
    boolean mysql = c.kind == DbKind.MYSQL;
    try (ResultSet rs = md.getTables(mysql ? schema : null, mysql ? null : schema, table, null)) {
      while (rs.next()) {
        String r = rs.getString("REMARKS");
        if (r != null && !r.isBlank()) return r.trim();
      }
    }
    return null;
  }
}
