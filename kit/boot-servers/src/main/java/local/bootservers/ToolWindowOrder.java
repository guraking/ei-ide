package local.bootservers;

import com.intellij.toolWindow.DefaultToolWindowLayoutBuilder;
import com.intellij.toolWindow.DefaultToolWindowLayoutExtension;
import com.intellij.toolWindow.ToolWindowDescriptor;

/**
 * 아래쪽 도구 창 아이콘의 기본 순서. 늘 있는 창을 앞에, 내용이 있을 때만 생기는 창(실행 · 디버그 · 찾기 · ERD …)을 뒤에 둔다.
 * 뒤쪽 창이 생기고 사라져도 앞쪽 아이콘 자리는 바뀌지 않는다.
 * 저장된 배치가 없을 때(새 설치 · 새 레이아웃)만 쓰인다. 이미 저장된 배치는 창 → 레이아웃 메뉴로 다시 저장한다.
 */
public final class ToolWindowOrder implements DefaultToolWindowLayoutExtension {
  /** 위 · 아래 순서 그대로. id 는 각 도구 창의 등록 id(설정 열쇠)다. */
  private static final String[] BOTTOM = {
      "Version Control", "Problems", "Problems View", "Terminal", "Services",
      "Servers", "Synchronize", "EI Database Result",
      "Build", "Run", "Debug", "Find", "EI ERD", "TODO",
  };

  @Override
  public void buildV1Layout(DefaultToolWindowLayoutBuilder builder) {}

  @Override
  public void buildV2Layout(DefaultToolWindowLayoutBuilder builder) {
    builder.removeAll(w -> w.getAnchor() == ToolWindowDescriptor.ToolWindowAnchor.BOTTOM);
    for (String id : BOTTOM) builder.getBottom().addOrUpdate(id, null);
    builder.getRight().addOrUpdate("EI Database", null);
  }
}
