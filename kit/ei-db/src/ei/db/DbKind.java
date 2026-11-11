package ei.db;

/**
 * 지원하는 DB 종류. 드라이버는 ~/.m2 의 groupPath/downloadArtifact 아래에서 찾는다(Drivers.findInMaven). artifactPrefix 는 화면 표시용.
 * oldGroupPath 는 예전 Maven 자리(ojdbc8 의 com/oracle/ojdbc, mysql-connector-java 의 mysql). 없으면 null.
 */
public enum DbKind {
  ORACLE("Oracle", 1521, "oracle.jdbc.OracleDriver", "com/oracle/database/jdbc", "ojdbc", "ojdbc11", "com/oracle/ojdbc"),
  MYSQL("MySQL", 3306, "com.mysql.cj.jdbc.Driver", "com/mysql", "mysql-connector-j", "mysql-connector-j", "mysql"),
  POSTGRES("PostgreSQL", 5432, "org.postgresql.Driver", "org/postgresql", "postgresql", "postgresql", null);

  public final String label;
  public final int defaultPort;
  public final String driverClass;
  public final String groupPath;
  public final String artifactPrefix;
  /** 없을 때 내려받을 Maven artifact(groupPath 아래). Oracle 은 JDK 11 이상용 ojdbc11. */
  public final String downloadArtifact;
  public final String oldGroupPath;

  DbKind(String label, int defaultPort, String driverClass, String groupPath, String artifactPrefix, String downloadArtifact,
         String oldGroupPath) {
    this.label = label;
    this.defaultPort = defaultPort;
    this.driverClass = driverClass;
    this.groupPath = groupPath;
    this.artifactPrefix = artifactPrefix;
    this.downloadArtifact = downloadArtifact;
    this.oldGroupPath = oldGroupPath;
  }

  public String url(DbConfig c) {
    return switch (this) {
      case ORACLE -> c.sid ? "jdbc:oracle:thin:@" + c.host + ":" + c.port + ":" + c.service
                           : "jdbc:oracle:thin:@//" + c.host + ":" + c.port + "/" + c.service;
      case MYSQL -> "jdbc:mysql://" + c.host + ":" + c.port + "/" + c.service;
      case POSTGRES -> "jdbc:postgresql://" + c.host + ":" + c.port + "/" + c.service;
    };
  }

  @Override public String toString() { return label; }
}
