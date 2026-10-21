package ei.db.mybatis;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;

import java.util.function.Function;

/**
 * 파일마다 한 번 읽은 {@link MapperDoc} 을 파일 사용자 데이터에 두고 같이 쓴다.
 * 문서가 있으면 문서 글(커밋 전 편집 포함)을 읽고 문서 수정 표시로, 없으면 파일 글을 읽고 PSI 수정으로 버린다.
 * 위치는 그 글 안의 오프셋이다. 읽기 접근 안에서 부른다. 돌려받은 결과는 고치지 않는다.
 */
final class MapperDocs {
  private MapperDocs() {}

  private static final String MAPPER_MARK = "<mapper";
  private static final Key<CachedValue<MapperDoc>> DOC = Key.create("ei.db.mybatis.MapperDocs.doc");
  private static final Key<CachedValue<Boolean>> IS_MAPPER = Key.create("ei.db.mybatis.MapperDocs.isMapper");

  static MapperDoc of(PsiFile file) {
    return cached(file, DOC, MapperDoc::parse);
  }

  /** 편집기 문서의 것. 문서에 PSI 파일이 없으면 그 자리에서 읽는다. */
  static MapperDoc of(Project project, Editor editor) {
    PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
    return file != null ? of(file) : MapperDoc.parse(editor.getDocument().getImmutableCharSequence());
  }

  /** 글에 &lt;mapper 가 있으면 true. 태그마다 부르는 곳에서 파일 전체를 다시 훑지 않게 한다. */
  static boolean isMapper(PsiFile file) {
    return cached(file, IS_MAPPER, text -> StringUtil.contains(text, MAPPER_MARK));
  }

  private static <T> T cached(PsiFile file, Key<CachedValue<T>> key, Function<CharSequence, T> read) {
    return CachedValuesManager.getCachedValue(file, key, () -> {
      Document d = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
      CharSequence text = d != null ? d.getImmutableCharSequence() : file.getViewProvider().getContents();
      return CachedValueProvider.Result.create(read.apply(text), d != null ? d : file);
    });
  }
}
