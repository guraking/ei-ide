package local.bootservers;

import static local.bootservers.CaseConvert.Style.CAMEL;
import static local.bootservers.CaseConvert.Style.PASCAL;
import static local.bootservers.CaseConvert.Style.SNAKE;
import static local.bootservers.CaseConvert.Style.UPPER_SNAKE;

/** CaseConvert 자체 점검. 실패하면 예외로 끝난다. */
public final class CaseConvertCheck {

    public static void main(String[] args) {
        single();
        mixed();
        System.out.println("CaseConvertCheck 통과");
    }

    private static void single() {
        eq("userName", CaseConvert.convert("USER_NAME", CAMEL), "대문자 스네이크 → 카멜");
        eq("UserName", CaseConvert.convert("user_name", PASCAL), "스네이크 → 파스칼");
        eq("user_name", CaseConvert.convert("userName", SNAKE), "카멜 → 스네이크");
        eq("USER_NAME", CaseConvert.convert("UserName", UPPER_SNAKE), "파스칼 → 대문자 스네이크");
        eq("http_server_url", CaseConvert.convert("HTTPServerURL", SNAKE), "약어");
        eq("address1Line", CaseConvert.convert("ADDRESS1_LINE", CAMEL), "숫자는 앞 낱말");
        eq("NAME", CaseConvert.convert("name", UPPER_SNAKE), "하나만 고르면 한 낱말도 바꿈");
        eq("  userId\n", CaseConvert.convert("  USER_ID\n", CAMEL), "앞뒤 공백 보존");
        eq("", CaseConvert.convert("", CAMEL), "빈 글");
    }

    private static void mixed() {
        eq("private String userName; // USER_NAME", CaseConvert.convert("private String USER_NAME; // USER_NAME", CAMEL),
                "키워드 · 타입 · 줄 주석 그대로");
        eq("private UserDto user_dto;", CaseConvert.convert("private UserDto userDto;", SNAKE), "파스칼 타입 이름 그대로");
        eq("@JsonProperty(\"user_name\") get_user_name()",
                CaseConvert.convert("@JsonProperty(\"user_name\") getUserName()", SNAKE), "문자열 그대로");
        eq("SELECT USER_ID, '\\'userId' FROM T", CaseConvert.convert("SELECT userId, '\\'userId' FROM T", UPPER_SNAKE),
                "작은따옴표 · 이스케이프");
        eq("/* userName */ userId", CaseConvert.convert("/* userName */ USER_ID", CAMEL), "블록 주석 그대로");
        eq("\"\"\"\n user_name \"x\"\n\"\"\" userId", CaseConvert.convert("\"\"\"\n user_name \"x\"\n\"\"\" user_id", CAMEL),
                "텍스트 블록 그대로");
        eq("long maxCount = 0xFFL + 10L;", CaseConvert.convert("long MAX_COUNT = 0xFFL + 10L;", CAMEL), "숫자 리터럴 그대로");
        eq("a = \"open", CaseConvert.convert("a = \"open", SNAKE), "닫히지 않은 문자열");
    }

    private static void eq(Object expected, Object actual, String name) {
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": 기대 " + expected + ", 실제 " + actual);
        }
    }
}
