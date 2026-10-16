package local.bootservers.was;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 프로젝트에 추가한 WAS 목록. 설치 경로는 PC 마다 다르므로 공유되지 않는 workspace.xml 에 둔다.
 */
@Service(Service.Level.PROJECT)
@State(name = "KingGuraWasServers", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class WasSettings implements PersistentStateComponent<WasSettings.StateBean> {

    /** WAS 종류. supported 가 false 인 종류는 고를 수만 있고 아직 실행하지 못한다. */
    public enum Kind {
        TOMCAT("Tomcat", true), WILDFLY("JBoss / WildFly", true), WEBLOGIC("WebLogic", true), JEUS("JEUS", true);

        public final String label;
        public final boolean supported;

        Kind(String label, boolean supported) {
            this.label = label;
            this.supported = supported;
        }
    }

    /** 올릴 프로젝트 하나. module 은 폴더 모듈 이름(Gradle 은 .main 을 뺀 이름). */
    public static final class Deployment {
        public String module = "";
        public String contextPath = "/";

        public Deployment() {
        }

        public Deployment(String module, String contextPath) {
            this.module = module;
            this.contextPath = contextPath;
        }
    }

    /** WAS 하나. 직렬화를 위해 공개 필드와 기본 생성자를 쓴다. */
    public static final class Server {
        public String id = UUID.randomUUID().toString();
        public String kind = Kind.TOMCAT.name();
        public String name = "";
        public String home = "";
        public int httpPort = 8090;
        public int shutdownPort = 8005;
        public int debugPort = 5005;
        public String vmOptions = "";
        public boolean reloadable = true;
        public List<Deployment> deployments = new ArrayList<>();
        // JEUS 전용. 비밀번호는 여기 두지 않고 IDE 비밀번호 저장소에 둔다.
        public String jeusDomain = "jeus_domain";
        public String jeusServer = "adminServer";
        public String jeusUser = "administrator";
        /** JEUS 명령 틀. 비어 있으면 {@link JeusBase} 의 기본 틀을 쓴다. */
        public String startCommand = "";
        public String stopCommand = "";
        public String deployCommand = "";

        public Kind kind() {
            try {
                return Kind.valueOf(kind);
            }
            catch (IllegalArgumentException e) {
                return Kind.TOMCAT;
            }
        }

        public Server copy() {
            Server s = new Server();
            s.id = id;
            s.kind = kind;
            s.name = name;
            s.home = home;
            s.httpPort = httpPort;
            s.shutdownPort = shutdownPort;
            s.debugPort = debugPort;
            s.vmOptions = vmOptions;
            s.reloadable = reloadable;
            s.jeusDomain = jeusDomain;
            s.jeusServer = jeusServer;
            s.jeusUser = jeusUser;
            s.startCommand = startCommand;
            s.stopCommand = stopCommand;
            s.deployCommand = deployCommand;
            for (Deployment d : deployments) {
                s.deployments.add(new Deployment(d.module, d.contextPath));
            }
            return s;
        }
    }

    public static final class StateBean {
        public List<Server> servers = new ArrayList<>();
    }

    private StateBean state = new StateBean();

    public static WasSettings getInstance(@NotNull Project project) {
        return project.getService(WasSettings.class);
    }

    @Override
    public StateBean getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull StateBean loaded) {
        state = loaded;
    }

    public List<Server> servers() {
        return state.servers;
    }

    /** 같은 id 가 있으면 바꾸고 없으면 덧붙인다. */
    public void put(Server server) {
        for (int i = 0; i < state.servers.size(); i++) {
            if (state.servers.get(i).id.equals(server.id)) {
                state.servers.set(i, server);
                return;
            }
        }
        state.servers.add(server);
    }

    public void remove(Server server) {
        state.servers.removeIf(s -> s.id.equals(server.id));
    }
}
