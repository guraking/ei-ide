package ei.db.mybatis;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import ei.db.EiDbBundle;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「바꾼 것 다시 시험」의 모으기: 커밋하지 않은 바뀐 파일(ChangeListManager, 지운 파일 빼고)에서
 * 바뀐 · 새 매퍼 문장(SELECT 는 결과 비교 항목)과 바뀐 · 새 자바 메서드를 찾고, 그 영향(CodeFlow.impact)이 닿는 API 의 저장한 요청을 모은다.
 * 고치기 전 글은 MapperCompare.beforeText(VCS 마지막 커밋, Git · SVN 같음). 백그라운드에서 부른다. PSI 는 CodeFlow.read(읽기 · 색인 끝남) 안에서만 본다.
 */
final class RetestCollect {
  private RetestCollect() {}

  private static final Logger LOG = Logger.getInstance(RetestCollect.class);

  /** 영향을 따라갈 바뀐 메서드 · 문장 수 상한. 하나마다 프로젝트 전체 참조 찾기라 많으면 오래 걸린다. */
  static final int MAX_ROOTS = 40;
  /** 볼 바뀐 파일(자바 · XML) 수 상한. 파일마다 VCS 에서 고치기 전 글을 읽는다. */
  static final int MAX_FILES = 300;
  private static final String XML = "xml";
  private static final String JAVA = "java";
  private static final String SELECT = "select";
  private static final String ID = "id";

  enum Kind { MAPPER, API, API_NONE }

  /** 표 한 줄. result · outcome 은 돌린 뒤 화면 스레드에서만 바꾼다. */
  static final class Item {
    final Kind kind;
    final String name;
    final String why;
    final @Nullable Navigatable nav;
    /** MAPPER: 문장 파일 · 고치기 전 글 · 문장 id. API: 저장한 요청 key. */
    final @Nullable VirtualFile file;
    final @Nullable String before;
    final @Nullable String statementId;
    final @Nullable String apiKey;
    final boolean changesData;
    boolean checked;
    String result = "";
    @Nullable Object outcome;

    Item(Kind kind, String name, String why, @Nullable Navigatable nav, @Nullable VirtualFile file, @Nullable String before,
         @Nullable String statementId, @Nullable String apiKey, boolean changesData) {
      this.kind = kind;
      this.name = name;
      this.why = why;
      this.nav = nav;
      this.file = file;
      this.before = before;
      this.statementId = statementId;
      this.apiKey = apiKey;
      this.changesData = changesData;
      this.checked = kind != Kind.API_NONE && !changesData;
    }
  }

  /** 모은 것. skippedRoots · skippedFiles 는 상한에 걸려 보지 않은 수, failedFiles 는 고치기 전 글을 못 읽은 파일 수. */
  record Gathered(List<Item> items, int skippedRoots, int skippedFiles, int failedFiles) {}

  /** 영향 뿌리: 바뀐 메서드(또는 바뀐 문장의 매퍼 메서드). key 는 같은 뿌리를 한 번만 보려는 것. */
  private record Root(String key, SmartPsiElementPointer<PsiMethod> method, String why) {}

  /** 영향이 닿은 API 하나. classAndMethod 는 「패키지.클래스#메서드」(모르면 null). */
  private record ApiRef(String label, String where, @Nullable String classAndMethod, SmartPsiElementPointer<PsiMethod> method) {}

  static Gathered gather(Project project, ProgressIndicator indicator) {
    List<Item> mappers = new ArrayList<>();
    Map<String, Root> roots = new LinkedHashMap<>();
    int files = 0;
    int skippedFiles = 0;
    int failed = 0;
    for (Change change : ChangeListManager.getInstance(project).getAllChanges()) {
      indicator.checkCanceled();
      ContentRevision after = change.getAfterRevision();
      VirtualFile file = after == null ? null : after.getFile().getVirtualFile();
      if (file == null || !file.isValid() || file.isDirectory()) continue;
      boolean xml = XML.equalsIgnoreCase(file.getExtension());
      if (!xml && !JAVA.equalsIgnoreCase(file.getExtension())) continue;
      if (files >= MAX_FILES) {
        skippedFiles++;
        continue;
      }
      CharSequence now = ReadAction.compute(() -> currentText(file));
      if (xml && !MapperDoc.isMapperText(now)) continue;
      files++;
      String before;
      try {
        before = MapperCompare.beforeText(project, file);
      } catch (VcsException e) {
        LOG.info("바꾼 것 다시 시험: 고치기 전 글을 못 읽음 " + file.getPath(), e);
        failed++;
        continue;
      }
      if (before == null) continue;
      if (xml) {
        mapperChanges(project, file, before, now, mappers, roots);
      } else {
        for (Root r : CodeFlow.read(project, () -> javaChanges(project, file, before))) roots.putIfAbsent(r.key(), r);
      }
    }
    List<Root> all = new ArrayList<>(roots.values());
    List<Root> seen = all.subList(0, Math.min(MAX_ROOTS, all.size()));
    Map<String, Item> apis = new LinkedHashMap<>();
    Map<String, Item> unsaved = new LinkedHashMap<>();
    for (Root root : seen) {
      indicator.checkCanceled();
      for (ApiRef ref : CodeFlow.read(project, () -> apisOf(project, root))) {
        List<String> keys = ref.classAndMethod() == null ? List.of() : ApiBridge.savedKeys(project, ref.classAndMethod());
        if (keys.isEmpty()) {
          unsaved.putIfAbsent(ref.label() + " " + ref.where(), new Item(Kind.API_NONE, ref.label(), root.why(),
              new CodeFlow.PsiNav(ref.method()), null, null, null, null, false));
          continue;
        }
        for (String key : keys) {
          if (apis.containsKey(key)) continue;
          apis.put(key, new Item(Kind.API, ApiBridge.title(project, key), root.why(), new CodeFlow.PsiNav(ref.method()),
              null, null, null, key, ApiBridge.changesData(project, key)));
        }
      }
    }
    List<Item> items = new ArrayList<>(mappers);
    items.addAll(apis.values());
    items.addAll(unsaved.values());
    return new Gathered(items, all.size() - seen.size(), skippedFiles, failed);
  }

  /** 편집기 문서가 있으면 그 글(저장 전 포함), 없으면 디스크 글. 읽기 접근 안에서 부른다. */
  static CharSequence currentText(VirtualFile file) {
    Document d = FileDocumentManager.getInstance().getCachedDocument(file);
    return d != null ? d.getImmutableCharSequence() : LoadTextUtil.loadText(file);
  }

  /** 지금 글의 문장 가운데 고치기 전과 글이 다르거나 새로 생긴 것. SELECT 는 결과 비교 항목, 모두 매퍼 메서드를 영향 뿌리로. */
  private static void mapperChanges(Project project, VirtualFile file, String before, CharSequence now, List<Item> out, Map<String, Root> roots) {
    MapperDoc nowDoc = MapperDoc.parse(now);
    if (nowDoc.namespace == null) return;
    MapperDoc beforeDoc = MapperDoc.parse(before);
    Set<String> done = new LinkedHashSet<>();
    List<String[]> changed = new ArrayList<>();
    for (MapperDoc.Node s : nowDoc.statements) {
      String id = s.attr(ID);
      // databaseId 로 같은 id 가 여럿이면 첫 문장만 본다(MapperCompare.run 도 id 로 찾는다).
      if (!done.add(id)) continue;
      MapperDoc.Node old = beforeDoc.statement(id);
      String nowText = RetestRules.statementText(now, CodeFlow.ranges(nowDoc, s));
      if (old != null && nowText.equals(RetestRules.statementText(before, CodeFlow.ranges(beforeDoc, old)))) continue;
      String why = EiDbBundle.message(old == null ? "retest.why.statement.added" : "retest.why.statement.changed", file.getName(), id);
      if (SELECT.equals(s.name)) {
        out.add(new Item(Kind.MAPPER, nowDoc.shortNamespace() + "." + id, why, new OpenFileDescriptor(project, file, s.start),
            file, before, id, null, false));
      }
      changed.add(new String[]{id, why});
    }
    if (changed.isEmpty()) return;
    for (Root r : CodeFlow.read(project, () -> mapperMethods(project, nowDoc.namespace, changed))) roots.putIfAbsent(r.key(), r);
  }

  /** namespace 클래스(프로젝트 안)의 메서드 가운데 이름이 바뀐 문장 id 인 것. 읽기 접근 안. */
  private static List<Root> mapperMethods(Project project, String namespace, List<String[]> changed) {
    List<Root> out = new ArrayList<>();
    PsiClass mapper = JavaPsiFacade.getInstance(project).findClass(namespace, GlobalSearchScope.projectScope(project));
    if (mapper == null) return out;
    SmartPointerManager pointers = SmartPointerManager.getInstance(project);
    for (String[] c : changed) {
      for (PsiMethod m : mapper.findMethodsByName(c[0], false)) {
        out.add(new Root(key(m), pointers.createSmartPsiElementPointer(m), c[1]));
      }
    }
    return out;
  }

  /** 지금 파일의 메서드 가운데 고치기 전 같은 키 메서드와 글이 다르거나 새로 생긴 것. 읽기 접근 안. */
  private static List<Root> javaChanges(Project project, VirtualFile file, String before) {
    List<Root> out = new ArrayList<>();
    PsiFile now = PsiManager.getInstance(project).findFile(file);
    if (!(now instanceof PsiJavaFile)) return out;
    Map<String, String> old = new HashMap<>();
    if (!before.isEmpty()) {
      PsiFile parsed = PsiFileFactory.getInstance(project).createFileFromText(file.getName(), JavaFileType.INSTANCE, before);
      for (PsiMethod m : PsiTreeUtil.findChildrenOfType(parsed, PsiMethod.class)) old.putIfAbsent(key(m), RetestRules.code(m.getText()));
    }
    SmartPointerManager pointers = SmartPointerManager.getInstance(project);
    for (PsiMethod m : PsiTreeUtil.findChildrenOfType(now, PsiMethod.class)) {
      String key = key(m);
      String was = old.get(key);
      if (was != null && was.equals(RetestRules.code(m.getText()))) continue;
      String why = EiDbBundle.message(was == null ? "retest.why.method.added" : "retest.why.method.changed", file.getName(), m.getName());
      out.add(new Root(key, pointers.createSmartPsiElementPointer(m), why));
    }
    return out;
  }

  private static String key(PsiMethod m) {
    List<String> types = new ArrayList<>();
    for (PsiParameter p : m.getParameterList().getParameters()) types.add(p.getType().getPresentableText());
    PsiClass owner = m.getContainingClass();
    return RetestRules.methodKey(owner == null ? null : owner.getQualifiedName(), m.getName(), types);
  }

  /** 뿌리 메서드를 바꾸면 닿는 API(부르는 곳을 거꾸로 따라 URL 까지). 메서드가 없어졌으면 빈 목록. 읽기 접근 안. */
  private static List<ApiRef> apisOf(Project project, Root root) {
    List<ApiRef> out = new ArrayList<>();
    PsiMethod m = root.method().getElement();
    if (m == null) return out;
    CodeFlow.Found found;
    try {
      found = CodeFlow.impact(project, m);
    } catch (ProcessCanceledException e) {
      throw e;
    } catch (RuntimeException e) {
      // 한 뿌리의 찾기 실패로 나머지까지 멈추지 않는다.
      LOG.warn("바꾼 것 다시 시험: 영향 찾기 실패 " + root.key(), e);
      return out;
    }
    if (found == null) return out;
    for (CodeFlow.Api api : found.apis()) {
      PsiMethod target = api.method().getElement();
      PsiClass owner = target == null ? null : target.getContainingClass();
      String cm = owner == null || owner.getQualifiedName() == null ? null : owner.getQualifiedName() + "#" + target.getName();
      out.add(new ApiRef(api.label(), api.where(), cm, api.method()));
    }
    return out;
  }
}
