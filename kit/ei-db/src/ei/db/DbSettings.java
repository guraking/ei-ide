package ei.db;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 접속 목록과 공통코드 표 지정. 모든 프로젝트가 같이 쓴다. */
@Service(Service.Level.APP)
@State(name = "EiDatabase", storages = @Storage("ei-database.xml"))
public final class DbSettings implements PersistentStateComponent<DbSettings.StateBean> {

  public static class StateBean {
    public List<DbConfig> connections = new ArrayList<>();
    /** 공통코드 풀이에 쓰는 표 · 칼럼(예: CODE_DETAIL, GROUP_CD, CODE, CODE_NAME). 비면 풀이하지 않는다. */
    public String codeTable = "";
    public String codeGroupColumn = "";
    public String codeColumn = "";
    public String codeNameColumn = "";
  }

  private StateBean state = new StateBean();

  public static DbSettings getInstance() {
    return ApplicationManager.getApplication().getService(DbSettings.class);
  }

  @Override public @NotNull StateBean getState() { return state; }

  @Override public void loadState(@NotNull StateBean s) { state = s; }

  public List<DbConfig> connections() { return state.connections; }

  public DbConfig find(String id) {
    for (DbConfig c : state.connections) {
      if (c.id.equals(id)) return c;
    }
    return null;
  }

  /** 같은 id 가 있으면 바꾸고 없으면 끝에 더한다. */
  public void put(DbConfig config) {
    for (int i = 0; i < state.connections.size(); i++) {
      if (state.connections.get(i).id.equals(config.id)) {
        state.connections.set(i, config);
        return;
      }
    }
    state.connections.add(config);
  }

  public void remove(String id) {
    state.connections.removeIf(c -> c.id.equals(id));
  }
}
