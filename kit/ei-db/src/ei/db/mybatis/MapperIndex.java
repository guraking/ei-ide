package ei.db.mybatis;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiModificationTracker;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 프로젝트의 매퍼 XML 을 namespace 로 찾는다. namespace 표는 프로젝트마다 한 번 만들어 두고 파일 구조나 XML 이
 * 바뀐 뒤에만 다시 만든다(다른 언어 파일의 편집으로는 다시 만들지 않는다). 다시 만들 때도 파일마다 수정 표시(열린 문서면 문서의 것)를 보고 바뀐 파일만 다시 읽는다.
 * 파일별 결과도 표에 딸려 프로젝트 사용자 데이터에 있어 프로젝트를 닫으면 함께 사라지고, 다시 만들 때 지금 없는 파일은 빠진다.
 * 읽기 접근 안에서 부른다. 색인 중이면 빈 결과.
 */
final class MapperIndex {
  private MapperIndex() {}

  private static final String XML_EXT = "xml";
  /** XML 언어 id. XMLLanguage 클래스에 기대지 않으려고 id 로 고른다. */
  private static final String XML_LANGUAGE_ID = "XML";
  /** 표를 다시 만드는 일은 한 번에 하나만 한다(여러 데몬 스레드가 같은 변경을 두고 동시에 다시 읽지 않게). */
  private static final Object REBUILD = new Object();

  /** 매퍼 XML 하나. ids 는 문장 id → 여는 태그 위치(문서 순서). 매퍼가 아니면 namespace 가 null. */
  record Entry(VirtualFile file, long stamp, String namespace, Map<String, Integer> ids) {}

  /** namespace → 매퍼 XML 들. version 은 만들 때의 {@link #version} 값. files 는 파일별 결과(매퍼 아닌 XML 포함). */
  private record Table(long version, Map<String, List<Entry>> byNamespace, Map<VirtualFile, Entry> files) {}

  private static final Key<Table> TABLE = Key.create("ei.db.mybatis.MapperIndex.table");

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

  /**
   * 파일 생성 · 삭제 · 이동 · 이름 바꿈(VFS 구조 수정 수)과 XML PSI 수정 수의 합.
   * 열리지 않은(PSI 를 읽지 않은) 파일이 디스크에서 바뀌면 PSI 가 모든 언어 수정 수를 올리므로 여기에도 잡힌다.
   */
  private static long version(Project project) {
    return VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.getModificationCount()
        + PsiModificationTracker.getInstance(project).forLanguages(l -> l.isKindOf(XML_LANGUAGE_ID)).getModificationCount();
  }

  private static Table table(Project project) {
    Table t = project.getUserData(TABLE);
    if (t != null && t.version() == version(project)) return t;
    synchronized (REBUILD) {
      long version = version(project);
      t = project.getUserData(TABLE);
      if (t != null && t.version() == version) return t;
      Map<VirtualFile, Entry> old = t == null ? Map.of() : t.files();
      Map<String, List<Entry>> byNamespace = new HashMap<>();
      Map<VirtualFile, Entry> files = new ConcurrentHashMap<>();
      for (VirtualFile f : FilenameIndex.getAllFilesByExt(project, XML_EXT, GlobalSearchScope.projectScope(project))) {
        ProgressManager.checkCanceled();
        Entry e = entry(old, f);
        if (e == null) continue;
        files.put(f, e);
        if (e.namespace() != null) byNamespace.computeIfAbsent(e.namespace(), k -> new ArrayList<>()).add(e);
      }
      t = new Table(version, byNamespace, files);
      project.putUserData(TABLE, t);
      return t;
    }
  }

  /** 파일 하나의 최신 결과. 읽지 못하면 null. 색인 중이 아닐 때 부른다. */
  static Entry entry(Project project, VirtualFile f) {
    Map<VirtualFile, Entry> files = table(project).files();
    Entry e = entry(files, f);
    if (e != null) files.put(f, e);
    return e;
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
    if (!StringUtil.contains(text, "<mapper")) return new Entry(f, stamp, null, Map.of());
    MapperDoc doc = MapperDoc.parse(text);
    Map<String, Integer> ids = new LinkedHashMap<>();
    for (MapperDoc.Node s : doc.statements) ids.putIfAbsent(s.attr("id"), s.start);
    return new Entry(f, stamp, doc.namespace, ids);
  }
}
