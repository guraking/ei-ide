package ei.db;

import java.util.UUID;

/**
 * 접속 하나. 설정 파일(ei-database.xml)에 그대로 저장되므로 공개 필드 + 기본 생성자 꼴을 지킨다.
 * 비밀번호는 여기 두지 않고 IDE 암호 저장소(Passwords)에 id 로 둔다.
 * service 는 Oracle 의 서비스 이름 또는 SID(sid=true), MySQL · PostgreSQL 은 데이터베이스 이름이다.
 */
public class DbConfig {
  public String id = UUID.randomUUID().toString();
  public String name = "";
  public DbKind kind = DbKind.ORACLE;
  public String host = "localhost";
  public int port = DbKind.ORACLE.defaultPort;
  public String service = "";
  public boolean sid;
  public String user = "";
  public String schema = "";
  /** 비면 Drivers 가 ~/.m2 에서 찾는다. */
  public String driverJar = "";
  public boolean readOnly;
  public boolean production;
  public boolean autoCommit;

  public String url() { return kind.url(this); }

  /** Oracle 은 스키마 이름이 대문자로 저장되므로 비교 · 조회용 이름을 맞춘다. 비면 사용자 이름. */
  public String effectiveSchema() {
    String s = schema == null || schema.isBlank() ? user : schema;
    return kind == DbKind.ORACLE ? s.toUpperCase() : s;
  }

  public DbConfig copy() {
    DbConfig c = new DbConfig();
    c.id = id; c.name = name; c.kind = kind; c.host = host; c.port = port; c.service = service; c.sid = sid;
    c.user = user; c.schema = schema; c.driverJar = driverJar; c.readOnly = readOnly; c.production = production;
    c.autoCommit = autoCommit;
    return c;
  }

  @Override public String toString() { return name; }
}
