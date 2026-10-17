package ei.db.ui;

import ei.db.DbConfig;
import ei.db.DbKind;

import java.util.List;
import java.util.Set;

/** 접속 내보내기 · 가져오기 자체 점검: java -cp out:test-out ei.db.ui.ConnectionsJsonCheck */
public class ConnectionsJsonCheck {
  static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b); }

  static void fails(String json, String part) {
    try {
      ConnectionsJson.read(json);
    } catch (IllegalArgumentException e) {
      if (!e.getMessage().contains(part)) throw new AssertionError("문구 다름: " + e.getMessage());
      return;
    }
    throw new AssertionError("실패해야 함: " + json);
  }

  public static void main(String[] args) {
    DbConfig a = new DbConfig();
    a.name = "개발 \"주\"\\DB";
    a.kind = DbKind.POSTGRES;
    a.host = "10.0.0.1";
    a.port = 5433;
    a.service = "app";
    a.user = "scott";
    a.production = true;
    a.driverJar = "/home/me/.m2/postgresql.jar";
    String json = ConnectionsJson.write(List.of(a));
    if (json.contains(a.id)) throw new AssertionError("id 가 들어가면 안 됨");
    if (json.contains("driverJar")) throw new AssertionError("드라이버 jar 경로가 들어가면 안 됨");
    if (json.toLowerCase().contains("password")) throw new AssertionError("비밀번호 키가 들어가면 안 됨");

    List<DbConfig> back = ConnectionsJson.read("﻿" + json);
    eq(back.size(), 1);
    DbConfig b = back.get(0);
    eq(b.name, a.name);
    eq(b.kind, DbKind.POSTGRES);
    eq(b.port, 5433);
    eq(b.production, true);
    eq(b.readOnly, false);
    eq(b.driverJar, "");
    if (b.id.equals(a.id)) throw new AssertionError("가져온 접속은 새 id");
    eq(ConnectionsJson.write(List.of(b)), json);
    eq(ConnectionsJson.read(ConnectionsJson.write(List.of())).size(), 0);

    // 빠진 키는 기본값, 모르는 키는 무시.
    DbConfig c = ConnectionsJson.read("{\"connections\":[{\"name\":\"x\",\"etc\":[1,{\"y\":null}]}]}").get(0);
    eq(c.kind, DbKind.ORACLE);
    eq(c.port, DbKind.ORACLE.defaultPort);

    fails("{\"connections\":[{\"name\":\"x\",\"kind\":\"DB2\"}]}", "kind");
    fails("{\"connections\":[{\"name\":\"x\",\"port\":1.5}]}", "port");
    fails("{\"connections\":[{\"port\":1}]}", "이름");
    fails("{\"list\":[]}", "connections");
    fails("{\"connections\":[", "JSON");

    eq(ConnectionsJson.uniqueName("개발", Set.of("운영")), "개발");
    eq(ConnectionsJson.uniqueName("개발", Set.of("개발")), "개발 (가져옴)");
    eq(ConnectionsJson.uniqueName("개발", Set.of("개발", "개발 (가져옴)")), "개발 (가져옴 2)");
    System.out.println("ConnectionsJsonCheck 통과");
  }
}
