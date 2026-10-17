package ei.db;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 접속 목록과 공통코드 표 지정. 모든 프로젝트가 같이 쓴다. */
@Service(Service.Level.APP)
@State(name = "EiDatabase", storages = @Storage("ei-database.xml"))
public final class DbSettings implements PersistentStateComponent<DbSettings.StateBean> {

  public static class StateBean {
    /** 고치지 말고 DbSettings.put · remove 로 바꿔 끼운다. */
    public volatile List<DbConfig> connections = new ArrayList<>();
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

  /**
   * 읽기 전용 목록. 여러 스레드가 읽으므로 put · remove 는 목록을 고치지 않고 새 목록으로 바꿔 끼운다
   * (이미 받은 목록을 도는 중에 ConcurrentModificationException 이 나지 않는다).
   */
  public List<DbConfig> connections() { return Collections.unmodifiableList(state.connections); }

  public DbConfig find(String id) {
    for (DbConfig c : state.connections) {
      if (c.id.equals(id)) return c;
    }
    return null;
  }

  /** 같은 id 가 있으면 바꾸고 없으면 끝에 더한다. */
  public synchronized void put(DbConfig config) {
    List<DbConfig> next = new ArrayList<>(state.connections);
    int at = indexOf(next, config.id);
    if (at >= 0) next.set(at, config); else next.add(config);
    state.connections = next;
  }

  public synchronized void remove(String id) {
    List<DbConfig> next = new ArrayList<>(state.connections);
    next.removeIf(c -> c.id.equals(id));
    state.connections = next;
  }

  private static int indexOf(List<DbConfig> list, String id) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).id.equals(id)) return i;
    }
    return -1;
  }
}
