package ei.db.mybatis;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.DbSettings;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.QueryResult;
import ei.db.SqlGuard;
import ei.db.console.ResultCompare;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 매퍼 SELECT 문장 하나를 고치기 전(VCS 의 마지막 커밋 글)과 지금 글로 각각 풀어 같은 값 · 같은 접속으로 돌리고 결과를 견준다.
 * 값 · 접속은 부르는 쪽이 주거나(실행 창의 지금 값), 주지 않으면 실행 창이 기억한 값(빈 값은 null) · 마지막 접속(없으면 하나뿐인 접속).
 * 다른 매퍼 파일의 조각(include)은 고치기 전 쪽은 그 파일의 고치기 전 글로 푼다. 찾지 못한 조각이 있으면 돌리지 않는다.
 * SELECT 가 아니거나 풀린 SQL 이 쓰는 문장 · 잠그는 SELECT(FOR UPDATE · FOR SHARE)면 돌리지 않는다. 두 SQL 이 같으면 DB 를 부르지 않는다.
 */
final class MapperCompare {
  private MapperCompare() {}

  /** 실행 중 취소를 살피는 간격. JDBC 실행은 막혀 있어 진행 표시가 스스로 취소를 알 수 없다. */
  private static final long CANCEL_POLL_MS = 200;
  /** 바뀐 파일 목록을 다시 볼 때 기다리는 한도(초). 넘으면 그때까지 아는 상태로 판단한다. */
  private static final long UPDATE_WAIT_SEC = 10;
  private static final String SELECT = "select";
  private static final String ID = "id";
  private static final String DATABASE_ID = "databaseId";
  private static final Pattern SPACES = Pattern.compile("\\s+");
  /** 행을 잠그는 SELECT. 비교는 결과만 보고 트랜잭션을 남기지 않아야 한다(Oracle · PostgreSQL 자동 커밋 꺼짐에서 잠금이 남음). */
  private static final Pattern LOCKING = Pattern.compile("(?i)\\bFOR\\s+(NO\\s+KEY\\s+)?UPDATE\\b|\\bFOR\\s+(KEY\\s+)?SHARE\\b");

  /** 결과. message 가 null 이 아니면 비교하지 못한 까닭(화면 글). sameSql 이면 고치기 전과 지금 SQL 이 같다. */
  record Outcome(String title, @Nullable QueryResult before, @Nullable QueryResult now, @Nullable String message, boolean sameSql) {
    boolean ok() { return message == null; }

    /** before · now 가 모두 있고 다른 행이 하나도 없으면 true(칼럼이 다르거나 한쪽이라도 건수 한도에서 잘렸으면 false). */
    boolean same() {
      return before != null && now != null && ResultCompare.sameRows(before, now);
    }

    /** 한쪽이라도 조회 건수 한도에서 잘려 앞부분만 읽었다. */
    boolean truncated() {
      return before != null && now != null && (before.truncated() || now.truncated());
    }

    static Outcome failed(String title, String message) { return new Outcome(title, null, null, message, false); }
  }

  /**
   * VCS 의 고치기 전(마지막 커밋) 글. 바뀐 것이 없거나 버전 관리 밖이면 null, 새 파일이면 "". 백그라운드에서 부른다.
   * 바뀐 파일 목록에 없으면 막 저장해 아직 못 본 것일 수 있어 그 파일만 다시 보게 하고 UPDATE_WAIT_SEC 까지 기다린다(취소하면 멈춤).
   */
  static @Nullable String beforeText(Project project, VirtualFile file) throws VcsException {
    if (!versioned(project, file)) return null;
    ChangeListManager clm = ChangeListManager.getInstance(project);
    if (clm.getChange(file) == null && !clm.isUnversioned(file) && !clm.isIgnoredFile(file)) {
      VcsDirtyScopeManager.getInstance(project).fileDirty(file);
      awaitUpdate(clm);
    }
    return knownBeforeText(project, file);
  }

  /**
   * 바뀐 파일 목록을 기다리지 않고 지금 아는 대로 본 고치기 전 글(beforeText 와 같은 뜻). 백그라운드에서 부른다.
   * 목록에 없는데 편집기에 저장하지 않은 글이 있으면 디스크 글(= 마지막 커밋 글)을 고치기 전 글로 쓴다.
   */
  static @Nullable String knownBeforeText(Project project, VirtualFile file) throws VcsException {
    if (!versioned(project, file)) return null;
    ChangeListManager clm = ChangeListManager.getInstance(project);
    Change change = clm.getChange(file);
    if (change == null) {
      if (clm.isUnversioned(file) || clm.isIgnoredFile(file)) return null;
      boolean unsaved = ReadAction.compute(() -> {
        Document d = FileDocumentManager.getInstance().getCachedDocument(file);
        return d != null && FileDocumentManager.getInstance().isDocumentUnsaved(d);
      });
      if (!unsaved) return null;
      try {
        return VfsUtilCore.loadText(file);
      } catch (IOException e) {
        throw new VcsException(e);
      }
    }
    ContentRevision before = change.getBeforeRevision();
    if (before == null) return "";
    String text = before.getContent();
    if (text == null) throw new VcsException(EiDbBundle.message("mappercompare.vcs.no.content"));
    return text;
  }

  /** 프로젝트의 바뀐 파일 목록을 모두 다시 보게 하고 UPDATE_WAIT_SEC 까지 기다린다(취소하면 멈춤). 백그라운드에서 부른다. */
  static void refreshChanges(Project project) {
    VcsDirtyScopeManager.getInstance(project).markEverythingDirty();
    awaitUpdate(ChangeListManager.getInstance(project));
  }

  private static void awaitUpdate(ChangeListManager clm) {
    CountDownLatch updated = new CountDownLatch(1);
    clm.invokeAfterUpdate(false, updated::countDown);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UPDATE_WAIT_SEC);
    try {
      while (!updated.await(CANCEL_POLL_MS, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline) {
        ProgressManager.checkCanceled();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 디스크의 파일이고 VCS 루트 안이면 true. 아니면 고치기 전 글이 없다. */
  private static boolean versioned(Project project, VirtualFile file) {
    return file.isInLocalFileSystem() && ProjectLevelVcsManager.getInstance(project).getVcsFor(file) != null;
  }

  /** beforeText 가 null 일 때 알릴 글: 버전 관리 밖이면 그 말, 아니면 고친 것이 없다는 말. 백그라운드에서 부른다. */
  static String noChangeMessage(Project project, VirtualFile file) {
    ChangeListManager clm = ChangeListManager.getInstance(project);
    FileStatus status = clm.getStatus(file);
    boolean outside = !versioned(project, file) || clm.isUnversioned(file) || clm.isIgnoredFile(file)
                      || status == FileStatus.UNKNOWN || status == FileStatus.IGNORED;
    return EiDbBundle.message(outside ? "mappercompare.unversioned" : "mappercompare.unchanged");
  }

  /** 백그라운드에서. 기억된 값 · 접속으로 돌린다(「바꾼 것 다시 시험」). */
  static Outcome run(Project project, String beforeText, CharSequence nowText, String statementId, ProgressIndicator indicator) {
    return run(project, beforeText, nowText, statementId, null, null, indicator);
  }

  /**
   * 백그라운드에서. statementId 의 고치기 전 · 지금 SQL 을 풀어 둘 다 돌린다.
   * given · values 가 null 이면 실행 창이 기억한 접속 · 값을 쓴다. values 에 없는 이름은 null 이다.
   */
  static Outcome run(Project project, String beforeText, CharSequence nowText, String statementId, @Nullable DbConfig given,
                     @Nullable Map<String, Object> values, ProgressIndicator indicator) {
    MapperDoc nowDoc = MapperDoc.parse(nowText);
    String title = nowDoc.shortNamespace() + "." + statementId;
    DbConfig c = given != null ? given : connection(project);
    if (c == null) return Outcome.failed(title, EiDbBundle.message("mappercompare.no.connection"));
    String databaseId = DynamicSql.databaseId(c.kind);
    MapperDoc.Node now = pick(nowDoc, statementId, databaseId);
    if (now == null) return Outcome.failed(title, EiDbBundle.message("mappercompare.no.statement.now"));
    if (!SELECT.equals(now.name)) return Outcome.failed(title, EiDbBundle.message("mappercompare.not.select"));
    MapperDoc beforeDoc = MapperDoc.parse(beforeText);
    MapperDoc.Node before = pick(beforeDoc, statementId, databaseId);
    if (before == null) return Outcome.failed(title, EiDbBundle.message("mappercompare.no.statement.before"));
    if (!SELECT.equals(before.name)) return Outcome.failed(title, EiDbBundle.message("mappercompare.not.select"));

    DtoFields.ForRun types = ReadAction.nonBlocking(() -> DtoFields.forRun(project, nowDoc, now))
        .inSmartMode(project).wrapProgress(indicator).executeSynchronously();
    Map<String, VirtualFile> otherFiles = new HashMap<>();
    Map<String, MapperDoc> beforeMappers = ReadAction.nonBlocking(() -> otherMappers(project, beforeDoc, before, otherFiles))
        .inSmartMode(project).wrapProgress(indicator).executeSynchronously();
    // 다른 매퍼 파일도 바뀌었으면 고치기 전 쪽은 그 파일의 고치기 전 조각으로 푼다. VCS 읽기라 읽기 접근 밖에서.
    for (Map.Entry<String, VirtualFile> e : otherFiles.entrySet()) {
      indicator.checkCanceled();
      String text;
      try {
        text = knownBeforeText(project, e.getValue());
      } catch (VcsException ex) {
        return Outcome.failed(title, EiDbBundle.message("mappercompare.vcs.failed", String.valueOf(ex.getMessage())));
      }
      if (text != null) beforeMappers.put(e.getKey(), text.isEmpty() ? null : MapperDoc.parse(text));
    }

    DynamicSql.Result nowSql = resolve(project, nowDoc, now, nowDoc, now, types, types.mappers()::get, values, databaseId);
    DynamicSql.Result beforeSql = resolve(project, nowDoc, now, beforeDoc, before, types, beforeMappers::get, values, databaseId);
    Set<String> missing = new LinkedHashSet<>(beforeSql.missingIncludes);
    missing.addAll(nowSql.missingIncludes);
    if (!missing.isEmpty()) return Outcome.failed(title, EiDbBundle.message("mappercompare.missing.include", String.join(", ", missing)));
    if (sameSql(beforeSql, nowSql)) return new Outcome(title, null, null, null, true);
    for (DynamicSql.Result r : List.of(beforeSql, nowSql)) {
      if (SqlGuard.isWrite(r.sql) || SqlGuard.hasSeveralStatements(r.sql, c.kind)) {
        return Outcome.failed(title, EiDbBundle.message("mappercompare.not.select"));
      }
      if (LOCKING.matcher(r.sql).find()) return Outcome.failed(title, EiDbBundle.message("mappercompare.locking"));
    }
    DbSessions sessions = DbSessions.get(project);
    try {
      indicator.setText2(EiDbBundle.message("mappercompare.running.before"));
      QueryResult b = query(sessions, c, beforeSql, indicator);
      indicator.setText2(EiDbBundle.message("mappercompare.running.now"));
      QueryResult n = query(sessions, c, nowSql, indicator);
      return new Outcome(title, b, n, null, false);
    } catch (DbSessions.CancelledException e) {
      return Outcome.failed(title, EiDbBundle.message("mappercompare.cancelled"));
    } catch (SQLException | RuntimeException e) {
      return Outcome.failed(title, EiDbBundle.message("mappercompare.failed", c.name, String.valueOf(e.getMessage())));
    } finally {
      sessions.endReadTransaction(c);
    }
  }

  /**
   * id 문장 가운데 databaseId 가 같은 것, 없으면 databaseId 가 없는 것(MyBatis 가 고르는 차례). id 가 비었거나 없으면 null.
   */
  static @Nullable MapperDoc.Node pick(MapperDoc doc, @Nullable String id, String databaseId) {
    if (id == null || id.isBlank()) return null;
    MapperDoc.Node plain = null;
    for (MapperDoc.Node s : doc.statements) {
      if (!id.equals(s.attr(ID))) continue;
      String db = s.attr(DATABASE_ID);
      if (db == null || db.isBlank()) {
        if (plain == null) plain = s;
      } else if (db.trim().equals(databaseId)) {
        return s;
      }
    }
    return plain;
  }

  /**
   * 고치기 전 문장이 include 하는 다른 매퍼 문서(지금 글)를 네임스페이스로 모은다. 자기 네임스페이스는 고치기 전 문서.
   * files 에는 읽은 다른 매퍼의 파일을 넣는다(그 파일의 고치기 전 글로 바꾸려고). 읽기 접근 안에서 부른다.
   */
  private static Map<String, MapperDoc> otherMappers(Project project, MapperDoc beforeDoc, MapperDoc.Node before, Map<String, VirtualFile> files) {
    Map<String, MapperDoc> out = new HashMap<>();
    if (beforeDoc.namespace != null) out.put(beforeDoc.namespace, beforeDoc);
    Function<String, MapperDoc> load = ns -> {
      if (out.containsKey(ns)) return out.get(ns);
      MapperDoc found = null;
      for (MapperIndex.Entry e : MapperIndex.find(project, ns)) {
        found = LayerCode.load(e.file());
        if (found != null) {
          files.put(ns, e.file());
          break;
        }
      }
      out.put(ns, found);
      return found;
    };
    // 이름을 모으며 include 를 모두(조건과 상관없이) 따라가므로 쓰일 수 있는 문서가 다 담긴다.
    DynamicSql.paramNames(beforeDoc, before, load);
    return out;
  }

  /** 값은 주어진 values, 없으면 지금 문장(keyDoc · keyStatement)에 기억된 것. doc 의 statement 를 mappers 조각으로 푼다. */
  private static DynamicSql.Result resolve(Project project, MapperDoc keyDoc, MapperDoc.Node keyStatement, MapperDoc doc, MapperDoc.Node statement,
                                           DtoFields.ForRun types, Function<String, MapperDoc> mappers, @Nullable Map<String, Object> values,
                                           String databaseId) {
    Map<String, Object> used = values != null ? values
        : MyBatisRunDialog.rememberedValues(project, keyDoc.namespace, keyStatement.attr(ID), DynamicSql.paramNames(doc, statement, mappers),
                                            DynamicSql.listNames(doc, statement, mappers), types.textParams());
    return DynamicSql.resolve(doc, statement, used, databaseId, mappers);
  }

  /** 실행 창이 마지막에 고른 접속, 없으면 접속이 하나뿐일 때 그것. 둘 다 아니면 null. */
  static @Nullable DbConfig connection(Project project) {
    DbSettings settings = DbSettings.getInstance();
    DbConfig last = settings.find(PropertiesComponent.getInstance(project).getValue(MyBatisRunDialog.CONNECTION_KEY, ""));
    if (last != null) return last;
    return settings.connections().size() == 1 ? settings.connections().get(0) : null;
  }

  /** 프로젝트 조회 건수까지 읽는다. 진행 막대의 취소는 실행 중에도 문장에 보낸다. */
  private static QueryResult query(DbSessions sessions, DbConfig c, DynamicSql.Result r, ProgressIndicator indicator) throws SQLException {
    AtomicReference<Statement> mine = new AtomicReference<>();
    ScheduledFuture<?> watch = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() -> {
      if (!indicator.isCanceled()) return;
      try {
        sessions.cancel(c.id, mine.get());
      } catch (SQLException ignored) {
        // 보내기에 실패하면 실행이 끝날 때까지 기다린다. 다음 간격에 다시 보낸다.
      }
    }, CANCEL_POLL_MS, CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
    try {
      return sessions.run(c, r.sql, new ArrayList<>(r.binds), sessions.maxRows(), indicator::isCanceled, mine, null);
    } finally {
      watch.cancel(false);
    }
  }

  /** 따옴표('...', "...") 밖의 공백(줄바꿈 · 들여쓰기) 차이만 있고 바인드 값이 같으면 같은 SQL 이다. */
  static boolean sameSql(DynamicSql.Result a, DynamicSql.Result b) {
    return squeeze(a.sql).equals(squeeze(b.sql)) && Objects.equals(a.binds, b.binds);
  }

  /** 따옴표 밖 공백 덩이를 한 칸으로 줄이고 앞뒤 공백을 뗀 글. 따옴표 안('' 이스케이프 포함)은 그대로 둔다. */
  static String squeeze(String sql) {
    StringBuilder out = new StringBuilder(sql.length());
    char quote = 0;
    boolean space = false;
    for (int i = 0; i < sql.length(); i++) {
      char ch = sql.charAt(i);
      if (quote == 0 && Character.isWhitespace(ch)) {
        space = true;
        continue;
      }
      if (space && !out.isEmpty()) out.append(' ');
      space = false;
      if (quote == 0 && (ch == '\'' || ch == '"')) quote = ch;
      else if (ch == quote) quote = 0;
      out.append(ch);
    }
    return out.toString();
  }

  /** 화면 스레드에서. 결과 비교 창(왼쪽 고치기 전, 오른쪽 지금)을 연다. 비교하지 못했으면 까닭을, SQL 이 같으면 그 말을 알린다. */
  static void show(Project project, Outcome outcome) {
    if (!outcome.ok()) {
      Notify.warning(project, outcome.title() + " · " + outcome.message());
    } else if (outcome.sameSql()) {
      Notify.info(project, EiDbBundle.message("mappercompare.same.sql", outcome.title()));
    } else {
      ResultCompare.show(project, outcome.before(), EiDbBundle.message("mappercompare.before"), outcome.now(), EiDbBundle.message("mappercompare.now"));
    }
  }
}
