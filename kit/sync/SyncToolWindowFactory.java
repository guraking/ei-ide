package kinggura.sync;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import javax.swing.JFrame;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/** 아래쪽 「동기화」 창. 처음 열 때 한 번 원격과 비교한다. 창 id 는 설정 · 단축키 호환을 위해 영어 그대로 두고 표시 이름만 바꾼다. */
public final class SyncToolWindowFactory implements ToolWindowFactory, DumbAware {
  static final String ID = "Synchronize";
  /** 우클릭으로 창을 열 때 비교할 파일. 있으면 창을 처음 만들 때 전체 비교를 건너뛰고 그 저장소만 비교한다. */
  private static final Key<VirtualFile> ONLY_FILE = Key.create("kinggura.sync.onlyFile");
  // 처음 열릴 때 창 높이: IDE 창 내용 높이의 이 비율(2026-10-29 사용자 캡처). 한 번만 맞추고, 그 뒤는 IDE 가 기억하는 크기를 쓴다.
  private static final float INITIAL_HEIGHT_RATIO = 0.35f;
  private static final String INITIAL_SIZE_DONE_KEY = "kinggura.sync.initialSize";

  @Override
  public void init(@NotNull ToolWindow toolWindow) {
    toolWindow.setStripeTitle(SyncBundle.message("sync.title"));
  }

  @Override
  public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
    SyncPanel panel = new SyncPanel(project, toolWindow.getDisposable());
    Content content = ContentFactory.getInstance().createContent(panel, "", false);
    toolWindow.getContentManager().addContent(content);
    if (project.getUserData(ONLY_FILE) == null) panel.refresh();
    applyInitialHeight(project, toolWindow);
  }

  /** 이 PC 에서 처음 열 때 한 번, 창 높이를 IDE 창 높이의 INITIAL_HEIGHT_RATIO 로 늘리거나 줄인다. 창이 아직 그려지기 전이면 다음 그리기 뒤에 잰다. */
  private static void applyInitialHeight(Project project, ToolWindow toolWindow) {
    PropertiesComponent properties = PropertiesComponent.getInstance();
    if (properties.getBoolean(INITIAL_SIZE_DONE_KEY) || !(toolWindow instanceof ToolWindowEx ex)) return;
    ApplicationManager.getApplication().invokeLater(() -> {
      JFrame frame = WindowManager.getInstance().getFrame(project);
      int current = toolWindow.getComponent().getHeight();
      if (frame == null || current <= 0) return;
      ex.stretchHeight(Math.round(frame.getContentPane().getHeight() * INITIAL_HEIGHT_RATIO) - current);
      properties.setValue(INITIAL_SIZE_DONE_KEY, true);
    });
  }

  /**
   * Git 메뉴 · 우클릭 Git 메뉴의 「작업 공간 동기화」. 주 메뉴에서는 창을 열기만 하고 비교는 창이 한다.
   * 우클릭(프로젝트 트리 등)에서는 고른 파일이 속한 저장소만 비교한다.
   */
  public static final class OpenAction extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      Project project = e.getProject();
      if (project == null) return;
      ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(ID);
      if (window == null) return;
      project.putUserData(ONLY_FILE, e.isFromContextMenu() ? e.getData(CommonDataKeys.VIRTUAL_FILE) : null);
      window.activate(() -> {
        VirtualFile only = project.getUserData(ONLY_FILE);
        project.putUserData(ONLY_FILE, null);
        Content content = window.getContentManager().getContent(0);
        if (only != null && content != null && content.getComponent() instanceof SyncPanel panel) panel.refreshFor(only);
      });
    }
  }
}
