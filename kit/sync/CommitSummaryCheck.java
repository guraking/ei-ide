package kinggura.sync;

import kinggura.sync.CommitSummary.FileChange;
import kinggura.sync.CommitSummary.Kind;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 자체 점검: java -cp out kinggura.sync.CommitSummaryCheck */
public class CommitSummaryCheck {
  static void eq(Object a, Object b) {
    if (!Objects.equals(a, b)) throw new AssertionError("\n" + a + "\n!=\n" + b);
  }

  static String run(Map<String, String> names, List<String> routes, FileChange... changes) {
    return CommitSummary.summarize(List.of(changes), names, routes);
  }

  static FileChange mod(String path, String before, String after) {
    return new FileChange(path, Kind.MODIFIED, before, after);
  }

  public static void main(String[] args) {
    String controllerBefore = """
      package a.kickoff;

      import x.Y;

      /** 킥오프 관리 Controller */
      @RestController
      @Tag(name = "킥오프", description = "킥오프 화면")
      public class KickoffController {
        private final KickoffService service; // 서비스
        private static final String[] PATHS = {"/a", "/b"};

        @Operation(summary = "일정 조회", description = "x")
        @GetMapping("/schedule")
        public List<Schedule> schedule(@RequestParam("id") Long id) {
          if (id == null) { return List.of(); }
          return service.schedule(id);
        }

        // 담당자 목록
        public List<User> users() { return List.of(); }

        public void remove(Long id) { service.remove(id); }

        public void overload(int a) {}
        public void overload(String a) {}
      }
      """;
    String controllerAfter = controllerBefore
      .replace("return service.schedule(id);", "return service.schedule(id, true);")
      .replace("public void remove(Long id) { service.remove(id); }\n", "")
      .replace("public void overload(String a) {}", "public void overload(String a) { a.trim(); }")
      .replace("  // 담당자 목록", "  /** 담당자 저장 */\n  @PostMapping(\"/save\")\n  public void save(User u) { service.save(u); }\n\n  // 담당자 목록");
    String serviceBefore = """
      package a.kickoff.service;
      public interface KickoffService {
        /** 일정 조회 */
        List<Schedule> schedule(Long id);
      }
      """;
    String serviceAfter = serviceBefore.replace("schedule(Long id);", "schedule(Long id, boolean all);");
    String mapperBefore = """
      <?xml version="1.0" encoding="UTF-8"?>
      <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "x">
      <mapper namespace="a.kickoff.mapper.KickoffMapper">
        <!-- 일정 조회 -->
        <select id="selectSchedule" resultType="map">
          SELECT A FROM T WHERE ID = #{id}
        </select>
        <update id="updateUser">UPDATE T SET A = 1</update>
      </mapper>
      """;
    String mapperAfter = mapperBefore.replace("SELECT A FROM", "SELECT A, B FROM")
      .replace("</mapper>", "  <!-- 엑셀 내려받기 -->\n  <select id=\"selectExcel\">SELECT 1</select>\n</mapper>");
    String vue = "<!-- 킥오프 화면 -->\n<template><div/></template>\n";

    // 같은 메뉴의 Controller · Service · Mapper · 화면이 「킥오프」 한 줄로 묶인다. 서비스의 「일정 조회」 는 컨트롤러와 같아 한 번만 적힌다.
    eq(run(Map.of(), List.of(),
           mod("back/src/a/kickoff/KickoffController.java", controllerBefore, controllerAfter),
           mod("back/src/a/kickoff/service/KickoffService.java", serviceBefore, serviceAfter),
           mod("back/src/a/kickoff/mapper/KickoffMapper.xml", mapperBefore, mapperAfter),
           mod("front/src/views/kickoff/KickoffMain.vue", vue, vue + "<style/>")),
       "킥오프: 일정 조회 수정, 담당자 저장 추가, overload 수정, remove 삭제, 엑셀 내려받기 추가 외 1건");

    // 기능 수가 다섯 이하이면 「외」 없이 다 적는다. 쿼리 주석이 없으면 쿼리 id 를 그대로 적는다.
    eq(run(Map.of(), List.of(),
           mod("m/KickoffMapper.xml", mapperBefore, mapperBefore.replace("SET A = 1", "SET A = 2"))),
       "Kickoff: updateUser 수정");

    // 이름표: 주석이 없으면 클래스 이름(계층 낱말을 뗀 것) · 폴더 이름을 한국어로 바꾼다.
    String plain = "public class ScheduleServiceImpl {\n  public void load() {}\n}\n";
    eq(run(Map.of("schedule", "일정관리", "load", "불러오기"), List.of(),
           mod("a/ScheduleServiceImpl.java", plain, plain.replace("load() {}", "load() { x(); }"))),
       "일정관리: 불러오기 수정");
    eq(run(Map.of("partdev", "부품개발"), List.of(),
           mod("src/partdev/service/PartServiceImpl.java", plain, plain.replace("load() {}", "load() { x(); }"))),
       "부품개발: load 수정");
    eq(run(Map.of("kickoff", "킥오프"), List.of(),
           mod("a/KickoffScheduleController.java", plain.replace("ScheduleServiceImpl", "KickoffScheduleController"),
               plain.replace("ScheduleServiceImpl", "KickoffScheduleController").replace("load() {}", "load() { x(); }"))),
       "킥오프 Schedule: load 수정");

    // 이름을 못 찾은 화면 · 그 밖의 파일은 파일 이름 그대로, 파일 단위로 적는다.
    eq(run(Map.of(), List.of(),
           mod("front/src/login.vue", "<template/>", "<template><a/></template>"),
           new FileChange("front/src/old.css", Kind.DELETED, "a{}", null),
           new FileChange("front/src/new.css", Kind.ADDED, null, "a{}")),
       "login.vue 수정\nnew.css 추가\nold.css 삭제");

    // 메서드 밖(import · 필드)만 바뀌면 파일 단위. 새 파일은 메서드마다 「추가」.
    eq(run(Map.of(), List.of(),
           mod("a/UserController.java", "import a;\n" + plain.replace("ScheduleServiceImpl", "UserController"),
               "import b;\n" + plain.replace("ScheduleServiceImpl", "UserController"))),
       "User 수정");
    eq(run(Map.of(), List.of(), new FileChange("a/ScheduleServiceImpl.java", Kind.ADDED, null, plain)),
       "Schedule: load 추가");
    eq(run(Map.of(), List.of(), new FileChange("a/Dto.java", Kind.ADDED, null, "public class Dto {\n  private int a;\n}\n")),
       "Dto 추가");

    // 라우터 · 메뉴 파일의 한국어 제목(vue-router · React). 화면 파일 이름이 들어 있는 가장 안쪽 { } 에서 찾는다.
    String vueRouter = """
      import KickoffMain from '@/views/kickoff/KickoffMain.vue'
      export default [
        { path: '/kickoff', component: KickoffMain, meta: { title: '킥오프' } },
        { path: '/part', component: () => import('@/views/part/index.vue'), meta: { title: 'menu.part' } },
        { path: '/part', component: () => import('@/views/part'), meta: { title: '부품개발' } },
      ]
      """;
    String reactRoutes = """
      // { path: '/x', element: <ScheduleList />, title: '주석 처리한 줄' }
      const routes = [
        { path: '/schedule', element: <ScheduleList />, label: "일정관리" },
      ];
      """;
    eq(run(Map.of(), List.of(vueRouter, reactRoutes),
           mod("src/views/kickoff/KickoffMain.vue", "<template/>", "<template><b/></template>"),
           mod("src/views/part/index.vue", "<template/>", "<template><b/></template>"),
           new FileChange("src/pages/ScheduleList.tsx", Kind.ADDED, null, "export default function ScheduleList() { return <div/> }")),
       "일정관리: 화면 추가\n킥오프: 화면 수정\n부품개발: 화면 수정");

    // 흔한 이름(List.vue)은 폴더와 함께 쓴 경로로 먼저 찾아 다른 메뉴 제목을 가져오지 않는다.
    String listRouter = """
      export default [
        { path: '/a', component: () => import('@/views/notice/List.vue'), meta: { title: '공지' } },
        { path: '/b', component: () => import('@/views/kickoff/List.vue'), meta: { title: '킥오프' } },
      ]
      """;
    eq(run(Map.of(), List.of(listRouter), mod("src/views/kickoff/List.vue", "<template/>", "<template><b/></template>")),
       "킥오프: 화면 수정");

    // jsp 주석 · title, React 맨 위 주석. 저작권 머리 같은 한국어 없는 주석은 이름으로 쓰지 않는다.
    eq(run(Map.of(), List.of(),
           mod("web/kickoff.jsp", "<%@ page contentType=\"text/html\" %>\n<%-- 킥오프 화면 --%>\n<html/>", "<%@ page %>\n<%-- 킥오프 화면 --%>\n<html><a/></html>"),
           mod("web/part.jsp", "<html><head><title>부품개발</title></head></html>", "<html><head><title>부품개발</title></head><a/></html>"),
           mod("src/Menu.jsx", "import React from 'react';\n/** 메뉴 */\nexport default 1;", "import React from 'react';\n/** 메뉴 */\nexport default 2;"),
           mod("src/Lic.js", "/* Copyright 2020 */\nexport default 1;", "/* Copyright 2020 */\nexport default 2;")),
       "Lic.js 수정\n메뉴: 화면 수정\n킥오프: 화면 수정\n부품개발: 화면 수정");

    // Kotlin: 식 몸 · 블록 몸 · 몸 없는 메서드, 프로퍼티 위 주석은 다음 메서드 이름이 되지 않는다.
    String kt = """
      package a

      /** 일정관리 */
      @RestController
      class ScheduleController(private val service: ScheduleService) {
          /** 서비스 */
          private val log = 1

          fun count(): Int = service.count(
              1)

          @Operation(summary = "일정 저장")
          @PostMapping
          fun save(@RequestBody body: Body): Long {
              return service.save(body)
          }

          companion object {
              fun make() = 1
          }
      }
      """;
    eq(run(Map.of(), List.of(),
           mod("a/ScheduleController.kt", kt, kt.replace("1)", "2)").replace("service.save(body)", "service.save(body, true)")
             .replace("fun make() = 1", "fun make() = 2"))),
       "일정관리: count 수정, 일정 저장 수정");
    eq(CommitSummary.parseCode("data class Row(val a: Int)\n\nfun top() { }\n", true).members().size(), 0);

    // 빈 목록 · 못 읽은 파일 · 형식 없는 Java.
    eq(run(Map.of(), List.of()), "");
    eq(run(Map.of(), List.of(), mod("a/B.java", null, null)), "B.java 수정");
    eq(run(Map.of(), List.of(), mod("a/package-info.java", "package a;", "package b;")), "package-info 수정");

    // 이름 손질.
    eq(CommitSummary.stripRole("KickoffServiceImpl"), "Kickoff");
    eq(CommitSummary.stripRole("Service"), "Service");
    eq(CommitSummary.stripRoleWords("킥오프 관리 Controller"), "킥오프 관리");
    eq(CommitSummary.stripRoleWords("화면"), "화면");
    eq(CommitSummary.firstLine("*\n * <p>킥오프 {@link Foo} 일정.\n * @author x\n"), "킥오프 Foo 일정");
    eq(CommitSummary.firstLine("\n * @author x\n"), null);
    eq(CommitSummary.mapped("partDevList", Map.of("part", "부품", "dev", "개발")), "부품 개발 List");
    eq(CommitSummary.mapped("abc", Map.of("part", "부품")), null);
    String line = "a /* { */ \"}\" 'x' // {";
    eq(CommitSummary.mask(line + "\nb"), "a" + " ".repeat(line.length() - 1) + "\nb");
    // 이름표 저장 모양.
    List<String[]> rows = List.of(new String[]{"Kickoff", "킥오프"}, new String[]{"part", "부품"});
    String stored = CommitSummary.formatNames(rows);
    eq(stored, "Kickoff\t킥오프\npart\t부품");
    eq(CommitSummary.parseNames(stored), Map.of("kickoff", "킥오프", "part", "부품"));
    eq(CommitSummary.parseNamesKeepCase(stored).keySet().iterator().next(), "Kickoff");
    eq(CommitSummary.parseNames("bad line\n\t빈낱말\nok\t\na b\t띄어쓰기"), Map.of());
    eq(CommitSummary.parseNames(null), Map.of());
    eq(CommitSummary.nameProblem(rows, "KICKOFF", "킥오프2"), "commitnames.error.same");
    eq(CommitSummary.nameProblem(rows, "dev", ""), "commitnames.error.half");
    eq(CommitSummary.nameProblem(rows, "part dev", "부품개발"), "commitnames.error.word");
    eq(CommitSummary.nameProblem(rows, "dev", "개\t발"), "commitnames.error.korean");
    eq(CommitSummary.nameProblem(rows, "dev", "개발"), null);
    System.out.println("CommitSummaryCheck 통과");
  }
}
