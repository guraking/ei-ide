package ei.db.gen;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** DTO 클래스 글과 resultMap 블록. */
public final class DtoGen {
  private DtoGen() {}

  /** column 은 DB 칼럼 이름, name 은 자바 필드 이름, type 은 자바 형식(단순 이름 또는 FQN). */
  public record Field(String column, String name, String type, String comment) {}

  public static String dto(String pkg, String cls, List<Field> fields, boolean lombok, boolean javadoc) {
    Set<String> imports = new TreeSet<>();
    for (Field f : fields) {
      String imp = TypeMap.IMPORTS.get(f.type());
      if (imp != null) imports.add(imp);
    }
    if (lombok) {
      imports.add("lombok.Getter");
      imports.add("lombok.Setter");
    }
    StringBuilder b = new StringBuilder();
    if (!pkg.isEmpty()) b.append("package ").append(pkg).append(";\n\n");
    for (String imp : imports) b.append("import ").append(imp).append(";\n");
    if (!imports.isEmpty()) b.append('\n');
    if (lombok) b.append("@Getter\n@Setter\n");
    b.append("public class ").append(cls).append(" {\n");
    for (Field f : fields) {
      if (javadoc && !f.comment().isBlank()) b.append("    /** ").append(javadocText(f.comment())).append(" */\n");
      b.append("    private ").append(f.type()).append(' ').append(f.name()).append(";\n");
    }
    if (!lombok) {
      for (Field f : fields) {
        String up = Names.upperFirst(f.name());
        b.append('\n');
        b.append("    public ").append(f.type()).append(" get").append(up).append("() {\n");
        b.append("        return ").append(f.name()).append(";\n    }\n\n");
        b.append("    public void set").append(up).append('(').append(f.type()).append(' ').append(f.name()).append(") {\n");
        b.append("        this.").append(f.name()).append(" = ").append(f.name()).append(";\n    }\n");
      }
    }
    return b.append("}\n").toString();
  }

  /** 두 칸 들여 쓴 resultMap 블록. pkColumns 에 든 칼럼은 &lt;id&gt; 로. */
  public static String resultMap(String id, String type, List<Field> fields, Set<String> pkColumns) {
    StringBuilder b = new StringBuilder();
    b.append("  <resultMap id=\"").append(id).append("\" type=\"").append(type).append("\">\n");
    for (Field f : fields) {
      boolean pk = pkColumns.contains(f.column());
      b.append("    <").append(pk ? "id    " : "result").append(" property=\"").append(f.name())
       .append("\" column=\"").append(Xml.attr(f.column())).append("\"/>\n");
    }
    return b.append("  </resultMap>\n").toString();
  }

  private static String javadocText(String s) {
    return s.replace("*/", "* /").replace('\n', ' ').strip();
  }
}
