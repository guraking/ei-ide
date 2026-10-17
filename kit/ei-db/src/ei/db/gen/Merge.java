package ei.db.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 이미 있는 파일에 덧붙이기. 원래 글은 지우거나 고치지 않고, 이미 있는 id · 메서드는 건너뛴다. */
public final class Merge {
  private Merge() {}

  private static final String MAPPER_END = "</mapper>";

  /** text 가 null 이면 끼울 자리를 못 찾은 것. */
  public record Result(String text, List<String> added, List<String> skipped) {}

  /** 마지막 &lt;/mapper&gt; 앞에 끼운다. */
  public static Result xml(String existing, List<MapperGen.Block> blocks) {
    int end = existing.lastIndexOf(MAPPER_END);
    if (end < 0) return new Result(null, List.of(), List.of());
    List<String> added = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    StringBuilder ins = new StringBuilder();
    for (MapperGen.Block b : blocks) {
      if (Pattern.compile("\\bid\\s*=\\s*[\"']" + Pattern.quote(b.id()) + "[\"']").matcher(existing).find()) {
        skipped.add(b.id());
      } else {
        added.add(b.id());
        ins.append('\n').append(b.text());
      }
    }
    String before = existing.substring(0, end);
    if (!added.isEmpty() && !before.endsWith("\n")) ins.insert(0, '\n');
    return new Result(before + ins + existing.substring(end), added, skipped);
  }

  /** 마지막 } 앞에 메서드를, package 줄(또는 마지막 import) 뒤에 없는 import 를 넣는다. */
  public static Result java(String existing, List<MapperGen.Block> methods, List<String> imports) {
    int end = existing.lastIndexOf('}');
    if (end < 0) return new Result(null, List.of(), List.of());
    List<String> added = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    StringBuilder ins = new StringBuilder();
    for (MapperGen.Block m : methods) {
      if (Pattern.compile("\\b" + Pattern.quote(m.id()) + "\\s*\\(").matcher(existing).find()) {
        skipped.add(m.id());
      } else {
        added.add(m.id());
        ins.append(m.text());
      }
    }
    String before = existing.substring(0, end);
    if (!added.isEmpty() && !before.endsWith("\n")) ins.insert(0, '\n');
    String body = before + ins + existing.substring(end);
    if (added.isEmpty()) return new Result(body, added, skipped);
    return new Result(addImports(body, imports), added, skipped);
  }

  static String addImports(String text, List<String> imports) {
    StringBuilder lines = new StringBuilder();
    for (String imp : imports) {
      if (!Pattern.compile("^import\\s+" + Pattern.quote(imp) + "\\s*;", Pattern.MULTILINE).matcher(text).find()) {
        lines.append("import ").append(imp).append(";\n");
      }
    }
    if (lines.length() == 0) return text;
    var lastImport = Pattern.compile("^import\\s+[^;]+;[ \\t]*\\n", Pattern.MULTILINE).matcher(text);
    int at = -1;
    while (lastImport.find()) at = lastImport.end();
    if (at < 0) {
      var pkg = Pattern.compile("^package\\s+[^;]+;[ \\t]*\\n", Pattern.MULTILINE).matcher(text);
      if (!pkg.find()) return lines + "\n" + text;
      return text.substring(0, pkg.end()) + "\n" + lines + text.substring(pkg.end());
    }
    return text.substring(0, at) + lines + text.substring(at);
  }
}
