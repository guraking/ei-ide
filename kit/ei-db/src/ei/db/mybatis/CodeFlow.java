package ei.db.mybatis;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiType;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.AllClassesSearch;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.search.searches.OverridingMethodsSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.Processor;
import com.intellij.util.Query;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.javadoc.PsiDocComment;
import ei.db.EiDbBundle;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 코드 흐름 창의 찾기: 호출 흐름(URL → 컨트롤러 → 서비스 → 매퍼 → XML → 표), 부르는 곳 거꾸로 따라가기(영향),
 * 표 · 칼럼을 쓰는 매퍼 문장, 안 쓰는 API · 매퍼 문장 · DTO.
 * 자바는 PSI 해석, 매퍼 XML 은 MapperIndex · MapperDoc · MapperSqlScan, 화면(vue · js 등)은 글자 찾기(FlowRules.frontPattern)다.
 * 모든 찾기는 {@link #read} 안(읽기 접근 · 색인 끝남)에서 돌고 화면과 상관없는 {@link Item} 나무를 돌려준다.
 * 쓰기 동작이 끼면 nonBlocking 읽기가 처음부터 다시 돌므로 찾기 함수는 바깥 상태를 바꾸지 않는다.
 */
final class CodeFlow {
  private CodeFlow() {}

  /** @RestController 는 @Controller 를 달고 있지만 어노테이션 찾기는 메타 어노테이션을 따라가지 않아 둘 다 찾는다. */
  private static final List<String> CONTROLLER_FQNS = List.of(
      "org.springframework.stereotype.Controller", "org.springframework.web.bind.annotation.RestController");
  private static final Set<String> MAPPER_ANNOTATIONS = Set.of("Mapper");
  private static final String REQUEST_MAPPING = "RequestMapping";
  private static final String ATTR_VALUE = "value";
  private static final String ATTR_PATH = "path";
  private static final String ATTR_METHOD = "method";
  private static final String MAPPER_TAG = "mapper";
  private static final String RESULT_MAP_TAG = "resultMap";
  private static final String INCLUDE_TAG = "include";
  private static final String ATTR_ID = "id";
  private static final String ATTR_TYPE = "type";
  /** association · collection 이 부르는 다른 문장. */
  private static final String ATTR_SELECT = "select";
  private static final String ATTR_REFID = "refid";
  /** 매퍼 문장 · resultMap 에서 형식을 적는 속성. */
  private static final List<String> TYPE_ATTRS = List.of("parameterType", "resultType", "type", "ofType", "javaType");
  private static final String MINIFIED_SUFFIX = ".min.js";
  /** 화면 글로 보는 파일. */
  private static final List<String> FRONT_EXTENSIONS = List.of("vue", "js", "ts", "jsx", "tsx", "html", "jsp");

  /** 나무 마디 수 상한. 넘으면 그 뒤는 모으지 않고 뿌리 설명에 적는다. */
  private static final int MAX_NODES = 3000;
  /** 호출 흐름에서 서비스 → 서비스 → … 로 내려가는 깊이. */
  private static final int MAX_DOWN_DEPTH = 8;
  /** 부르는 곳을 거꾸로 올라가는 깊이. */
  private static final int MAX_UP_DEPTH = 8;
  /** 인터페이스 메서드 하나에서 찾는 구현 수. */
  private static final int MAX_IMPLS = 5;
  /** 「위에 나옴」 줄 옆에 적는 URL 수. 넘으면 「외 N개」 로 줄인다. */
  private static final int MAX_SEEN_LABELS = 3;
  /** 메서드 · 필드 · 클래스 하나에서 모으는 참조 수. */
  private static final int MAX_REFS = 300;
  /** 화면 파일 하나의 크기 상한(묶어 만든 큰 파일은 화면 소스가 아니다). */
  private static final long MAX_FRONT_BYTES = 1024 * 1024;
  /** URL 하나에서 보여 줄 화면 위치 수. */
  private static final int MAX_FRONT_HITS = 20;

  /** 나무 마디 하나. nav 는 두 번 누르면 갈 곳(없으면 null). note 는 이름 옆 회색 글. */
  static final class Item {
    final String text;
    String note;
    final Icon icon;
    final @Nullable Navigatable nav;
    final List<Item> kids = new ArrayList<>();

    Item(String text, String note, Icon icon, @Nullable Navigatable nav) {
      this.text = text;
      this.note = note;
      this.icon = icon;
      this.nav = nav;
    }

    /** 나무 빠른 찾기가 보는 글. */
    @Override
    public String toString() { return text; }
  }

  /** URL 하나. key 는 방식 + URL 이다. */
  record Api(String http, String url, String where, SmartPsiElementPointer<PsiMethod> method) {
    String label() { return http.isEmpty() ? url : http + " " + url; }
    String key() { return label() + " " + where; }
  }

  /** 화면 파일 하나의 글과 그 낱말 조각(FlowRules.tokens, 정규식을 돌리기 전에 거르는 데 쓴다). */
  record Front(VirtualFile file, CharSequence text, Set<String> tokens) {
    /** url 을 부를 수 있는 파일이면(조각이 모두 들어 있으면) true. */
    boolean mayCall(Api api) { return tokens.containsAll(FlowRules.literalTokens(api.url())); }
  }

  /** 매퍼 XML 하나를 읽은 것. text 와 doc 은 같은 글이다. */
  record Xml(VirtualFile file, CharSequence text, MapperDoc doc, MapperSqlScan.Result sql) {}

  /**
   * 찾은 나무와, 그 뒤 따로 읽을 화면 찾기 재료. screens 가 true 면 {@link #screensGroup} 을 root 아래 붙인다.
   * 화면 파일 글 읽기를 앞 찾기와 나눠 쓰기 동작이 끼어도 처음부터 다시 도는 양을 줄인다.
   */
  record Found(Item root, List<Api> apis, boolean screens) {}

  /** 매퍼 문장 하나(읽기 접근 밖으로 넘길 때). */
  record Statement(VirtualFile file, String namespace, String id, String tag, int offset) {}

  /** 읽기 접근 · 색인 끝난 상태에서 work 를 돌린다. 쓰기 동작이 끼면 처음부터 다시 돈다. 화면 스레드에서 부르지 않는다. */
  static <T> T read(Project project, Callable<T> work) {
    return ReadAction.nonBlocking(work).inSmartMode(project).executeSynchronously();
  }

  // ---------------------------------------------------------------- URL

  /** 프로젝트의 모든 컨트롤러 URL(URL 차례). */
  static List<Api> apis(Project project) {
    Run run = new Run(project);
    List<Api> out = new ArrayList<>();
    for (PsiClass c : run.controllers()) {
      for (PsiMethod m : c.getMethods()) {
        ProgressManager.checkCanceled();
        out.addAll(run.apisOf(m));
      }
    }
    out.sort(Comparator.comparing(Api::url).thenComparing(Api::http).thenComparing(Api::where));
    return out;
  }

  /** 프로젝트의 화면 파일들과 그 글. node_modules · 압축 · 큰 파일은 뺀다. */
  static List<Front> fronts(Project project) {
    List<Front> out = new ArrayList<>();
    GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
    ProjectFileIndex index = ProjectFileIndex.getInstance(project);
    for (String ext : FRONT_EXTENSIONS) {
      for (VirtualFile f : FilenameIndex.getAllFilesByExt(project, ext, scope)) {
        ProgressManager.checkCanceled();
        if (f.getLength() > MAX_FRONT_BYTES || f.getName().endsWith(MINIFIED_SUFFIX)) continue;
        VirtualFile root = index.getContentRootForFile(f);
        String relative = root == null ? null : VfsUtilCore.getRelativePath(f, root);
        if (FlowRules.skippedFront(relative == null ? f.getPath() : relative)) continue;
        CharSequence text = textOf(f);
        out.add(new Front(f, text, FlowRules.tokens(text)));
      }
    }
    return out;
  }

  /** 편집기에 열린 문서가 있으면 그 글(저장 전 포함), 없으면 디스크 글. */
  private static CharSequence textOf(VirtualFile f) {
    Document d = FileDocumentManager.getInstance().getCachedDocument(f);
    return d != null ? d.getImmutableCharSequence() : LoadTextUtil.loadText(f);
  }

  /** api 를 부르는 화면 위치들(앞에서 MAX_FRONT_HITS 개). URL 에 글자 마디가 없으면 null(찾을 수 없음). */
  static @Nullable List<Item> screens(Project project, Api api, List<Front> fronts) {
    Pattern p = FlowRules.frontPattern(api.url());
    if (p == null) return null;
    List<Item> out = new ArrayList<>();
    for (Front f : fronts) {
      ProgressManager.checkCanceled();
      if (out.size() >= MAX_FRONT_HITS) break;
      if (!f.mayCall(api)) continue;
      Matcher m = p.matcher(f.text());
      while (out.size() < MAX_FRONT_HITS && m.find()) {
        int line = StringUtil.offsetToLineNumber(f.text(), m.start()) + 1;
        out.add(new Item(f.file().getName() + ":" + line, f.file().getParent() == null ? "" : f.file().getParent().getPath(),
            f.file().getFileType().getIcon(), new OpenFileDescriptor(project, f.file(), m.start())));
      }
    }
    return out;
  }

  // ---------------------------------------------------------------- 1 호출 흐름

  /** method 에서 아래로: 부르는 서비스(구현) · 매퍼 메서드 · XML 문장 · 표, 그리고 이 URL 을 부르는 화면. */
  static Found forward(Project project, PsiMethod method) {
    Run run = new Run(project);
    Item root = run.methodItem(method, null);
    List<Api> apis = run.apisOf(method);
    root.note = labels(apis);
    run.expanded.add(method);
    if (method.getBody() != null) {
      run.down(method, root, 0);
    } else {
      // 인터페이스 · 추상 메서드면 구현마다 따라간다.
      for (PsiMethod impl : run.implementations(method)) {
        if (impl == method) continue;
        Item ii = run.add(root, run.methodItem(impl, null));
        if (run.expanded.add(impl)) run.down(impl, ii, 1);
      }
    }
    return new Found(run.finish(root), apis, !apis.isEmpty());
  }

  /** apis 를 부르는 화면 묶음(새 마디). 읽기 접근 안에서 부른다. */
  static Item screensGroup(Project project, List<Api> apis) {
    Run run = new Run(project);
    Item g = new Item(EiDbBundle.message("codeflow.group.screens"), "", AllIcons.FileTypes.Html, null);
    if (apis.isEmpty()) g.note = EiDbBundle.message("codeflow.note.none");
    else run.addScreens(g, apis, fronts(project));
    return run.finish(g);
  }

  // ---------------------------------------------------------------- 2 바꾸면 영향

  /** 메서드 · 필드 · 클래스를 바꾸면 영향 받는 곳: 부르는 곳(거꾸로, URL 까지) · 매퍼 SQL · API · 화면. 다른 요소면 null. */
  static @Nullable Found impact(Project project, PsiElement target) {
    Run run = new Run(project);
    Item root;
    if (target instanceof PsiMethod m) {
      root = run.methodItem(m, null);
      for (Api a : run.apisOf(m)) run.apis.putIfAbsent(a.key(), a);
      Item users = run.group(root, EiDbBundle.message("codeflow.group.callers"), AllIcons.Hierarchy.Supertypes);
      run.expanded.add(m);
      run.usedBy(run.refsTo(m), users, 0);
      PsiClass c = m.getContainingClass();
      if (c != null && run.isMapper(c, m)) {
        Item sql = run.group(root, EiDbBundle.message("codeflow.group.sql"), AllIcons.FileTypes.Xml);
        run.addStatements(sql, c, m.getName());
      }
    } else if (target instanceof PsiField f && f.getContainingClass() != null) {
      PsiClass owner = f.getContainingClass();
      root = new Item(owner.getName() + "." + f.getName(), EiDbBundle.message("codeflow.kind.field"), AllIcons.Nodes.Field, run.nav(f));
      Item users = run.group(root, EiDbBundle.message("codeflow.group.users"), AllIcons.Hierarchy.Supertypes);
      run.usedBy(run.refsTo(f), users, 0);
      Item sql = run.group(root, EiDbBundle.message("codeflow.group.sql"), AllIcons.FileTypes.Xml);
      run.mapperUses(owner, f.getName(), sql);
    } else if (target instanceof PsiClass c && c.getQualifiedName() != null) {
      root = new Item(c.getName(), c.getQualifiedName(), AllIcons.Nodes.Class, run.nav(c));
      Item users = run.group(root, EiDbBundle.message("codeflow.group.users"), AllIcons.Hierarchy.Supertypes);
      run.usedBy(run.refs(ReferencesSearch.search(c, run.scope)), users, 0);
      Item sql = run.group(root, EiDbBundle.message("codeflow.group.sql"), AllIcons.FileTypes.Xml);
      run.mapperUses(c, null, sql);
    } else {
      return null;
    }
    return run.withApis(root);
  }

  // ---------------------------------------------------------------- 3 표 · 칼럼

  /** 표(「스키마.표」 도 됨) · 칼럼(빈 글이면 표만)을 쓰는 매퍼 문장과 그 매퍼 메서드를 부르는 곳(URL 까지), API, 화면. */
  static Found tableUsers(Project project, String tableInput, String column) {
    Run run = new Run(project);
    String[] split = FlowRules.splitTable(tableInput);
    String schema = split[0];
    String table = split[1];
    String col = column.trim().replace("\"", "");
    String title = col.isEmpty() ? tableInput.trim() : tableInput.trim() + "." + col;
    Item root = new Item(title, EiDbBundle.message("codeflow.kind.table"), AllIcons.Nodes.DataTables, null);
    for (MapperIndex.Entry e : run.sourceEntries()) {
      Xml x = run.xml(e.file());
      if (x == null) continue;
      for (MapperDoc.Node st : x.doc().statements) {
        ProgressManager.checkCanceled();
        if (!run.room()) break;
        List<int[]> ranges = ranges(x.doc(), st);
        String sql = FlowRules.withoutTags(textOf(x.text(), ranges));
        boolean certain = usesTable(x.sql(), ranges, schema, table);
        if (!certain && !FlowRules.hasTable(sql, schema, table)) continue;
        if (!col.isEmpty()) {
          boolean certainCol = usesColumn(x.sql(), ranges, table, col);
          if (!certainCol && !FlowRules.hasWord(sql, col)) continue;
          certain &= certainCol;
        }
        Item si = run.add(root, run.statementItem(x, st));
        if (!certain) si.note += " · " + EiDbBundle.message("codeflow.note.text.match");
        run.addCallersOfStatement(si, x.doc().namespace, st.attr(ATTR_ID));
      }
    }
    if (root.kids.isEmpty()) root.note = EiDbBundle.message("codeflow.note.none");
    return run.withApis(root);
  }

  /** ranges 안에 schema.table(schema 가 null 이면 어느 스키마든)을 표로 쓴 곳이 있으면 true. */
  private static boolean usesTable(MapperSqlScan.Result sql, List<int[]> ranges, @Nullable String schema, String table) {
    for (MapperSqlScan.Table t : sql.tables()) {
      if (inside(ranges, t.start()) && sameTable(t, schema, table)) return true;
    }
    return false;
  }

  private static boolean usesColumn(MapperSqlScan.Result sql, List<int[]> ranges, String table, String column) {
    for (MapperSqlScan.Column c : sql.columns()) {
      if (inside(ranges, c.start()) && sameTable(c.table(), null, table) && MapperSqlScan.bare(c.name()).equalsIgnoreCase(column)) return true;
    }
    return false;
  }

  private static boolean sameTable(MapperSqlScan.Table t, @Nullable String schema, String table) {
    if (!MapperSqlScan.bare(t.name()).equalsIgnoreCase(table)) return false;
    return schema == null || t.schema() == null || MapperSqlScan.bare(t.schema()).equalsIgnoreCase(schema);
  }

  // ---------------------------------------------------------------- 7 안 쓰는 것

  /** 프로젝트의 매퍼 문장들(소스 쪽 XML 만, 문서 차례). */
  static List<Statement> statements(Project project) {
    Run run = new Run(project);
    List<Statement> out = new ArrayList<>();
    for (MapperIndex.Entry e : run.sourceEntries()) {
      Xml x = run.xml(e.file());
      // 열린 문서에서 namespace 를 지운 참이면(색인과 글이 다름) 매퍼가 아니다.
      if (x == null || x.doc().namespace == null) continue;
      for (MapperDoc.Node st : x.doc().statements) {
        out.add(new Statement(e.file(), x.doc().namespace, st.attr(ATTR_ID), st.name, st.start));
      }
    }
    return out;
  }

  /**
   * 부르는 곳을 못 찾은 매퍼 문장이면 그 줄, 아니면 null. resultMap 의 association · collection select 가 부르는 문장(nestedSelects,
   * 「네임스페이스.id」)은 쓰는 것으로 본다. 인터페이스나 메서드가 없으면 문자열로 부를 수도 있어 그렇게 적는다.
   * 파일이 지워졌으면 null.
   */
  static @Nullable Item unusedStatement(Project project, Statement s, Set<String> nestedSelects) {
    if (!s.file().isValid() || nestedSelects.contains(s.namespace() + "." + s.id())) return null;
    Item item = new Item(s.file().getName() + " · " + s.id(), kindLabel(s.tag()), AllIcons.FileTypes.Xml,
        new OpenFileDescriptor(project, s.file(), s.offset()));
    PsiClass mapper = JavaPsiFacade.getInstance(project).findClass(s.namespace(), GlobalSearchScope.allScope(project));
    if (mapper == null) {
      item.note += " · " + EiDbBundle.message("codeflow.note.no.interface");
      return item;
    }
    PsiMethod[] ms = mapper.findMethodsByName(s.id(), true);
    if (ms.length == 0) {
      item.note += " · " + EiDbBundle.message("codeflow.note.no.method");
      return item;
    }
    GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
    for (PsiMethod m : ms) {
      if (anyCodeRef(MethodReferencesSearch.search(m, scope, true), null)) return null;
    }
    return item;
  }

  /** 매퍼 XML 의 select 속성(association · collection 이 부르는 문장)들을 「네임스페이스.id」 로. 점이 없으면 그 파일의 네임스페이스. */
  static Set<String> nestedSelects(Project project) {
    Run run = new Run(project);
    Set<String> out = new HashSet<>();
    for (MapperIndex.Entry e : run.sourceEntries()) {
      Xml x = run.xml(e.file());
      if (x != null) collectSelects(x.doc().root, x.doc().namespace, out);
    }
    return out;
  }

  private static void collectSelects(MapperDoc.Node n, @Nullable String namespace, Set<String> out) {
    String v = n.attr(ATTR_SELECT);
    if (v != null && !v.isBlank()) out.add(v.contains(".") ? v.trim() : namespace + "." + v.trim());
    for (MapperDoc.Node k : n.children) {
      if (k.name != null) collectSelects(k, namespace, out);
    }
  }

  /** 프로젝트의 DTO 클래스(이름이 Dto · Vo 로 끝남, 인터페이스 · 어노테이션 · enum 은 뺌). */
  static List<SmartPsiElementPointer<PsiClass>> dtos(Project project) {
    GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
    PsiShortNamesCache names = PsiShortNamesCache.getInstance(project);
    SmartPointerManager pointers = SmartPointerManager.getInstance(project);
    List<SmartPsiElementPointer<PsiClass>> out = new ArrayList<>();
    for (String name : names.getAllClassNames()) {
      ProgressManager.checkCanceled();
      if (!FlowRules.isDtoName(name)) continue;
      for (PsiClass c : names.getClassesByName(name, scope)) {
        if (!c.isInterface() && !c.isAnnotationType() && !c.isEnum() && c.getQualifiedName() != null) out.add(pointers.createSmartPsiElementPointer(c));
      }
    }
    return out;
  }

  /** 매퍼 XML 의 형식 속성 값들(소문자). 별칭 · 단순 이름 · 정규 이름이 섞여 있고, typeAlias 별칭은 그 클래스 이름도 넣는다. */
  static Set<String> mapperTypeNames(Project project) {
    Run run = new Run(project);
    Set<String> written = new HashSet<>();
    for (MapperIndex.Entry e : run.sourceEntries()) {
      Xml x = run.xml(e.file());
      if (x != null) collectTypes(x.doc().root, written);
    }
    Set<String> out = new HashSet<>();
    for (String v : written) {
      out.add(v.toLowerCase(Locale.ROOT));
      String type = v.contains(".") ? null : MapperIndex.typeAlias(project, v);
      if (type != null) out.add(type.toLowerCase(Locale.ROOT));
    }
    return out;
  }

  /** n 과 그 안쪽 요소들의 형식 속성 값(쓴 그대로). */
  private static void collectTypes(MapperDoc.Node n, Set<String> out) {
    for (String a : TYPE_ATTRS) {
      String v = n.attr(a);
      if (v != null && !v.isBlank()) out.add(v.trim());
    }
    for (MapperDoc.Node k : n.children) {
      if (k.name != null) collectTypes(k, out);
    }
  }

  /**
   * 아무도 쓰지 않는 DTO 면 그 줄, 아니면 null. 자바 참조가 없고, 매퍼 XML 형식 속성에 정규 이름 · 단순 이름(별칭은 보통 단순 이름)이 없을 때다.
   * 클래스가 지워졌으면 null.
   */
  static @Nullable Item unusedDto(Project project, SmartPsiElementPointer<PsiClass> pointer, Set<String> xmlTypes) {
    PsiClass c = pointer.getElement();
    if (c == null || c.getQualifiedName() == null || c.getName() == null) return null;
    if (xmlTypes.contains(c.getQualifiedName().toLowerCase(Locale.ROOT)) || xmlTypes.contains(c.getName().toLowerCase(Locale.ROOT))) return null;
    if (anyCodeRef(ReferencesSearch.search(c, GlobalSearchScope.projectScope(project)), c)) return null;
    return new Item(c.getName(), c.getQualifiedName(), AllIcons.Nodes.Class, new PsiNav(pointer));
  }

  // ---------------------------------------------------------------- 공용

  /** 코드로 쓴 참조면 true: import 문 · 문서 주석 안이 아니다. */
  private static boolean isCode(PsiElement el) {
    return PsiTreeUtil.getParentOfType(el, PsiDocComment.class, PsiImportStatementBase.class) == null;
  }

  /** query 에 코드로 쓴 참조가 하나라도 있으면 true. self 가 있으면 그 클래스 안의 참조는 세지 않는다. */
  private static boolean anyCodeRef(Query<PsiReference> query, @Nullable PsiClass self) {
    boolean[] found = {false};
    query.forEach((Processor<PsiReference>) ref -> {
      PsiElement el = ref.getElement();
      if (isCode(el) && (self == null || !PsiTreeUtil.isAncestor(self, el, false))) found[0] = true;
      return !found[0];
    });
    return found[0];
  }

  private static String labels(List<Api> apis) {
    List<String> out = new ArrayList<>();
    for (Api a : apis) out.add(a.label());
    return String.join(", ", out);
  }

  /** 문장 태그 → 「조회 · 넣기 · 고치기 · 지우기」. */
  static String kindLabel(String tag) {
    return switch (tag) {
      case "select" -> EiDbBundle.message("codeflow.kind.select");
      case "insert" -> EiDbBundle.message("codeflow.kind.insert");
      case "update" -> EiDbBundle.message("codeflow.kind.update");
      case "delete" -> EiDbBundle.message("codeflow.kind.delete");
      default -> tag;
    };
  }

  /** 문장 노드와 그 노드가 include 하는 같은 파일의 sql 조각들(안의 include 도)의 범위. */
  static List<int[]> ranges(MapperDoc doc, MapperDoc.Node statement) {
    List<int[]> out = new ArrayList<>();
    out.add(new int[]{statement.start, statement.end});
    addIncludes(doc, statement, out, new HashSet<>());
    return out;
  }

  private static void addIncludes(MapperDoc doc, MapperDoc.Node n, List<int[]> out, Set<MapperDoc.Node> seen) {
    for (MapperDoc.Node k : n.children) {
      if (k.name == null) continue;
      if (!INCLUDE_TAG.equals(k.name)) {
        addIncludes(doc, k, out, seen);
        continue;
      }
      MapperDoc.Node frag = doc.fragment(k.attr(ATTR_REFID));
      if (frag == null || !seen.add(frag)) continue;
      out.add(new int[]{frag.start, frag.end});
      addIncludes(doc, frag, out, seen);
    }
  }

  private static boolean inside(List<int[]> ranges, int at) {
    for (int[] r : ranges) {
      if (at >= r[0] && at < r[1]) return true;
    }
    return false;
  }

  /** ranges 의 글을 이은 것(범위 사이는 줄바꿈). */
  private static String textOf(CharSequence text, List<int[]> ranges) {
    StringBuilder out = new StringBuilder();
    for (int[] r : ranges) {
      if (r[1] <= text.length()) out.append(text, r[0], r[1]).append('\n');
    }
    return out.toString();
  }

  /** 누를 때 요소를 다시 찾아 그 자리로 가는 이동(요소가 지워졌으면 아무것도 하지 않음). */
  static final class PsiNav implements Navigatable {
    private final SmartPsiElementPointer<? extends PsiElement> pointer;

    PsiNav(SmartPsiElementPointer<? extends PsiElement> pointer) { this.pointer = pointer; }

    @Override
    public void navigate(boolean requestFocus) {
      PsiElement e = pointer.getElement();
      VirtualFile f = e == null ? null : PsiUtilCore.getVirtualFile(e);
      if (f != null) new OpenFileDescriptor(e.getProject(), f, e.getTextOffset()).navigate(requestFocus);
    }

    @Override
    public boolean canNavigate() { return pointer.getVirtualFile() != null; }

    @Override
    public boolean canNavigateToSource() { return canNavigate(); }
  }

  /** 찾기 한 번의 상태: 마디 수, 펼친 메서드, 읽은 XML, 만난 URL. */
  private static final class Run {
    final Project project;
    final GlobalSearchScope scope;
    final Set<PsiMethod> expanded = new HashSet<>();
    /** 지금 펼치는 길에 있는 같은 클래스 도우미(되부르기 막기). */
    final Set<PsiMethod> inlining = new HashSet<>();
    final Map<VirtualFile, Xml> xmls = new HashMap<>();
    final Map<String, Api> apis = new LinkedHashMap<>();
    /** 형식 글 → 클래스(찾지 못하면 null 도 기억). */
    final Map<String, PsiClass> types = new HashMap<>();
    int nodes;
    boolean cut;
    /** 한 요소의 참조가 MAX_REFS 를 넘어 일부만 본 적이 있으면 true. */
    boolean refsCut;

    Run(Project project) {
      this.project = project;
      this.scope = GlobalSearchScope.projectScope(project);
    }

    boolean room() {
      if (nodes < MAX_NODES) return true;
      cut = true;
      return false;
    }

    /** 마디마다 그 아래에 이미 단 호출(클래스#메서드). */
    private final Map<Item, Set<String>> shown = new java.util.IdentityHashMap<>();

    Item add(Item parent, Item kid) {
      nodes++;
      parent.kids.add(kid);
      return kid;
    }

    Item group(Item parent, String text, Icon icon) {
      return add(parent, new Item(text, "", icon, null));
    }

    Item finish(Item root) {
      if (cut) note(root, EiDbBundle.message("codeflow.note.cut", MAX_NODES));
      if (refsCut) note(root, EiDbBundle.message("codeflow.note.refs.cut", MAX_REFS));
      return root;
    }

    private static void note(Item item, String more) {
      item.note = item.note == null || item.note.isEmpty() ? more : item.note + " · " + more;
    }

    Navigatable nav(PsiElement e) {
      return new PsiNav(SmartPointerManager.getInstance(project).createSmartPsiElementPointer(e));
    }

    // ---- 컨트롤러 · URL

    Collection<PsiClass> controllers() {
      Set<PsiClass> out = new LinkedHashSet<>();
      boolean known = false;
      GlobalSearchScope all = GlobalSearchScope.allScope(project);
      for (String fqn : CONTROLLER_FQNS) {
        PsiClass annotation = JavaPsiFacade.getInstance(project).findClass(fqn, all);
        if (annotation == null) continue;
        known = true;
        out.addAll(AnnotatedElementsSearch.searchPsiClasses(annotation, scope).findAll());
      }
      // 스프링을 해석할 수 없으면(빌드 도구 연결 전) 어노테이션 짧은 이름으로 모든 클래스를 본다.
      if (!known) {
        for (PsiClass c : AllClassesSearch.search(scope, project).findAll()) {
          if (isController(c)) out.add(c);
        }
      }
      return out;
    }

    boolean isController(PsiClass c) {
      return LayerRules.any(LayerNav.annotationNames(c), LayerRules.CONTROLLER_ANNOTATIONS);
    }

    /** 컨트롤러 매핑 메서드면 그 URL 들(클래스 경로 × 메서드 경로), 아니면 빈 목록. */
    List<Api> apisOf(PsiMethod m) {
      PsiClass c = m.getContainingClass();
      if (c == null || !isController(c)) return List.of();
      List<String> bases = new ArrayList<>();
      for (PsiAnnotation a : c.getAnnotations()) {
        if (REQUEST_MAPPING.equals(shortName(a))) bases.addAll(paths(a));
      }
      if (bases.isEmpty()) bases.add("");
      List<Api> out = new ArrayList<>();
      for (PsiAnnotation a : m.getAnnotations()) {
        String name = shortName(a);
        if (name == null || !LayerRules.MAPPING_ANNOTATIONS.contains(name)) continue;
        String http = FlowRules.http(name, values(a, ATTR_METHOD));
        List<String> paths = paths(a);
        if (paths.isEmpty()) paths.add("");
        for (String base : bases) {
          for (String path : paths) {
            out.add(new Api(http, FlowRules.join(base, path), c.getName() + "." + m.getName(),
                SmartPointerManager.getInstance(project).createSmartPsiElementPointer(m)));
          }
        }
      }
      return out;
    }

    private static @Nullable String shortName(PsiAnnotation a) {
      PsiJavaCodeReferenceElement ref = a.getNameReferenceElement();
      return ref == null ? null : ref.getReferenceName();
    }

    private List<String> paths(PsiAnnotation a) {
      List<String> v = values(a, ATTR_VALUE);
      return v.isEmpty() ? values(a, ATTR_PATH) : v;
    }

    /** 속성 값들. 상수로 계산되면 그 글, 아니면 쓴 글(따옴표 뺌). 배열 · 하나 모두 된다. */
    private List<String> values(PsiAnnotation a, String attr) {
      PsiAnnotationMemberValue v = a.findDeclaredAttributeValue(attr);
      List<String> out = new ArrayList<>();
      if (v == null) return out;
      PsiAnnotationMemberValue[] items = v instanceof PsiArrayInitializerMemberValue arr ? arr.getInitializers() : new PsiAnnotationMemberValue[]{v};
      for (PsiAnnotationMemberValue item : items) {
        Object constant = JavaPsiFacade.getInstance(project).getConstantEvaluationHelper().computeConstantExpression(item);
        if (constant instanceof String s) out.add(s);
        else if (item instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) out.add(s);
        else out.add(StringUtil.unquoteString(item.getText()));
      }
      return out;
    }

    // ---- 아래로

    /** method 본문의 호출을 따라 parent 아래에 서비스 · 매퍼 마디를 단다. 같은 클래스(조상 포함)의 메서드는 마디 없이 그 안을 본다. */
    void down(PsiMethod method, Item parent, int depth) {
      if (depth >= MAX_DOWN_DEPTH || method.getBody() == null) return;
      PsiClass self = method.getContainingClass();
      // 같은 마디 아래 같은 이름의 호출은 한 줄만 둔다(같은 클래스 도우미를 펼친 것 · 인자 수만 다른 것 포함).
      Set<String> here = shown.computeIfAbsent(parent, k -> new HashSet<>());
      for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(method.getBody(), PsiMethodCallExpression.class)) {
        ProgressManager.checkCanceled();
        if (!room()) return;
        PsiMethod called = call.resolveMethod();
        PsiClass owner = called == null ? null : called.getContainingClass();
        if (owner == null) continue;
        // 공통 매퍼(BaseMapper 의 selectList 등)는 선언한 인터페이스가 아니라 부른 쪽 형식(UserMapper)의 XML 에 문장이 있다.
        PsiClass via = qualifierClass(call);
        PsiClass mapper = via != null && isMapper(via, called) ? via : isMapper(owner, called) ? owner : null;
        if (!here.add((mapper == null ? owner : mapper).getQualifiedName() + "#" + called.getName())) continue;
        if (mapper != null) {
          Item mi = add(parent, methodItem(called, nav(call)));
          if (mapper != owner) mi.note += " · " + mapper.getName();
          addStatements(mi, mapper, called.getName());
        } else if (self != null && called.getBody() != null && inProject(owner) && (owner == self || self.isInheritor(owner, true))) {
          // 같은 클래스의 도우미는 부르는 곳마다 펼친다(여러 서비스 메서드가 같은 도우미를 쓴다). 되부르기는 지금 길에서만 막는다.
          if (inlining.add(called)) {
            down(called, parent, depth + 1);
            inlining.remove(called);
          }
        } else if (inProject(owner) && LayerRules.isBean(owner.isInterface(), LayerNav.annotationNames(owner))) {
          for (PsiMethod impl : implementations(called)) {
            Item si = add(parent, methodItem(impl, nav(call)));
            if (expanded.add(impl)) down(impl, si, depth + 1);
            else si.note = EiDbBundle.message("codeflow.note.seen");
          }
        }
      }
    }

    /** 호출 대상 식의 형식 클래스(필드 · 변수 · 메서드 결과 모두). 대상이 없거나 클래스가 아니면 null. */
    private static @Nullable PsiClass qualifierClass(PsiMethodCallExpression call) {
      PsiExpression q = call.getMethodExpression().getQualifierExpression();
      return q != null && q.getType() instanceof PsiClassType t ? t.resolve() : null;
    }

    /** 매퍼 인터페이스 메서드면 true: 그 인터페이스의 매퍼 XML 이 있거나, @Mapper 이거나, 메서드에 SQL 어노테이션이 있다. */
    boolean isMapper(PsiClass owner, PsiMethod m) {
      if (!owner.isInterface()) return false;
      return !MapperIndex.find(project, owner.getQualifiedName()).isEmpty()
          || LayerRules.any(LayerNav.annotationNames(owner), MAPPER_ANNOTATIONS)
          || LayerRules.any(LayerNav.annotationNames(m), LayerRules.STATEMENT_ANNOTATIONS);
    }

    boolean inProject(PsiElement e) {
      VirtualFile f = PsiUtilCore.getVirtualFile(e);
      return f != null && scope.contains(f);
    }

    /** 본문이 있으면 그 메서드, 없으면 프로젝트 안 구현(MAX_IMPLS 개까지). 구현이 없으면 그 메서드. */
    Collection<PsiMethod> implementations(PsiMethod called) {
      if (called.getBody() != null) return List.of(called);
      List<PsiMethod> found = new ArrayList<>();
      OverridingMethodsSearch.search(called, scope, true).forEach((Processor<PsiMethod>) impl -> {
        ProgressManager.checkCanceled();
        if (impl.getBody() != null) found.add(impl);
        return found.size() < MAX_IMPLS;
      });
      return found.isEmpty() ? List.of(called) : found;
    }

    /** 매퍼 메서드 이름의 XML 문장들과 그 문장이 쓰는 표를 parent 아래에 단다(소스 쪽 XML). */
    void addStatements(Item parent, PsiClass mapper, String name) {
      for (MapperIndex.Entry e : sourceFirst(MapperIndex.find(project, mapper.getQualifiedName()))) {
        Xml x = xml(e.file());
        if (x == null) continue;
        for (MapperDoc.Node st : x.doc().statements) {
          if (!name.equals(st.attr(ATTR_ID)) || !room()) continue;
          Item si = add(parent, statementItem(x, st));
          for (String table : tables(x, st)) add(si, new Item(table, EiDbBundle.message("codeflow.kind.table"), AllIcons.Nodes.DataTables, null));
        }
      }
    }

    /** 문장(include 한 조각 포함)이 쓰는 표 이름들(쓴 차례, 같은 것은 한 번). */
    private Collection<String> tables(Xml x, MapperDoc.Node st) {
      List<int[]> ranges = ranges(x.doc(), st);
      Set<String> out = new LinkedHashSet<>();
      for (MapperSqlScan.Table t : x.sql().tables()) {
        if (!inside(ranges, t.start())) continue;
        String name = MapperSqlScan.bare(t.name()).toUpperCase(Locale.ROOT);
        out.add(t.schema() == null ? name : MapperSqlScan.bare(t.schema()).toUpperCase(Locale.ROOT) + "." + name);
      }
      return out;
    }

    Item statementItem(Xml x, MapperDoc.Node st) {
      return new Item(x.file().getName() + " · " + st.attr(ATTR_ID), kindLabel(st.name), AllIcons.FileTypes.Xml,
          new OpenFileDescriptor(project, x.file(), st.start));
    }

    // ---- 위로

    /** m(과 프로젝트 안 조상 메서드)을 부르는 참조들. 인터페이스로 부르는 곳도 잡으려고 조상 메서드도 찾는다. */
    List<PsiElement> refsTo(PsiMethod m) {
      List<PsiElement> out = new ArrayList<>();
      for (PsiMethod t : withSupers(m)) {
        if (out.size() >= MAX_REFS) break;
        out.addAll(refs(MethodReferencesSearch.search(t, scope, true)));
      }
      return out;
    }

    /** 필드와 그 getter · setter(롬복이 만든 것 포함)를 쓰는 참조들. */
    List<PsiElement> refsTo(PsiField f) {
      PsiMethod getter = PropertyUtilBase.findGetterForField(f);
      PsiMethod setter = PropertyUtilBase.findSetterForField(f);
      List<PsiElement> out = new ArrayList<>();
      // getter · setter 본문의 필드 참조는 빼고 그 둘을 부르는 곳을 본다(같은 곳이 두 번 나오지 않게).
      for (PsiElement el : refs(ReferencesSearch.search(f, scope))) {
        PsiMethod in = PsiTreeUtil.getParentOfType(el, PsiMethod.class);
        if (in == null || (!in.equals(getter) && !in.equals(setter))) out.add(el);
      }
      if (getter != null) out.addAll(refsTo(getter));
      if (setter != null) out.addAll(refsTo(setter));
      return out;
    }

    /** 참조 찾기 결과에서 앞의 MAX_REFS 개의 요소. */
    List<PsiElement> refs(Query<PsiReference> query) {
      List<PsiElement> out = new ArrayList<>();
      query.forEach((Processor<PsiReference>) ref -> {
        ProgressManager.checkCanceled();
        PsiElement el = ref.getElement();
        if (!isCode(el)) return true;
        if (out.size() >= MAX_REFS) {
          refsCut = true;
          return false;
        }
        out.add(el);
        return true;
      });
      return out;
    }

    private List<PsiMethod> withSupers(PsiMethod m) {
      List<PsiMethod> out = new ArrayList<>();
      Deque<PsiMethod> todo = new ArrayDeque<>();
      out.add(m);
      todo.add(m);
      while (!todo.isEmpty()) {
        for (PsiMethod s : todo.poll().findSuperMethods()) {
          if (inProject(s) && !out.contains(s)) {
            out.add(s);
            todo.add(s);
          }
        }
      }
      return out;
    }

    /**
     * 참조들을 담은 메서드마다 parent 아래 마디를 달고(이동은 참조 자리로), 컨트롤러 매핑 메서드면 URL 을 적고 멈춘다.
     * 아니면 그 메서드를 부르는 곳을 이어 올라간다. 매퍼 메서드면 그 XML 문장도 단다. 메서드 밖(필드 · 선언)은 클래스 마디로.
     */
    List<Api> usedBy(List<PsiElement> refs, Item parent, int depth) {
      Map<String, Api> all = new LinkedHashMap<>();
      Map<PsiMethod, PsiElement> methods = new LinkedHashMap<>();
      Map<PsiClass, PsiElement> outside = new LinkedHashMap<>();
      for (PsiElement el : refs) {
        if (!el.isValid()) continue;
        PsiMethod m = PsiTreeUtil.getParentOfType(el, PsiMethod.class);
        if (m != null) {
          methods.putIfAbsent(m, el);
          continue;
        }
        PsiClass c = PsiTreeUtil.getParentOfType(el, PsiClass.class);
        if (c != null) outside.putIfAbsent(c, el);
      }
      for (Map.Entry<PsiMethod, PsiElement> e : methods.entrySet()) {
        ProgressManager.checkCanceled();
        if (!room()) break;
        PsiMethod m = e.getKey();
        Item mi = add(parent, methodItem(m, nav(e.getValue())));
        climbed.add(m);
        PsiClass c = m.getContainingClass();
        if (c != null && isMapper(c, m)) addStatements(mi, c, m.getName());
        List<Api> found = apisOf(m);
        if (!found.isEmpty()) {
          mi.note = labels(found);
          for (Api a : found) apis.putIfAbsent(a.key(), a);
        } else if (!expanded.add(m)) {
          found = seen(mi, m);
        } else if (depth + 1 >= MAX_UP_DEPTH) {
          mi.note = EiDbBundle.message("codeflow.note.deep");
        } else {
          found = usedBy(refsTo(m), mi, depth + 1);
          reached.put(m, found);
        }
        for (Api a : found) all.putIfAbsent(a.key(), a);
      }
      for (Map.Entry<PsiClass, PsiElement> e : outside.entrySet()) {
        if (!room()) break;
        add(parent, new Item(String.valueOf(e.getKey().getName()), EiDbBundle.message("codeflow.note.outside"), AllIcons.Nodes.Class, nav(e.getValue())));
      }
      return new ArrayList<>(all.values());
    }

    /** 이미 올라가 본 메서드가 닿은 URL(아직 올라가는 중이면 없음). */
    private final Map<PsiMethod, List<Api>> reached = new HashMap<>();

    /** 다시 나온 메서드 마디: 다시 올라가지 않고 「위에 나옴」 과 먼저 올라가 닿은 URL 을 적는다. 그 URL 들을 돌려준다. */
    private List<Api> seen(Item mi, PsiMethod m) {
      List<Api> found = reached.getOrDefault(m, List.of());
      mi.note = EiDbBundle.message("codeflow.note.seen");
      if (found.isEmpty()) return found;
      int more = found.size() - MAX_SEEN_LABELS;
      String shown = labels(more > 0 ? found.subList(0, MAX_SEEN_LABELS) : found);
      mi.note += " · " + (more > 0 ? EiDbBundle.message("codeflow.note.more", shown, more) : shown);
      return found;
    }

    /** namespace.id 문장의 매퍼 메서드를 parent 아래 달고 그 메서드를 부르는 곳을 올라간다. 인터페이스 · 메서드가 없으면 그렇게 적는다. */
    void addCallersOfStatement(Item parent, @Nullable String namespace, @Nullable String id) {
      PsiClass mapper = namespace == null ? null : JavaPsiFacade.getInstance(project).findClass(namespace, GlobalSearchScope.allScope(project));
      PsiMethod[] ms = mapper == null || id == null ? PsiMethod.EMPTY_ARRAY : mapper.findMethodsByName(id, true);
      if (ms.length == 0) {
        parent.note += " · " + EiDbBundle.message(mapper == null ? "codeflow.note.no.interface" : "codeflow.note.no.method");
        return;
      }
      for (PsiMethod m : ms) {
        if (!room()) return;
        Item mi = add(parent, methodItem(m, nav(m)));
        climbed.add(m);
        if (expanded.add(m)) reached.put(m, usedBy(refsTo(m), mi, 1));
        else seen(mi, m);
      }
    }

    /** 메서드 마디: 「종류  클래스.메서드」. nav 가 null 이면 메서드 자리로 간다. */
    Item methodItem(PsiMethod m, @Nullable Navigatable at) {
      PsiClass c = m.getContainingClass();
      String kind = kindOf(c, m);
      String text = (c == null ? "" : c.getName() + ".") + m.getName();
      Icon icon = c != null && c.isInterface() ? AllIcons.Nodes.Interface : AllIcons.Nodes.Method;
      return new Item(text, kind, icon, at == null ? nav(m) : at);
    }

    private String kindOf(@Nullable PsiClass c, PsiMethod m) {
      if (c == null) return EiDbBundle.message("codeflow.kind.method");
      if (isController(c)) return EiDbBundle.message("codeflow.kind.controller");
      if (isMapper(c, m)) return EiDbBundle.message("codeflow.kind.mapper");
      if (LayerRules.any(LayerNav.annotationNames(c), LayerRules.SERVICE_ANNOTATIONS)) return EiDbBundle.message("codeflow.kind.service");
      return EiDbBundle.message("codeflow.kind.method");
    }

    // ---- 매퍼 XML

    /** 소스 쪽 매퍼 XML 들. 같은 namespace 에 소스 · 리소스 폴더의 것이 있으면 빌드 출력 복사본은 뺀다. */
    List<MapperIndex.Entry> sourceEntries() {
      Map<String, List<MapperIndex.Entry>> byNamespace = new LinkedHashMap<>();
      for (MapperIndex.Entry e : MapperIndex.all(project)) byNamespace.computeIfAbsent(e.namespace(), k -> new ArrayList<>()).add(e);
      List<MapperIndex.Entry> out = new ArrayList<>();
      for (List<MapperIndex.Entry> same : byNamespace.values()) out.addAll(sourceFirst(same));
      return out;
    }

    private List<MapperIndex.Entry> sourceFirst(List<MapperIndex.Entry> same) {
      ProjectFileIndex index = ProjectFileIndex.getInstance(project);
      List<MapperIndex.Entry> sources = new ArrayList<>();
      for (MapperIndex.Entry e : same) {
        if (index.isInSourceContent(e.file())) sources.add(e);
      }
      return sources.isEmpty() ? same : sources;
    }

    /** XML 을 읽고 SQL 의 표 · 칼럼을 뽑아 둔다. 읽지 못하면 null. */
    @Nullable Xml xml(VirtualFile f) {
      if (xmls.containsKey(f)) return xmls.get(f);
      Xml x = null;
      PsiFile psi = f.isValid() ? PsiManager.getInstance(project).findFile(f) : null;
      if (psi != null) {
        Document d = PsiDocumentManager.getInstance(project).getDocument(psi);
        CharSequence text = d != null ? d.getImmutableCharSequence() : psi.getViewProvider().getContents();
        MapperDoc doc = MapperDocs.of(psi);
        // 문서가 아직 커밋되지 않아 글이 다르면 위치가 어긋나므로 그 글로 다시 읽는다.
        if (doc.root.end != text.length()) doc = MapperDoc.parse(text);
        x = new Xml(f, text, doc, MapperSqlScan.scan(text, doc));
      }
      xmls.put(f, x);
      return x;
    }

    /**
     * dto(형식)와 이어진 매퍼 문장 · resultMap 을 parent 아래 단다. field 가 null 이면 이어진 것 모두,
     * 아니면 그 글에서 필드를 쓴 것(FlowRules.usesField: #{} · property · test), SQL 에 칼럼 이름 USER_NAME 으로만 나오면 「추정」.
     * 이어짐: 형식 속성(parameterType · resultType)이나 resultMap 의 type 이 dto, 또는 매퍼 메서드의 매개변수 · 돌려주는 형식에 dto 가 있음.
     */
    void mapperUses(PsiClass dto, @Nullable String field, Item parent) {
      for (MapperIndex.Entry e : sourceEntries()) {
        Xml x = xml(e.file());
        if (x == null) continue;
        MapperDoc.Node mapper = mapperNode(x.doc());
        if (mapper == null) continue;
        Set<MapperDoc.Node> statements = new HashSet<>(x.doc().statements);
        for (MapperDoc.Node n : mapper.children) {
          ProgressManager.checkCanceled();
          if (!room()) return;
          boolean statement = statements.contains(n);
          boolean resultMap = RESULT_MAP_TAG.equals(n.name);
          if (!statement && !resultMap) continue;
          if (!related(x, n, statement, dto)) continue;
          String note = statement ? kindLabel(n.name) : RESULT_MAP_TAG;
          if (field != null) {
            List<int[]> ranges = statement ? ranges(x.doc(), n) : List.of(new int[]{n.start, n.end});
            String text = textOf(x.text(), ranges);
            String snake = FlowRules.snake(field);
            if (FlowRules.usesField(text, field)) note += " · " + EiDbBundle.message("codeflow.note.field");
            else if (!snake.equals(field) && FlowRules.hasWord(FlowRules.withoutTags(text), snake)) note += " · " + EiDbBundle.message("codeflow.note.column.guess");
            else continue;
          }
          Item si = add(parent, new Item(x.file().getName() + " · " + n.attr(ATTR_ID), note, AllIcons.FileTypes.Xml,
              new OpenFileDescriptor(project, x.file(), n.start)));
          if (statement) addCallersOfStatement(si, x.doc().namespace, n.attr(ATTR_ID));
        }
      }
    }

    private static @Nullable MapperDoc.Node mapperNode(MapperDoc doc) {
      for (MapperDoc.Node n : doc.root.children) {
        if (MAPPER_TAG.equals(n.name)) return n;
      }
      return null;
    }

    private boolean related(Xml x, MapperDoc.Node n, boolean statement, PsiClass dto) {
      if (!statement) {
        // resultMap 은 안쪽 association · collection 의 형식(ofType · javaType)까지 본다.
        Set<String> nested = new LinkedHashSet<>();
        collectTypes(n, nested);
        for (String t : nested) {
          if (isClass(t, x.file(), dto)) return true;
        }
        return false;
      }
      for (String a : TYPE_ATTRS) {
        if (isClass(n.attr(a), x.file(), dto)) return true;
      }
      String resultMap = n.attr(RESULT_MAP_TAG);
      MapperDoc.Node mapper = mapperNode(x.doc());
      if (resultMap != null && mapper != null) {
        for (MapperDoc.Node r : mapper.children) {
          if (RESULT_MAP_TAG.equals(r.name) && resultMap.trim().equals(r.attr(ATTR_ID)) && isClass(r.attr(ATTR_TYPE), x.file(), dto)) return true;
        }
      }
      String ns = x.doc().namespace;
      String id = n.attr(ATTR_ID);
      PsiClass iface = ns == null ? null : JavaPsiFacade.getInstance(project).findClass(ns, GlobalSearchScope.allScope(project));
      if (iface == null || id == null) return false;
      for (PsiMethod m : iface.findMethodsByName(id, true)) {
        if (mentions(m.getReturnType(), dto)) return true;
        for (PsiParameter p : m.getParameterList().getParameters()) {
          if (mentions(p.getType(), dto)) return true;
        }
      }
      return false;
    }

    private boolean isClass(@Nullable String type, VirtualFile from, PsiClass dto) {
      if (type == null || type.isBlank()) return false;
      // 같은 단순 이름이 여럿이면 XML 이 있는 모듈 쪽으로 고르므로(DtoFields.find) 파일마다 따로 기억한다.
      String key = from.getPath() + "\n" + type.trim();
      if (!types.containsKey(key)) types.put(key, DtoFields.find(project, type.trim(), from));
      return sameOrSub(types.get(key), dto);
    }

    /** c 가 dto 이거나 dto 를 이어받은 클래스면 true(공통 부모 VO 의 필드를 자식 VO 로 쓰는 경우). */
    private static boolean sameOrSub(@Nullable PsiClass c, PsiClass dto) {
      return c != null && (dto.getManager().areElementsEquivalent(c, dto) || c.isInheritor(dto, true));
    }

    /** 형식(List&lt;Dto&gt; · Dto[] 같은 안쪽 형식 포함)에 dto(또는 그 자식)가 있으면 true. */
    private static boolean mentions(@Nullable PsiType t, PsiClass dto) {
      if (t instanceof PsiArrayType arr) return mentions(arr.getComponentType(), dto);
      if (!(t instanceof PsiClassType ct)) return false;
      if (sameOrSub(ct.resolve(), dto)) return true;
      for (PsiType p : ct.getParameters()) {
        if (mentions(p, dto)) return true;
      }
      return false;
    }

    // ---- API · 화면

    /** 거꾸로 올라가며 만난 메서드들(만난 차례). 매퍼 · 서비스 묶음을 만든다. */
    private final Set<PsiMethod> climbed = new LinkedHashSet<>();

    /** 올라가며 만난 메서드 가운데 종류가 kind 인 것을 root 아래 묶음으로 단다. 없으면 「없음」 을 적는다. */
    private void addJavaGroup(Item root, String groupKey, String kind, Icon icon) {
      List<PsiMethod> same = new ArrayList<>();
      for (PsiMethod m : climbed) {
        if (kind.equals(kindOf(m.getContainingClass(), m))) same.add(m);
      }
      Item g = group(root, EiDbBundle.message(groupKey, same.size()), icon);
      for (PsiMethod m : same) add(g, methodItem(m, null));
      if (same.isEmpty()) g.note = EiDbBundle.message("codeflow.note.none");
    }

    /** 찾는 동안 만난 매퍼 · 서비스 · URL 을 root 아래 묶음으로 달고, 화면 묶음은 뒤에서 붙이게 넘긴다. */
    Found withApis(Item root) {
      addJavaGroup(root, "codeflow.group.mappers", EiDbBundle.message("codeflow.kind.mapper"), AllIcons.Nodes.Interface);
      addJavaGroup(root, "codeflow.group.services", EiDbBundle.message("codeflow.kind.service"), AllIcons.Nodes.Class);
      List<Api> list = new ArrayList<>(apis.values());
      Item api = group(root, EiDbBundle.message("codeflow.group.apis", list.size()), AllIcons.Nodes.PpWeb);
      for (Api a : list) add(api, new Item(a.label(), a.where(), AllIcons.Nodes.Method, new PsiNav(a.method())));
      if (list.isEmpty()) api.note = EiDbBundle.message("codeflow.note.none");
      return new Found(finish(root), list, true);
    }

    void addScreens(Item parent, List<Api> list, List<Front> fronts) {
      if (fronts.isEmpty()) {
        parent.note = EiDbBundle.message("codeflow.note.no.front");
        return;
      }
      int total = 0;
      for (Api a : list) {
        if (!room()) break;
        List<Item> hits = screens(project, a, fronts);
        if (hits == null || hits.isEmpty()) continue;
        Item ai = add(parent, new Item(a.label(), a.where(), AllIcons.Nodes.PpWeb, null));
        for (Item h : hits) {
          if (!room()) break;
          add(ai, h);
          total++;
        }
      }
      parent.note = total == 0 ? EiDbBundle.message("codeflow.note.none") : EiDbBundle.message("codeflow.note.count", total);
    }
  }
}
