package ei.db.mybatis;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FileStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 매퍼 SELECT 문장 하나를 고치기 전(VCS 의 마지막 커밋 글)과 지금 글로 각각 풀어 같은 값 · 같은 접속으로 돌리고 결과를 견준다.
 * 값은 MyBatis 실행 창이 문장 · 파라미터마다 기억한 것(빈 값은 null), 접속은 그 창에서 마지막에 고른 것(없으면 하나뿐인 접속).
 * SELECT 가 아니거나 풀린 SQL 이 쓰는 문장이면 돌리지 않는다. 두 SQL 이 같으면 DB 를 부르지 않는다.
 */
final class MapperCompare {
  private MapperCompare() {}

  /** 실행 중 취소를 살피는 간격. JDBC 실행은 막혀 있어 진행 표시가 스스로 취소를 알 수 없다. */
  private static final long CANCEL_POLL_MS = 200;
  /** 바뀐 파일 목록을 다시 볼 때 기다리는 한도(초). 넘으면 그때까지 아는 상태로 판단한다. */
  private static final long UPDATE_WAIT_SEC = 10;
  private static final String SELECT = "select";
  private static final Pattern SPACES = Pattern.compile("\\s+");

  /** 결과. message 가 null 이 아니면 비교하지 못한 까닭(화면 글). sameSql 이면 고치기 전과 지금 SQL 이 같다. */
  record Outcome(String title, @Nullable QueryResult before, @Nullable QueryResult now, @Nullable String message, boolean sameSql) {
    boolean ok() { return message == null; }

    /** before · now 가 모두 있고 다른 행이 하나도 없으면 true(칼럼이 다르면 false). */
    boolean same() {
      return before != null && now != null && ResultCompare.sameRows(before, now);
    }

    static Outcome failed(String title, String message) { return new Outcome(title, null, null, message, false); }
  }

  /**
   * VCS 의 고치기 전(마지막 커밋) 글. 바뀐 것이 없거나 버전 관리 밖이면 null, 새 파일이면 "". 백그라운드에서 부른다.
   * 바뀐 파일 목록에 없으면 막 저장해 아직 못 본 것일 수 있어 그 파일만 다시 보게 하고 UPDATE_WAIT_SEC 까지 기다린다.
   * 그래도 없는데 편집기에 저장하지 않은 글이 있으면 디스크 글(= 마지막 커밋 글)을 고치기 전 글로 쓴다.
   */
  static @Nullable String beforeText(Project project, VirtualFile file) throws VcsException {
    ChangeListManager clm = ChangeListManager.getInstance(project);
    Change change = clm.getChange(file);
    if (change == null && !clm.isUnversioned(file) && !clm.isIgnoredFile(file)) {
      VcsDirtyScopeManager.getInstance(project).fileDirty(file);
      CountDownLatch updated = new CountDownLatch(1);
      clm.invokeAfterUpdate(false, updated::countDown);
      try {
        updated.await(UPDATE_WAIT_SEC, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
      change = clm.getChange(file);
      if (change == null && ReadAction.compute(() -> {
        Document d = FileDocumentManager.getInstance().getCachedDocument(file);
        return d != null && FileDocumentManager.getInstance().isDocumentUnsaved(d);
      })) {
        try {
          return VfsUtilCore.loadText(file);
        } catch (IOException e) {
          throw new VcsException(e);
        }
      }
    }
    if (change == null) return null;
    ContentRevision before = change.getBeforeRevision();
    if (before == null) return "";
    String text = before.getContent();
    return text == null ? "" : text;
  }

  /** beforeText 가 null 일 때 알릴 글: 버전 관리 밖이면 그 말, 아니면 고친 것이 없다는 말. 백그라운드에서 부른다. */
  static String noChangeMessage(Project project, VirtualFile file) {
    ChangeListManager clm = ChangeListManager.getInstance(project);
    FileStatus status = clm.getStatus(file);
    boolean outside = clm.isUnversioned(file) || clm.isIgnoredFile(file) || status == FileStatus.UNKNOWN || status == FileStatus.IGNORED;
    return EiDbBundle.message(outside ? "mappercompare.unversioned" : "mappercompare.unchanged");
  }

  /** 백그라운드에서. statementId 의 고치기 전 · 지금 SQL 을 기억된 값 · 접속으로 풀어 둘 다 돌린다. */
  static Outcome run(Project project, VirtualFile file, String beforeText, CharSequence nowText, String statementId, ProgressIndicator indicator) {
    MapperDoc nowDoc = MapperDoc.parse(nowText);
    String title = nowDoc.shortNamespace() + "." + statementId;
    MapperDoc.Node now = nowDoc.statement(statementId);
    if (now == null) return Outcome.failed(title, EiDbBundle.message("mappercompare.no.statement.now"));
    if (!SELECT.equals(now.name)) return Outcome.failed(title, EiDbBundle.message("mappercompare.not.select"));
    MapperDoc beforeDoc = MapperDoc.parse(beforeText);
    MapperDoc.Node before = beforeDoc.statement(statementId);
    if (before == null) return Outcome.failed(title, EiDbBundle.message("mappercompare.no.statement.before"));
    if (!SELECT.equals(before.name)) return Outcome.failed(title, EiDbBundle.message("mappercompare.not.select"));
    DbConfig c = connection(project);
    if (c == null) return Outcome.failed(title, EiDbBundle.message("mappercompare.no.connection"));

    DtoFields.ForRun types = ReadAction.nonBlocking(() -> DtoFields.forRun(project, nowDoc, now)).wrapProgress(indicator).executeSynchronously();
    String databaseId = DynamicSql.databaseId(c.kind);
    DynamicSql.Result nowSql = resolve(project, nowDoc, now, nowDoc, types, databaseId);
    DynamicSql.Result beforeSql = resolve(project, nowDoc, now, beforeDoc, before, types, databaseId);
    if (sameSql(beforeSql, nowSql)) return new Outcome(title, null, null, null, true);
    for (DynamicSql.Result r : List.of(beforeSql, nowSql)) {
      if (SqlGuard.isWrite(r.sql) || SqlGuard.hasSeveralStatements(r.sql, c.kind)) {
        return Outcome.failed(title, EiDbBundle.message("mappercompare.not.select"));
      }
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

  private static DynamicSql.Result resolve(Project project, MapperDoc keyDoc, MapperDoc.Node keyStatement, MapperDoc doc,
                                           DtoFields.ForRun types, String databaseId) {
    return resolve(project, keyDoc, keyStatement, doc, keyStatement, types, databaseId);
  }

  /** 값은 지금 문장(keyDoc · keyStatement)에 기억된 것을 쓰고, doc 의 statement 를 푼다. 같은 파일 조각은 doc 의 것, 다른 매퍼 조각은 types.mappers. */
  private static DynamicSql.Result resolve(Project project, MapperDoc keyDoc, MapperDoc.Node keyStatement, MapperDoc doc, MapperDoc.Node statement,
                                           DtoFields.ForRun types, String databaseId) {
    Map<String, MapperDoc> mappers = types.mappers();
    Map<String, Object> values = MyBatisRunDialog.rememberedValues(project, keyDoc.namespace, keyStatement.attr("id"),
        DynamicSql.paramNames(doc, statement, mappers::get), DynamicSql.listNames(doc, statement, mappers::get), types.textParams());
    return DynamicSql.resolve(doc, statement, values, databaseId, mappers::get);
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

  /** 공백(줄바꿈 · 들여쓰기) 차이만 있고 바인드 값이 같으면 같은 SQL 이다. */
  static boolean sameSql(DynamicSql.Result a, DynamicSql.Result b) {
    return SPACES.matcher(a.sql.strip()).replaceAll(" ").equals(SPACES.matcher(b.sql.strip()).replaceAll(" ")) && Objects.equals(a.binds, b.binds);
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
