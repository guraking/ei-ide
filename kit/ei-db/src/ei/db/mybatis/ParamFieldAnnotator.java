package ei.db.mybatis;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 매퍼 XML 의 #{이름} 이 parameterType 클래스(상위 포함)의 필드 · getter 에 없으면 경고한다.
 * 클래스를 찾지 못하거나 Map · 기본형 · parameterType 없음이면 그 문장은 검사하지 않는다.
 */
public final class ParamFieldAnnotator extends ExternalAnnotator<PsiFile, List<MapperParams.Miss>> {
  @Override
  public @Nullable PsiFile collectInformation(@NotNull PsiFile file) {
    return StringUtil.contains(file.getViewProvider().getContents(), "<mapper") ? file : null;
  }

  @Override
  public @Nullable List<MapperParams.Miss> doAnnotate(PsiFile file) {
    // 쓰기 동작이 오면 읽기를 내려놓고 다시 시도하는 읽기라 큰 XML 에서도 타이핑을 막지 않는다.
    return ReadAction.nonBlocking(() -> file.isValid() ? check(file) : null).executeSynchronously();
  }

  @Override
  public void apply(@NotNull PsiFile file, List<MapperParams.Miss> misses, @NotNull AnnotationHolder holder) {
    if (misses == null) return;
    int length = file.getTextLength();
    for (MapperParams.Miss m : misses) {
      if (m.end() > length) continue; // 검사 뒤 글이 줄었으면 다음 검사에 맡긴다.
      holder.newAnnotation(HighlightSeverity.WARNING, m.message()).range(new TextRange(m.start(), m.end())).create();
    }
  }

  private static List<MapperParams.Miss> check(PsiFile file) {
    Project project = file.getProject();
    Map<String, Collection<String>> props = new HashMap<>();
    return MapperParams.check(file.getText(), type -> propertiesOf(project, type, props));
  }

  /** 검사할 문장마다 한 번 불리므로 여기서 취소를 확인한다. 찾지 못한 형식은 담지 않아 문장마다 다시 찾는다. */
  private static Collection<String> propertiesOf(Project project, String type, Map<String, Collection<String>> cache) {
    ProgressManager.checkCanceled();
    return cache.computeIfAbsent(type, t -> {
      PsiClass c = DtoFields.find(project, t);
      return c == null ? null : DtoFields.properties(c);
    });
  }
}
