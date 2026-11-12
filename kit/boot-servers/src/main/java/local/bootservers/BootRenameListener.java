package local.bootservers;

import com.intellij.execution.RunManagerListener;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.application.ApplicationConfiguration;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 실행 설정 이름이 어디서 바뀌든(IntelliJ 실행 설정 창 포함) 서버 묶음의 줄 키와 실행 기록을 새 이름으로 옮긴다.
 * 서버 창이 열려 있지 않아도 받도록 프로젝트 리스너로 둔다. 서버 창 「적용」 의 이름 바꾸기(RunManager.addConfiguration)도 이 알림으로 옮긴다.
 */
public final class BootRenameListener implements RunManagerListener {

    private final Project project;

    public BootRenameListener(Project project) {
        this.project = project;
    }

    @Override
    public void runConfigurationChanged(@NotNull RunnerAndConfigurationSettings settings, @Nullable String existingId) {
        if (existingId == null || !(settings.getConfiguration() instanceof ApplicationConfiguration)) {
            return;
        }
        String newName = settings.getName();
        String oldName = oldName(existingId, settings.getUniqueID(), newName);
        if (oldName == null) {
            return;
        }
        ServerRegistry.getInstance(project).rename(oldName, newName);
        ServerGroups.getInstance(project).renameMember(ServerGroups.BOOT_KEY_PREFIX + oldName, ServerGroups.BOOT_KEY_PREFIX + newName);
    }

    /**
     * 바뀌기 전 고유 id 에서 옛 이름을 꺼낸다. 고유 id 는 「종류 접두 + 이름」 꼴이라 지금 id 에서 이름을 뺀 접두로 자른다.
     * 이름이 그대로거나 꼴이 맞지 않으면(접두가 다름) null.
     */
    static @Nullable String oldName(String existingId, String uniqueId, String newName) {
        if (existingId.equals(uniqueId) || !uniqueId.endsWith(newName)) {
            return null;
        }
        String prefix = uniqueId.substring(0, uniqueId.length() - newName.length());
        if (!existingId.startsWith(prefix) || existingId.length() == prefix.length()) {
            return null;
        }
        return existingId.substring(prefix.length());
    }
}
