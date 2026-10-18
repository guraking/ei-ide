package local.bootservers;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/** 프로젝트별 최근 HTTP 요청. 메모리에만 두며 IDE 를 끄면 사라진다(헤더의 인증 값을 디스크에 남기지 않는다). */
@Service(Service.Level.PROJECT)
public final class HttpHistory {

    static final int MAX = 20;

    /** 보낸 요청 한 건. */
    record Request(String method, String url, String headers, String body) {
        @Override
        public String toString() {
            return method + " " + url;
        }
    }

    private final LinkedList<Request> requests = new LinkedList<>();

    static HttpHistory getInstance(@NotNull Project project) {
        return project.getService(HttpHistory.class);
    }

    /** 맨 앞에 넣는다. 같은 요청이 있으면 앞으로 옮기고, MAX 를 넘으면 오래된 것을 버린다. */
    synchronized void add(Request request) {
        requests.remove(request);
        requests.addFirst(request);
        while (requests.size() > MAX) {
            requests.removeLast();
        }
    }

    /** 최근 것부터. */
    synchronized List<Request> list() {
        return new ArrayList<>(requests);
    }
}
