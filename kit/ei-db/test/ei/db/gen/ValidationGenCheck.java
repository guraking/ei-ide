package ei.db.gen;

import ei.db.Meta;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 입력 검증 주석 자체 점검: java -cp out ei.db.gen.ValidationGenCheck */
public class ValidationGenCheck {
  static void eq(Object a, Object b) { if (!Objects.equals(a, b)) throw new AssertionError(a + " != " + b); }

  static String texts(List<ValidationGen.Ann> anns) {
    StringBuilder b = new StringBuilder();
    for (ValidationGen.Ann a : anns) b.append(b.length() == 0 ? "" : " ").append(a.shortText());
    return b.toString();
  }

  static Meta.Column col(String name, String type, int size, int scale, boolean pk, boolean nullable) {
    return new Meta.Column(name, type, size, scale, pk, false, nullable, "");
  }

  public static void main(String[] args) {
    eq(ValidationGen.guessTable("OrderItemDto"), "ORDER_ITEM");
    eq(ValidationGen.guessTable("UserVO"), "USER");
    eq(ValidationGen.guessTable("UserVo"), "USER");
    eq(ValidationGen.guessTable("ProductEntity"), "PRODUCT");
    eq(ValidationGen.guessTable("HTTPLogDto"), "HTTP_LOG");
    eq(ValidationGen.guessTable("Code2Detail"), "CODE2_DETAIL");
    eq(ValidationGen.guessTable("Dto"), "DTO");
    eq(ValidationGen.guessTable("Member"), "MEMBER");

    Set<String> none = Set.of();
    // PK · NOT NULL 문자열
    eq(texts(ValidationGen.annotations(col("USER_ID", "VARCHAR2", 20, 0, true, false), "String", none)),
       "@NotBlank @Size(max = 20)");
    eq(texts(ValidationGen.annotations(col("USER_ID", "VARCHAR2", 20, 0, true, false), "java.lang.String", none)),
       "@NotBlank @Size(max = 20)");
    // NULL 허용 문자열
    eq(texts(ValidationGen.annotations(col("NAME", "VARCHAR2", 100, 0, false, true), "String", none)), "@Size(max = 100)");
    // NOT NULL 숫자 · 소수
    eq(texts(ValidationGen.annotations(col("PRICE", "NUMBER", 12, 2, false, false), "BigDecimal", none)),
       "@NotNull @Digits(integer = 10, fraction = 2)");
    eq(texts(ValidationGen.annotations(col("QTY", "NUMBER", 8, 0, false, true), "Integer", none)),
       "@Digits(integer = 8, fraction = 0)");
    eq(texts(ValidationGen.annotations(col("QTY", "NUMBER", 8, 0, false, true), "long", none)),
       "@Digits(integer = 8, fraction = 0)");
    // Oracle 정밀도 없는 NUMBER: 크기 0, scale -127
    eq(texts(ValidationGen.annotations(col("SEQ", "NUMBER", 0, -127, true, false), "Long", none)), "@NotNull");
    // 날짜는 길이 · 자릿수 없음, NOT NULL 만
    eq(texts(ValidationGen.annotations(col("REG_DT", "DATE", 7, 0, false, false), "LocalDateTime", none)), "@NotNull");
    // DATE 칼럼을 String 으로 받으면 @Size 를 걸지 않는다
    eq(texts(ValidationGen.annotations(col("REG_DT", "DATE", 7, 0, false, true), "String", none)), "");
    // double 은 @Digits 대상이 아니다
    eq(texts(ValidationGen.annotations(col("RATE", "NUMBER", 5, 2, false, true), "Double", none)), "");
    // 이미 있는 주석은 건너뛴다
    eq(texts(ValidationGen.annotations(col("USER_ID", "VARCHAR2", 20, 0, true, false), "String", Set.of("Size"))), "@NotBlank");
    eq(texts(ValidationGen.annotations(col("USER_ID", "VARCHAR2", 20, 0, true, false), "String", Set.of("NotBlank", "Size"))), "");
    // 자릿수가 scale 보다 작게 오면 정수 자리는 0
    eq(texts(ValidationGen.annotations(col("R", "NUMBER", 2, 2, false, true), "BigDecimal", none)), "@Digits(integer = 0, fraction = 2)");

    eq(new ValidationGen.Ann("Size", "max = 3").text("jakarta.validation.constraints"), "@jakarta.validation.constraints.Size(max = 3)");
    eq(new ValidationGen.Ann("NotNull", "").text("javax.validation.constraints"), "@javax.validation.constraints.NotNull");
    eq(Names.camel("USER_ID"), "userId");
    System.out.println("ValidationGenCheck 통과");
  }
}
