package ei.db.console;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import ei.db.DbConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「앞 계획과 비교」가 쓰는 본 계획들. 프로젝트마다 메모리에만 둔다(IDE 를 닫으면 사라진다).
 * 접속마다 최근 MAX_PER_CONNECTION 개를 새것부터 두고, 「기준으로 삼기」로 찍은 계획 하나는 밀려나지 않게 따로 둔다. 화면 스레드에서만 쓴다.
 */
final class PlanHistory {
  /** 접속마다 기억하는 최근 계획 수. */
  static final int MAX_PER_CONNECTION = 10;
  private static final Key<PlanHistory> KEY = Key.create("ei.db.console.PlanHistory");

  /** 본 계획 하나. at 은 본 시각(ms). */
  record Entry(ExplainPlan.Plan plan, String connectionId, String connectionName, long at) {}

  private final Map<String, Deque<Entry>> recent = new HashMap<>();
  private Entry baseline;

  static PlanHistory get(Project project) {
    PlanHistory h = project.getUserData(KEY);
    if (h == null) {
      h = new PlanHistory();
      project.putUserData(KEY, h);
    }
    return h;
  }

  /** 방금 본 계획을 기억하고 그 항목을 돌려준다. */
  Entry add(DbConfig c, ExplainPlan.Plan plan) {
    Entry e = new Entry(plan, c.id, c.name, System.currentTimeMillis());
    Deque<Entry> list = recent.computeIfAbsent(c.id, k -> new ArrayDeque<>());
    list.addFirst(e);
    while (list.size() > MAX_PER_CONNECTION) list.removeLast();
    return e;
  }

  void pin(Entry e) { baseline = e; }

  Entry baseline() { return baseline; }

  /** current 와 비교할 수 있는 계획: 기준(있으면 맨 앞), 그다음 같은 접속의 최근 계획(새것부터). current 자신은 뺀다. */
  List<Entry> candidates(Entry current) {
    List<Entry> out = new ArrayList<>();
    if (baseline != null && baseline != current) out.add(baseline);
    for (Entry e : recent.getOrDefault(current.connectionId(), new ArrayDeque<>())) {
      if (e != current && e != baseline) out.add(e);
    }
    return out;
  }
}
