package ei.db.mybatis;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.util.text.CharArrayUtil;
import ei.db.EiDbBundle;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 매퍼 XML 의 select · insert · update · delete 문장과 sql 조각 글에 쓴 ${}(값을 따옴표 없이 SQL 에 그대로 넣는다).
 * 태그 속성(test 등) · XML 주석 안은 보지 않는다. 문서 글만 읽어 색인 중에도 돈다.
 * 앞 글로 자리를 갈라 고칠 법을 다르게 알린다: 값 자리는 #{}, 정렬 · 이름 자리는 정해 둔 목록에서 고르기.
 */
public final class RiskDollarInspection extends LocalInspectionTool implements DumbAware {
  private static final String CDATA_OPEN = "<![CDATA[";

  @Override
  public ProblemDescriptor[] checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, boolean isOnTheFly) {
    if (!MapperDocs.isMapper(file)) return null;
    MapperDoc doc = MapperDocs.of(file);
    CharSequence text = file.getViewProvider().getContents();
    List<ProblemDescriptor> out = new ArrayList<>();
    List<MapperDoc.Node> bodies = new ArrayList<>(doc.statements);
    bodies.addAll(doc.fragments.values());
    for (MapperDoc.Node body : bodies) {
      ProgressManager.checkCanceled();
      scan(body, text, new StringBuilder(), file, manager, isOnTheFly, out);
    }
    return out.isEmpty() ? null : out.toArray(ProblemDescriptor.EMPTY_ARRAY);
  }

  /** 글 조각을 문서 순서로 따라가며 before 에 앞 글을 모은다(앞의 ${} 는 자리표시 글로). */
  private static void scan(MapperDoc.Node node, CharSequence text, StringBuilder before, PsiFile file, InspectionManager manager,
                           boolean isOnTheFly, List<ProblemDescriptor> out) {
    for (MapperDoc.Node n : node.children) {
      if (n.name != null) {
        scan(n, text, before, file, manager, isOnTheFly, out);
        continue;
      }
      int end = Math.min(n.end, text.length());
      int from = Math.min(n.start, end);
      if (from + CDATA_OPEN.length() <= end && CharArrayUtil.regionMatches(text, from, CDATA_OPEN)) from += CDATA_OPEN.length();
      int last = from;
      for (RiskRules.Dollar d : RiskRules.dollars(text, from, end)) {
        before.append(text, last, d.start());
        out.add(manager.createProblemDescriptor(file, new TextRange(d.start(), d.end()),
            message(RiskRules.dollarSpot(before.toString()), d.name()), ProblemHighlightType.GENERIC_ERROR_OR_WARNING, isOnTheFly));
        before.append(RiskRules.PLACEHOLDER);
        last = d.end();
      }
      before.append(text, last, end);
    }
  }

  /** 자리별 알림 글. 매퍼 XML · 자바 어노테이션 SQL 이 같이 쓴다. */
  static String message(RiskRules.Spot spot, String name) {
    String key = switch (spot) {
      case ORDER -> "risk.dollar.order";
      case NAME -> "risk.dollar.name";
      case VALUE -> "risk.dollar.value";
    };
    return EiDbBundle.message(key, name);
  }
}
