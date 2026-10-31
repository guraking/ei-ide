package local.bootservers;

import java.util.List;
import java.util.Set;

/** Links · ConfigKeys.entries 자체 점검. 실패하면 예외로 끝난다. */
public final class LinksCheck {

    public static void main(String[] args) {
        entries();
        profiles();
        jdbc();
        redisAndHttp();
        placeholders();
        System.out.println("LinksCheck 통과");
    }

    private static void entries() {
        String yml = """
                server:
                  port: 8081 # 주석
                spring:
                  cloud:
                    gateway:
                      routes:
                        - id: api
                          uri: http://localhost:8081
                  text: |
                    url: http://no
                ---
                spring.config.activate.on-profile: dev
                server.port: 9000
                """;
        List<ConfigKeys.Entry> e = ConfigKeys.entries(yml, true);
        check(e.size() == 5, "값 5개: " + e);
        check(e.get(0).key().equals("server.port") && e.get(0).value().equals("8081"), "server.port");
        check(e.get(2).key().equals("spring.cloud.gateway.routes.uri"), "목록 안 키: " + e.get(2));
        check(yml.startsWith("port", e.get(0).offset() - 0) || yml.substring(e.get(0).offset()).startsWith("port"), "위치");
        check(e.get(3).document() == 1 && e.get(4).document() == 1, "둘째 문서");
        List<ConfigKeys.Entry> p = ConfigKeys.entries("# c\nserver.port=8080\nspring.datasource.url = jdbc:oracle:thin:@db:1521:ORCL\n", false);
        check(p.size() == 2 && p.get(1).value().equals("jdbc:oracle:thin:@db:1521:ORCL"), "properties: " + p);
    }

    private static void profiles() {
        Links.Source base = source("application.yml", "spring:\n  profiles:\n    active: local\nserver:\n  port: 8080\n");
        Links.Source local = source("application-local.yml", "server:\n  port: 8081\n");
        Links.Source dev = source("application-dev.yml", "server:\n  port: 9090\n");
        List<Links.Source> all = List.of(base, local, dev);
        check(Links.profiles("", all).equals(Set.of("local")), "설정의 active");
        check(Links.profiles("dev", all).equals(Set.of("dev")), "VM 이 이김");
        check(Links.port(all, Set.of("local")) == 8081, "프로필 파일이 덮음");
        check(Links.port(all, Set.of("default")) == 8080, "기본");
        check(Links.profileNames(all).equals(Set.of("local", "dev")), "프로필 이름들");
        Links.Source docs = source("application.yml", "server.port: 1\n---\nspring.config.activate.on-profile: local\nserver.port: 2\n---\nspring.profiles: \"!local\"\nserver.port: 3\n");
        check(Links.port(List.of(docs), Set.of("local")) == 2, "문서 프로필 맞음");
        check(Links.port(List.of(docs), Set.of("dev")) == 3, "! 프로필");
        check(Links.matches("local & !dev", Set.of("local")) && !Links.matches("local & !dev", Set.of("local", "dev")), "& · !");
        check(Links.matches("(dev | qa) & eu", Set.of("qa", "eu")) && !Links.matches("(dev | qa) & eu", Set.of("qa")), "괄호 · |");
        check(Links.matches("x, prod | dev", Set.of("dev")) && !Links.matches("dev & (qa", Set.of("dev", "qa")), "쉼표 · 깨진 식");
        check(Links.profiles("", List.of(source("application.yml", "spring:\n  profiles:\n    default: local\n"))).equals(Set.of("local")), "spring.profiles.default");
    }

    private static void jdbc() {
        expect("jdbc:oracle:thin:@dev-db:1521/DXPLM", Links.Kind.DATABASE, "Oracle · DXPLM", "dev-db:1521");
        expect("jdbc:oracle:thin:@//dev-db/SVC", Links.Kind.DATABASE, "Oracle · SVC", "dev-db:1521");
        expect("jdbc:oracle:thin:@db1:1522:ORCL", Links.Kind.DATABASE, "Oracle · ORCL", "db1:1522");
        expect("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=tns-db)(PORT=1530))(CONNECT_DATA=(SERVICE_NAME=PLM)))",
                Links.Kind.DATABASE, "Oracle · PLM", "tns-db:1530");
        expect("jdbc:log4jdbc:mysql://my:3307/shop?useSSL=false", Links.Kind.DATABASE, "MySQL · shop", "my:3307");
        expect("jdbc:postgresql://pg/app", Links.Kind.DATABASE, "PostgreSQL · app", "pg:5432");
        expect("jdbc:sqlserver://ms:1434;databaseName=erp;encrypt=false", Links.Kind.DATABASE, "SQL Server · erp", "ms:1434");
        expect("jdbc:tibero:thin:@tb:8629:tibero", Links.Kind.DATABASE, "Tibero · tibero", "tb:8629");
        check(targets("spring.datasource.url: jdbc:h2:mem:test\n").isEmpty(), "메모리 H2 는 뺌");
    }

    private static void redisAndHttp() {
        List<Links.Target> t = targets("""
                spring:
                  data:
                    redis:
                      host: cache
                      port: 6380
                app:
                  mail:
                    base-url: http://10.1.2.30:8095/send
                  pay-url: https://pay.example.com
                  lb: lb://auth
                """);
        check(t.size() == 3, "셋: " + t);
        check(t.get(0).kind() == Links.Kind.REDIS && t.get(0).address().equals("cache:6380"), "redis host · port");
        check(t.get(1).kind() == Links.Kind.HTTP && t.get(1).address().equals("10.1.2.30:8095") && t.get(1).title().equals("HTTP · app.mail"), "http: " + t.get(1));
        check(t.get(2).address().equals("pay.example.com:443"), "https 기본 포트");
        check(targets("spring.redis.url: redis://user:pw@r1:7000\n").get(0).address().equals("r1:7000"), "redis url");
        check(targets("a: http://x:1\nb: http://x:1/other\n").size() == 1, "같은 주소 하나");
    }

    private static void placeholders() {
        check(targets("db:\n  host: real\nspring.datasource.url: jdbc:mysql://${db.host}:${db.port:3310}/x\n").get(0).address().equals("real:3310"), "자리 바꾸기");
        check(targets("spring.datasource.url: jdbc:mysql://${DB_HOST}/x\n").isEmpty(), "못 바꾸면 뺌");
        check(targets("a: ${b}\nb: ${a}\n").isEmpty(), "순환");
    }

    private static void expect(String url, Links.Kind kind, String title, String address) {
        List<Links.Target> t = targets("spring.datasource.url: \"" + url + "\"\n");
        check(t.size() == 1, "하나: " + url + " → " + t);
        check(t.get(0).kind() == kind && t.get(0).title().equals(title) && t.get(0).address().equals(address), url + " → " + t.get(0));
    }

    private static List<Links.Target> targets(String yml) {
        return Links.targets(List.of(source("application.yml", yml)), Set.of("default"));
    }

    private static Links.Source source(String name, String yml) {
        return new Links.Source(name, true, ConfigKeys.entries(yml, true));
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }
}
