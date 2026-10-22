package ei.db.console;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import ei.db.DbConfig;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.SchemaCache;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL 콘솔의 표 · 칼럼 자동완성. 「별칭.」 뒤에서는 그 표의 칼럼만, 그 밖에는 표 이름과 문장에 나온 표들의 칼럼을 낸다.
 * 입력한 글자가 소문자면 소문자로 넣는다(Oracle 은 이름이 대문자로 저장된다). 목록은 SchemaCache 가 뒤에서 읽어 둔다.
 */
public final class SqlCompletion extends CompletionContributor {

  @Override
  public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
    VirtualFile file = parameters.getOriginalFile().getVirtualFile();
    if (file == null || file.getUserData(ConsoleOpener.CONFIG_ID) == null) return;
    DbConfig c = ConsoleOpener.config(file);
    if (c == null) return;
    Project project = parameters.getPosition().getProject();
    String text = parameters.getEditor().getDocument().getCharsSequence().toString();
    int offset = Math.min(parameters.getOffset(), text.length());

    int start = offset;
    while (start > 0 && isIdent(text.charAt(start - 1))) start--;
    String prefix = text.substring(start, offset);
    String qualifier = null;
    if (start > 0 && text.charAt(start - 1) == '.') {
      int q = start - 1;
      int qs = q;
      while (qs > 0 && isIdent(text.charAt(qs - 1))) qs--;
      if (qs < q) qualifier = text.substring(qs, q);
    }
    boolean lower = !prefix.isEmpty() && prefix.equals(prefix.toLowerCase(Locale.ROOT)) && !prefix.equals(prefix.toUpperCase(Locale.ROOT));
    CompletionResultSet rs = result.withPrefixMatcher(prefix).caseInsensitive();
    Map<String, String> aliases = SqlAliases.parse(SqlAliases.statementAround(text, offset));

    if (qualifier != null) {
      String table = aliases.getOrDefault(qualifier.toUpperCase(Locale.ROOT), qualifier);
      if (!addColumns(rs, project, c, table, lower)) rs.addLookupAdvertisement(EiDbBundle.message("completion.loading.columns"));
      rs.stopHere();
      return;
    }
    List<Meta.Table> tables = SchemaCache.tables(project, c);
    if (tables == null) {
      rs.addLookupAdvertisement(EiDbBundle.message("completion.loading.tables"));
    } else {
      for (Meta.Table t : tables) {
        rs.addElement(LookupElementBuilder.create(cased(t.name(), lower))
            .withIcon("VIEW".equals(t.type()) ? AllIcons.Nodes.DataSchema : AllIcons.Nodes.DataTables)
            .withTypeText(t.comment(), true));
      }
    }
    Set<String> done = new HashSet<>();
    for (String table : aliases.values()) {
      if (done.add(table.toUpperCase(Locale.ROOT))) addColumns(rs, project, c, table, lower);
    }
  }

  /** 칼럼을 넣었으면 true, 아직 읽는 중이면 false. */
  private static boolean addColumns(CompletionResultSet rs, Project project, DbConfig c, String table, boolean lower) {
    List<Meta.Column> cols = SchemaCache.columns(project, c, table);
    if (cols == null) return false;
    for (Meta.Column col : cols) {
      String tail = col.comment().isEmpty() ? "" : "  " + col.comment();
      rs.addElement(LookupElementBuilder.create(cased(col.name(), lower))
          .withIcon(col.pk() ? AllIcons.Nodes.DataColumn : AllIcons.Nodes.Field)
          .withTailText(tail, true)
          .withTypeText(col.typeLabel(), true));
    }
    return true;
  }

  private static String cased(String name, boolean lower) {
    return lower ? name.toLowerCase(Locale.ROOT) : name;
  }

  private static boolean isIdent(char ch) {
    return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$' || ch == '#';
  }
}
