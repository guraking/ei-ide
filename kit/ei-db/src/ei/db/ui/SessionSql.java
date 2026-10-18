package ei.db.ui;

import ei.db.DbKind;

/**
 * 접속 우클릭 「세션 보기」 · 「잠금 보기」 쿼리. 순수 자바, 바인드 없음.
 * Oracle 은 V$SESSION · V$LOCKED_OBJECT 조회 권한(SELECT ANY DICTIONARY 또는 V_$ 뷰 권한)이 있어야 한다. 없으면 결과 창에 ORA-00942 가 보인다.
 * 잠금 보기는 누가 어떤 표를 잡고 있는지와, 끝내야 할 때 쓸 문장(KILL_SQL)을 함께 보인다. 끝내기는 사람이 콘솔에서 직접 실행한다.
 */
final class SessionSql {
  private SessionSql() {}

  private static final String ORACLE_SESSIONS = """
      SELECT
             s.SID
           , s.SERIAL#
           , s.USERNAME
           , s.STATUS
           , s.OSUSER
           , s.MACHINE
           , s.PROGRAM
           , s.LOGON_TIME
           , s.LAST_CALL_ET
           , s.BLOCKING_SESSION
           , s.EVENT
        FROM V$SESSION s
       WHERE s.TYPE = 'USER'
       ORDER BY s.STATUS, s.USERNAME, s.SID""";

  private static final String ORACLE_LOCKS = """
      SELECT
             s.SID
           , s.SERIAL#
           , s.USERNAME
           , s.OSUSER
           , s.MACHINE
           , s.PROGRAM
           , s.STATUS
           , o.OWNER
           , o.OBJECT_NAME
           , DECODE(lo.LOCKED_MODE, 2, 'ROW SHARE', 3, 'ROW EXCLUSIVE', 4, 'SHARE', 5, 'SHARE ROW EXCLUSIVE', 6, 'EXCLUSIVE', TO_CHAR(lo.LOCKED_MODE)) AS LOCKED_MODE
           , s.LAST_CALL_ET
           , s.BLOCKING_SESSION
           , 'ALTER SYSTEM KILL SESSION ''' || s.SID || ',' || s.SERIAL# || ''' IMMEDIATE' AS KILL_SQL
        FROM V$LOCKED_OBJECT lo, ALL_OBJECTS o, V$SESSION s
       WHERE lo.OBJECT_ID = o.OBJECT_ID
         AND lo.SESSION_ID = s.SID
       ORDER BY o.OWNER, o.OBJECT_NAME, s.SID""";

  private static final String POSTGRES_SESSIONS = """
      SELECT
             a.pid
           , a.usename
           , a.datname
           , a.state
           , a.client_addr
           , a.application_name
           , a.backend_start
           , a.xact_start
           , a.query_start
           , pg_blocking_pids(a.pid) AS blocked_by
           , a.query
        FROM pg_stat_activity a
       WHERE a.backend_type = 'client backend'
       ORDER BY a.state, a.usename, a.pid""";

  private static final String POSTGRES_LOCKS = """
      SELECT
             a.pid
           , a.usename
           , a.state
           , a.application_name
           , n.nspname AS schema_name
           , c.relname AS table_name
           , l.mode
           , l.granted
           , a.xact_start
           , pg_blocking_pids(a.pid) AS blocked_by
           , 'SELECT pg_terminate_backend(' || a.pid || ')' AS kill_sql
        FROM pg_locks l, pg_class c, pg_namespace n, pg_stat_activity a
       WHERE l.locktype = 'relation'
         AND l.relation = c.oid
         AND c.relnamespace = n.oid
         AND l.pid = a.pid
         AND n.nspname NOT IN ('pg_catalog', 'information_schema')
       ORDER BY n.nspname, c.relname, a.pid""";

  private static final String MYSQL_SESSIONS = """
      SELECT
             p.ID
           , p.USER
           , p.HOST
           , p.DB
           , p.COMMAND
           , p.TIME
           , p.STATE
           , p.INFO
        FROM information_schema.PROCESSLIST p
       ORDER BY p.COMMAND, p.USER, p.ID""";

  /** InnoDB 는 행 잠금을 표 단위로 보이지 않으므로 커밋하지 않은 트랜잭션(잠금을 쥔 쪽)을 보인다. */
  private static final String MYSQL_LOCKS = """
      SELECT
             t.trx_mysql_thread_id AS THREAD_ID
           , p.USER
           , p.HOST
           , p.DB
           , t.trx_state
           , t.trx_started
           , t.trx_rows_locked
           , t.trx_tables_locked
           , t.trx_query
           , CONCAT('KILL ', t.trx_mysql_thread_id) AS KILL_SQL
        FROM information_schema.INNODB_TRX t, information_schema.PROCESSLIST p
       WHERE p.ID = t.trx_mysql_thread_id
       ORDER BY t.trx_started""";

  static String sessions(DbKind kind) {
    return switch (kind) {
      case ORACLE -> ORACLE_SESSIONS;
      case POSTGRES -> POSTGRES_SESSIONS;
      case MYSQL -> MYSQL_SESSIONS;
    };
  }

  static String locks(DbKind kind) {
    return switch (kind) {
      case ORACLE -> ORACLE_LOCKS;
      case POSTGRES -> POSTGRES_LOCKS;
      case MYSQL -> MYSQL_LOCKS;
    };
  }
}
