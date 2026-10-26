package local.bootservers;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer;

import java.util.List;

/**
 * GitHub 플러그인의 「Create Gist…」(고른 글 · 파일을 GitHub Gist 로 올림)를 모든 메뉴에서 뺀다.
 * 동작 자체는 등록된 채로 두므로 Find Action 으로는 계속 찾을 수 있다.
 * GitHub 플러그인을 끈 경우 동작이 없으므로 아무것도 하지 않는다.
 */
final class GistMenuOff implements ActionConfigurationCustomizer,
                                   ActionConfigurationCustomizer.SyncHeavyCustomizeStrategy {
    private static final String GIST_ACTION = "Github.Create.Gist";
    /** intellij.vcs.github.xml 에서 이 동작을 넣는 묶음 전부. */
    private static final List<String> GROUPS = List.of(
            "EditorPopupMenu", "ProjectViewPopupMenu", "EditorTabPopupMenu", "ConsoleEditorPopupMenu", "GitHub.MainMenu");

    @Override
    public void customize(ActionManager actionManager) {
        AnAction gist = actionManager.getAction(GIST_ACTION);
        if (gist == null) {
            return;
        }
        for (String groupId : GROUPS) {
            if (actionManager.getAction(groupId) instanceof DefaultActionGroup group) {
                group.remove(gist, GIST_ACTION);
            }
        }
    }
}
