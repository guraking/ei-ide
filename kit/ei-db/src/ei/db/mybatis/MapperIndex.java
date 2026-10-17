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
 * 프로젝트의 매퍼 XML 을 namespace 로 찾는다. namespace 표는 프로젝트마다 한 번 만들어 두고 VFS 나 PSI 가
 * 바뀐 뒤에만 다시 만든다. 다시 만들 때도 파일마다 수정 표시(열린 문서면 문서의 것)를 보고 바뀐 파일만 다시 읽는다.
 * 읽기 접근 안에서 부른다. 색인 중이면 빈 결과.
 */
final class MapperIndex {
  private MapperIndex() {}

  private static final String XML_EXT = "xml";

  /** 매퍼 XML 하나. ids 는 문장 id → 여는 태그 위치(문서 순서). 매퍼가 아니면 namespace 가 null. */
  record Entry(VirtualFile file, long stamp, String namespace, Map<String, Integer> ids) {}

  private static final Map<VirtualFile, Entry> CACHE = new ConcurrentHashMap<>();

  /** namespace → 매퍼 XML 들. version 은 만들 때의 VFS 수정 수 + PSI 수정 수. */
  private record Table(long version, Map<String, List<Entry>> byNamespace) {}

  private static final Key<Table> TABLE = Key.create("ei.db.mybatis.MapperIndex.table");

  /** namespace 가 같은 매퍼 XML 들. */
  static List<Entry> find(Project project, String namespace) {
    if (namespace == null || DumbService.isDumb(project)) return List.of();
    return table(project).byNamespace().getOrDefault(namespace, List.of());
  }

  private static Table table(Project project) {
    long version = VirtualFileManager.getInstance().getModificationCount()
        + PsiModificationTracker.getInstance(project).getModificationCount();
    Table t = project.getUserData(TABLE);
    if (t != null && t.version() == version) return t;
    Map<String, List<Entry>> byNamespace = new HashMap<>();
    for (VirtualFile f : FilenameIndex.getAllFilesByExt(project, XML_EXT, GlobalSearchScope.projectScope(project))) {
      ProgressManager.checkCanceled();
      Entry e = entry(f);
      if (e != null && e.namespace() != null) byNamespace.computeIfAbsent(e.namespace(), k -> new ArrayList<>()).add(e);
    }
    t = new Table(version, byNamespace);
    project.putUserData(TABLE, t);
    return t;
  }

  /** 파일 하나의 최신 결과. 읽지 못하면 null. */
  static Entry entry(VirtualFile f) {
    if (f == null || !f.isValid() || f.isDirectory()) return null;
    Document doc = FileDocumentManager.getInstance().getCachedDocument(f);
    long stamp = doc != null ? doc.getModificationStamp() : f.getModificationStamp();
    Entry old = CACHE.get(f);
    if (old != null && old.stamp() == stamp) return old;
    CharSequence text;
    try {
      text = doc != null ? doc.getImmutableCharSequence() : VfsUtilCore.loadText(f);
    } catch (IOException ex) {
      // 읽지 못한 파일은 매퍼 후보에서 빠질 뿐이고, 다음 호출에서 다시 시도한다.
      return null;
    }
    Entry e = read(f, stamp, text);
    CACHE.put(f, e);
    return e;
  }

  private static Entry read(VirtualFile f, long stamp, CharSequence text) {
    if (!StringUtil.contains(text, "<mapper")) return new Entry(f, stamp, null, Map.of());
    MapperDoc doc = MapperDoc.parse(text);
    Map<String, Integer> ids = new LinkedHashMap<>();
    for (MapperDoc.Node s : doc.statements) ids.putIfAbsent(s.attr("id"), s.start);
    return new Entry(f, stamp, doc.namespace, ids);
  }
}
