package local.bootservers;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 서버 창의 서버 묶음(이름 + 시작 순서). WAS 목록처럼 공유되지 않는 workspace.xml 에 둔다.
 * 묶음 안 서버는 줄 키로 적는다: 스프링 부트는 「boot:실행 설정 이름」, WAS 는 「was:id」.
 */
@Service(Service.Level.PROJECT)
@State(name = "KingGuraServerGroups", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ServerGroups implements PersistentStateComponent<ServerGroups.StateBean> {

    /** 스프링 부트 서버의 줄 키 앞머리. 뒤에 실행 설정 이름이 붙는다. */
    static final String BOOT_KEY_PREFIX = "boot:";

    /** 직렬화를 위해 공개 필드와 기본 생성자를 쓴다. */
    public static final class Group {
        public String name = "";
        public List<String> members = new ArrayList<>();
    }

    public static final class StateBean {
        public List<Group> groups = new ArrayList<>();
    }

    private StateBean state = new StateBean();

    public static ServerGroups getInstance(@NotNull Project project) {
        return project.getService(ServerGroups.class);
    }

    @Override
    public StateBean getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull StateBean loaded) {
        state = loaded;
    }

    public List<Group> groups() {
        return state.groups;
    }

    /** 줄 키가 바뀌면(실행 설정 이름 바꾸기) 모든 묶음의 그 키를 새 키로 바꾼다. 순서는 그대로 둔다. */
    public void renameMember(String oldKey, String newKey) {
        for (Group group : state.groups) {
            for (int i = 0; i < group.members.size(); i++) {
                if (group.members.get(i).equals(oldKey)) {
                    group.members.set(i, newKey);
                }
            }
        }
    }
}
