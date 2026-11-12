package ei.db.mybatis;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderEx;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 프로젝트의 매퍼 XML 을 namespace 로 찾는다. namespace 표는 프로젝트마다 한 번 만들어 두고,
 * XML 이 편집되거나 디스크에서 바뀌면(VCS 업데이트 포함) 그 파일만 다시 읽어 표를 고친다.
 * XML · 폴더가 생기거나 지워지거나 옮겨지거나 이름이 바뀌면, 또는 프로젝트 루트가 바뀌면 표를 다시 만든다(그때도 수정 표시가 같은 파일은 다시 읽지 않는다).
 * 파일별 결과도 표에 딸려 프로젝트 사용자 데이터에 있어 프로젝트를 닫으면 함께 사라진다.
 * 읽기 접근 안에서 부른다. 색인 중이면 빈 결과.
 */
final class MapperIndex {
  private MapperIndex() {}

  private static final String XML_EXT = "xml";
  /** 이 글이 없는 XML 은 별칭을 찾지 않는다(설정 파일만 읽게). */
  private static final String TYPE_ALIAS_MARK = "<typeAlias";
  /** 표를 고치는 일은 한 번에 하나만 한다(여러 데몬 스레드가 같은 변경을 두고 동시에 다시 읽지 않게). */
  private static final Object REBUILD = new Object();
  /** 표를 다시 만들어야 하는 변경(XML · 폴더 구조, 루트)마다 오른다. 표는 만들 때의 값을 가진다. */
  private static final AtomicLong STRUCTURE = new AtomicLong();

  /**
   * 매퍼 XML 하나. ids 는 문장 id → 첫 여는 태그 위치(문서 순서), starts 는 모든 문장의 여는 태그 위치 → id
   * (databaseId 로 나뉜 같은 id 문장도 각각). 매퍼가 아니면 namespace 가 null.
   * aliases 는 MyBatis 설정 파일(뿌리 configuration)의 typeAlias 별칭(소문자) → 클래스 이름, 다른 파일이면 빈 맵.
   */
  record Entry(VirtualFile file, long stamp, String namespace, Map<String, Integer> ids, Map<Integer, String> starts,
               Map<String, String> aliases) {}

  /** namespace → 매퍼 XML 들(고칠 때 새 맵으로 바꾼다). structure 는 만들 때의 {@link #STRUCTURE} 값. files 는 파일별 결과(매퍼 아닌 XML 포함). */
  private record Table(long structure, Map<String, List<Entry>> byNamespace, Map<VirtualFile, Entry> files) {}

  private static final Key<Table> TABLE = Key.create("ei.db.mybatis.MapperIndex.table");
  /** 표를 만든 뒤 내용이 바뀐 XML(다음 조회 때 그 파일만 다시 읽는다). */
  private static final Key<Set<VirtualFile>> DIRTY = Key.create("ei.db.mybatis.MapperIndex.dirty");

  /** namespace 가 같은 매퍼 XML 들. */
  static List<Entry> find(Project project, String namespace) {
    if (namespace == null || DumbService.isDumb(project)) return List.of();
    return table(project).byNamespace().getOrDefault(namespace, List.of());
  }

  /** 프로젝트의 모든 매퍼 XML. 색인 중이면 빈 목록. */
  static List<Entry> all(Project project) {
    if (DumbService.isDumb(project)) return List.of();
    List<Entry> out = new ArrayList<>();
    for (List<Entry> l : table(project).byNamespace().values()) out.addAll(l);
    return out;
  }

  private static Set<VirtualFile> dirtyOf(Project project) {
    return ((UserDataHolderEx) project).putUserDataIfAbsent(DIRTY, ConcurrentHashMap.newKeySet());
  }

  private static Table table(Project project) {
    Set<VirtualFile> dirty = dirtyOf(project);
    Table t = project.getUserData(TABLE);
    if (t != null && t.structure() == STRUCTURE.get() && dirty.isEmpty()) return t;
    synchronized (REBUILD) {
      t = project.getUserData(TABLE);
      long structure = STRUCTURE.get();
      if (t == null || t.structure() != structure) t = scan(project, structure, t == null ? Map.of() : t.files());
      if (!dirty.isEmpty()) t = refresh(project, t, dirty);
      project.putUserData(TABLE, t);
      return t;
    }
  }

  /** 프로젝트의 모든 XML 로 표를 새로 만든다. 수정 표시가 같은 파일은 old 의 결과를 그대로 쓴다. */
  private static Table scan(Project project, long structure, Map<VirtualFile, Entry> old) {
    Map<String, List<Entry>> byNamespace = new HashMap<>();
    Map<VirtualFile, Entry> files = new ConcurrentHashMap<>();
    ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
    for (VirtualFile f : FilenameIndex.getAllFilesByExt(project, XML_EXT, GlobalSearchScope.projectScope(project))) {
      ProgressManager.checkCanceled();
      Entry e = entry(old, f);
      if (e == null) continue;
      files.put(f, e);
      if (e.namespace() == null) continue;
      // 소스 · 리소스 폴더의 원본을 앞에 둔다. bin · build · out 같은 빌드 출력 복사본은 뒤에 남아 원본이 없을 때만 첫 번째가 된다.
      List<Entry> same = byNamespace.computeIfAbsent(e.namespace(), k -> new ArrayList<>());
      if (fileIndex.isInSourceContent(f)) same.add(0, e);
      else same.add(e);
    }
    return new Table(structure, byNamespace, files);
  }

  /**
   * dirty 의 파일만 다시 읽어 고친 표. 처리할 파일을 dirty 에서 먼저 빼고 읽으므로 그 사이의 새 변경은 dirty 에 다시 남는다.
   * 도중에 멈추면(취소) 뺀 파일을 dirty 에 돌려놓는다.
   */
  private static Table refresh(Project project, Table t, Set<VirtualFile> dirty) {
    List<VirtualFile> todo = new ArrayList<>(dirty);
    dirty.removeAll(todo);
    try {
      GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
      ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
      Map<VirtualFile, Entry> files = t.files();
      Map<String, List<Entry>> byNamespace = t.byNamespace();
      for (VirtualFile f : todo) {
        ProgressManager.checkCanceled();
        Entry old = files.get(f);
        Entry now = f.isValid() && scope.contains(f) ? entry(files, f) : null;
        if (now == old) continue;
        if (now == null) files.remove(f);
        else files.put(f, now);
        byNamespace = replaced(byNamespace, old, old == null ? null : old.namespace(), now, now == null ? null : now.namespace(),
            now != null && fileIndex.isInSourceContent(f));
      }
      return new Table(t.structure(), byNamespace, files);
    } catch (RuntimeException | Error ex) {
      dirty.addAll(todo);
      throw ex;
    }
  }

  /**
   * table 에서 old(oldNs 목록)를 빼고 now 를 newNs 목록에 넣은 새 맵. first 면 목록 앞에 넣는다. 빈 목록은 뺀다.
   * 받은 맵과 목록은 고치지 않는다(다른 스레드가 읽는 중일 수 있다). namespace 가 null 이면 그쪽은 건너뛴다.
   */
  static <E> Map<String, List<E>> replaced(Map<String, List<E>> table, @Nullable E old, @Nullable String oldNs,
                                           @Nullable E now, @Nullable String newNs, boolean first) {
    Map<String, List<E>> out = new HashMap<>(table);
    if (old != null && oldNs != null) {
      List<E> l = new ArrayList<>(out.getOrDefault(oldNs, List.of()));
      l.remove(old);
      if (l.isEmpty()) out.remove(oldNs);
      else out.put(oldNs, l);
    }
    if (now != null && newNs != null) {
      List<E> l = new ArrayList<>(out.getOrDefault(newNs, List.of()));
      if (first) l.add(0, now);
      else l.add(now);
      out.put(newNs, l);
    }
    return out;
  }

  /** MyBatis 설정 파일들의 typeAlias 가운데 alias(대소문자 무시)의 클래스 이름. 없거나 색인 중이면 null. */
  static @Nullable String typeAlias(Project project, String alias) {
    if (DumbService.isDumb(project)) return null;
    String key = alias.toLowerCase(Locale.ROOT);
    for (Entry e : table(project).files().values()) {
      String type = e.aliases().get(key);
      if (type != null) return type;
    }
    return null;
  }

  /** 파일 하나의 최신 결과. 읽지 못하면 null. 색인 중이 아닐 때 부른다. */
  static Entry entry(Project project, VirtualFile f) {
    return entry(table(project).files(), f);
  }

  private static boolean isXml(@Nullable VirtualFile f) {
    return f != null && XML_EXT.equalsIgnoreCase(f.getExtension());
  }

  private static boolean isXmlPath(String path) {
    return path.regionMatches(true, path.length() - XML_EXT.length() - 1, "." + XML_EXT, 0, XML_EXT.length() + 1);
  }

  /** 열린 모든 프로젝트에 f 를 다시 읽을 파일로 표시한다(그 프로젝트 파일이 아니면 다시 읽을 때 걸러진다). */
  private static void markDirty(VirtualFile f) {
    for (Project p : ProjectManager.getInstance().getOpenProjects()) {
      if (!p.isDisposed()) dirtyOf(p).add(f);
    }
  }

  /** 편집기 문서(저장 전 글 포함)가 바뀐 XML 을 표시한다. */
  public static final class Edits implements DocumentListener {
    @Override
    public void documentChanged(@NotNull DocumentEvent event) {
      VirtualFile f = FileDocumentManager.getInstance().getFile(event.getDocument());
      if (isXml(f)) markDirty(f);
    }
  }

  /** 디스크에서 바뀐 XML 은 그 파일만, XML · 폴더의 생성 · 삭제 · 이동 · 복사 · 이름 바꿈은 표 전체를 다시 만들게 한다. */
  public static final class Files implements BulkFileListener {
    /** before 에서 본 삭제 가운데 XML · 폴더가 있었는지(지운 뒤에는 폴더인지 알 수 없다). 쓰기 동작 안에서 before · after 가 차례로 불린다. */
    private boolean structuralDelete;

    @Override
    public void before(@NotNull List<? extends @NotNull VFileEvent> events) {
      for (VFileEvent e : events) {
        if (e instanceof VFileDeleteEvent d && (d.getFile().isDirectory() || isXml(d.getFile()))) structuralDelete = true;
      }
    }

    @Override
    public void after(@NotNull List<? extends @NotNull VFileEvent> events) {
      boolean structure = structuralDelete;
      structuralDelete = false;
      for (VFileEvent e : events) {
        if (e instanceof VFileContentChangeEvent c) {
          if (isXml(c.getFile())) markDirty(c.getFile());
        } else if (e instanceof VFilePropertyChangeEvent p) {
          if (VirtualFile.PROP_NAME.equals(p.getPropertyName())
              && (p.getFile().isDirectory() || isXmlPath(String.valueOf(p.getOldValue())) || isXml(p.getFile()))) {
            structure = true;
          }
        } else if (!(e instanceof VFileDeleteEvent)) {
          VirtualFile f = e.getFile();
          if (f == null || !f.isValid() || f.isDirectory() || isXmlPath(e.getPath())) structure = true;
        }
      }
      if (structure) STRUCTURE.incrementAndGet();
    }
  }

  /** 소스 · 콘텐츠 루트가 바뀌면 프로젝트 범위가 달라지므로 표를 다시 만든다. */
  public static final class Roots implements ModuleRootListener {
    @Override
    public void rootsChanged(@NotNull ModuleRootEvent event) {
      STRUCTURE.incrementAndGet();
    }
  }

  /** cache 의 결과가 수정 표시와 같으면 그것, 아니면 새로 읽은 것. cache 는 바꾸지 않는다. */
  private static Entry entry(Map<VirtualFile, Entry> cache, VirtualFile f) {
    if (f == null || !f.isValid() || f.isDirectory()) return null;
    Document doc = FileDocumentManager.getInstance().getCachedDocument(f);
    long stamp = doc != null ? doc.getModificationStamp() : f.getModificationStamp();
    Entry old = cache.get(f);
    if (old != null && old.stamp() == stamp) return old;
    CharSequence text;
    try {
      text = doc != null ? doc.getImmutableCharSequence() : VfsUtilCore.loadText(f);
    } catch (IOException ex) {
      // 읽지 못한 파일은 매퍼 후보에서 빠질 뿐이고, 다음 호출에서 다시 시도한다.
      return null;
    }
    return read(f, stamp, text);
  }

  private static Entry read(VirtualFile f, long stamp, CharSequence text) {
    if (!MapperDoc.isMapperText(text)) {
      Map<String, String> aliases = StringUtil.contains(text, TYPE_ALIAS_MARK) ? MapperDoc.typeAliases(text) : Map.of();
      return new Entry(f, stamp, null, Map.of(), Map.of(), aliases);
    }
    MapperDoc doc = MapperDoc.parse(text);
    Map<String, Integer> ids = new LinkedHashMap<>();
    Map<Integer, String> starts = new HashMap<>();
    for (MapperDoc.Node s : doc.statements) {
      ids.putIfAbsent(s.attr("id"), s.start);
      starts.put(s.start, s.attr("id"));
    }
    return new Entry(f, stamp, doc.namespace, ids, starts, Map.of());
  }
}
