package local.bootservers;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPromoter;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.IdeActions;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 이클립스 단축키에서 F2 는 「이름 바꾸기」(RenameElement)와 「설명 보기」(QuickJavaDoc) 둘에 묶인다(kit/defaults 가 Eclipse.xml 에 더함).
 * 이클립스처럼 편집기 안에서는 설명 보기, 프로젝트 창 같은 나머지 곳에서는 이름 바꾸기를 먼저 고른다.
 * 둘 중 하나만 들어온 목록(다른 단축키)은 손대지 않는다.
 */
final class F2RenamePromoter implements ActionPromoter {
    @Override
    public List<AnAction> promote(@NotNull List<? extends AnAction> actions, @NotNull DataContext context) {
        ActionManager manager = ActionManager.getInstance();
        AnAction rename = null;
        AnAction doc = null;
        for (AnAction action : actions) {
            String id = manager.getId(action);
            if (IdeActions.ACTION_RENAME.equals(id)) {
                rename = action;
            } else if (IdeActions.ACTION_QUICK_JAVADOC.equals(id)) {
                doc = action;
            }
        }
        if (rename == null || doc == null) {
            return List.of();
        }
        return List.of(CommonDataKeys.EDITOR.getData(context) != null ? doc : rename);
    }
}
