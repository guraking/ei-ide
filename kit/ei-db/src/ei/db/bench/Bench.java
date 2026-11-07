package ei.db.bench;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.Meta;
import ei.db.console.ConsoleRun;
import ei.db.console.SqlParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 작업대 탭들이 함께 쓰는 것: 지금 접속, 연결 모양 · 값 자리 선택, 아래 미리 보기로 SQL 보내기, 표 · 칼럼 · 외래 키 읽기.
 * 읽기는 풀 스레드에서 하고 결과는 화면 스레드에서 넘긴다(작업대가 모달이 아니어도 ModalityState.any 로 바로 돈다).
 * 접속이 바뀌면 읽어 둔 것을 비운다. 실패는 onError 로 한국어 글을 넘긴다.
 */
final class Bench {
  /** 미리 보기 받는 곳: SQL 과 아래에 보일 알림 줄들. */
  interface Sink { void show(String sql, List<String> notes); }

  final Project project;
  private final Sink sink;
  private final Consumer<String> onError;
  private DbConfig config;
  private QueryBuilder.JoinStyle style = QueryBuilder.JoinStyle.OLD;
  private QueryBuilder.Bind bind = QueryBuilder.Bind.COLON;
  private List<Meta.Table> tables;
  private final Map<String, List<Meta.Column>> columns = new ConcurrentHashMap<>();
  private final Map<String, List<Meta.ForeignKey>> fks = new ConcurrentHashMap<>();
  private final List<Runnable> listeners = new ArrayList<>();
  private Runnable beforeReplace = () -> { };
  /** 접속이 바뀔 때마다 오른다. 읽기가 끝났을 때 값이 다르면 그 결과는 버린다. */
  private int generation;

  Bench(Project project, Sink sink, Consumer<String> onError) {
    this.project = project;
    this.sink = sink;
    this.onError = onError;
  }

  DbConfig config() { return config; }

  DbKind kind() { return config == null ? DbKind.ORACLE : config.kind; }

  QueryBuilder.JoinStyle style() { return kind() == DbKind.ORACLE ? style : QueryBuilder.JoinStyle.ANSI; }

  QueryBuilder.Bind bind() { return bind; }

  String schema() { return config == null ? null : Meta.schemaOf(config); }

  /** 접속 · 모양이 바뀌면 부를 것(탭들이 미리 보기를 다시 만든다). */
  void onChange(Runnable r) { listeners.add(r); }

  void setConfig(DbConfig c) {
    config = c;
    generation++;
    tables = null;
    columns.clear();
    fks.clear();
    fire();
  }

  void setStyle(QueryBuilder.JoinStyle s) {
    style = s;
    fire();
  }

  void setBind(QueryBuilder.Bind b) {
    bind = b;
    fire();
  }

  private void fire() {
    for (Runnable r : listeners) r.run();
  }

  void show(String sql, List<String> notes) { sink.show(sql, notes); }

  /** 도구가 SQL 칸의 문장을 바꿔 놓을 때 부른다. 바꾸기 전 문장을 「되돌리기」 로 돌아갈 수 있게 남긴다. */
  void replace(String sql, List<String> notes) {
    beforeReplace.run();
    sink.show(sql, notes);
  }

  void onReplace(Runnable r) { beforeReplace = r; }

  /** 기본 스키마의 표 · 뷰. 읽어 둔 것이 있으면 바로, 없으면 읽은 뒤 화면 스레드에서. */
  void tables(Consumer<List<Meta.Table>> done) {
    if (tables != null) {
      done.accept(tables);
      return;
    }
    DbConfig c = config;
    if (c == null) return;
    load(() -> Meta.tables(DbSessions.get(project).connection(c), c, Meta.schemaOf(c), new String[]{"TABLE", "VIEW"}), list -> {
      tables = list;
      done.accept(list);
    });
  }

  /** 표의 칼럼(이름은 메타 그대로). */
  void columns(String table, Consumer<List<Meta.Column>> done) {
    String key = JoinAdvisor.key(table);
    List<Meta.Column> have = columns.get(key);
    if (have != null) {
      done.accept(have);
      return;
    }
    DbConfig c = config;
    if (c == null) return;
    load(() -> Meta.columns(DbSessions.get(project).connection(c), c, Meta.schemaOf(c), table), list -> {
      columns.put(key, list);
      done.accept(list);
    });
  }

  /** 표의 외래 키(그 표가 다른 표를 가리키는 것). */
  void foreignKeys(String table, Consumer<List<Meta.ForeignKey>> done) {
    String key = JoinAdvisor.key(table);
    List<Meta.ForeignKey> have = fks.get(key);
    if (have != null) {
      done.accept(have);
      return;
    }
    DbConfig c = config;
    if (c == null) return;
    load(() -> Meta.importedKeys(DbSessions.get(project).connection(c), c, Meta.schemaOf(c), table), list -> {
      fks.put(key, list);
      done.accept(list);
    });
  }

  /** 읽어 둔 칼럼(없으면 빈 목록). */
  List<Meta.Column> cachedColumns(String table) {
    return columns.getOrDefault(JoinAdvisor.key(table), List.of());
  }

  List<Meta.ForeignKey> cachedForeignKeys(String table) {
    return fks.getOrDefault(JoinAdvisor.key(table), List.of());
  }

  /** SELECT 의 건수를 센다. 값 자리가 있으면 값을 묻는다(취소하면 아무것도 안 함). 결과 · 거절 글은 done 으로 넘긴다. */
  void count(String select, Consumer<String> done) {
    DbConfig c = config;
    if (c == null) {
      done.accept("접속을 먼저 고르세요.");
      return;
    }
    String sql;
    try {
      sql = SqlTools.countOf(select);
    } catch (IllegalArgumentException e) {
      done.accept(e.getMessage());
      return;
    }
    SqlParams.Bound b = ConsoleRun.bind(project, sql);
    if (b == null) return;
    load(() -> DbSessions.get(project).run(c, b.sql(), b.binds()), r -> {
      Object v = r.rows().isEmpty() || r.rows().get(0).isEmpty() ? null : r.rows().get(0).get(0);
      if (v == null) {
        done.accept("건수를 읽지 못했습니다.");
        return;
      }
      try {
        done.accept(String.format(java.util.Locale.ROOT, "%,d건", new java.math.BigDecimal(v.toString().strip()).longValue()));
      } catch (NumberFormatException e) {
        done.accept("건수를 읽지 못했습니다: " + v);
      }
    });
  }

  interface Reader<T> { T read() throws Exception; }

  /** 풀 스레드에서 읽고, 접속이 그대로면 화면 스레드에서 done. 실패는 onError. */
  <T> void load(Reader<T> reader, Consumer<T> done) {
    int gen = generation;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        T value = reader.read();
        ApplicationManager.getApplication().invokeLater(() -> {
          if (gen == generation && !project.isDisposed()) done.accept(value);
        }, ModalityState.any());
      } catch (Exception e) {
        String message = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
        ApplicationManager.getApplication().invokeLater(() -> onError.accept(message), ModalityState.any());
      }
    });
  }
}
