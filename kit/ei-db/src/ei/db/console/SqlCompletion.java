package ei.db.console;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.PrioritizedLookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import ei.db.DbConfig;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.SchemaCache;
import ei.db.bench.JoinAdvisor;
import ei.db.bench.QueryBuilder;
import ei.db.bench.SqlContext;
import ei.db.bench.SqlParts;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL 콘솔의 표 · 칼럼 자동완성. 「별칭.」 뒤에서는 그 표의 칼럼만, 그 밖에는 표 이름과 문장에 나온 표들의 칼럼을 낸다.
 * 입력한 글자가 소문자면 소문자로 넣는다(Oracle 은 이름이 대문자로 저장된다). 목록은 SchemaCache 가 뒤에서 읽어 둔다.
 * 접속이 열려 있지 않으면 목록을 읽지 않고(로그인하지 않는다) 안내만 보인다.
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

    boolean connected = !SchemaCache.waitingForConnection(project, c);
    if (qualifier != null) {
      String table = aliases.getOrDefault(qualifier.toUpperCase(Locale.ROOT), qualifier);
      if (!addColumns(rs, project, c, table, lower)) {
        rs.addLookupAdvertisement(EiDbBundle.message(connected ? "completion.loading.columns" : "completion.not.connected"));
      }
      rs.stopHere();
      return;
    }
    String before = text.substring(text.lastIndexOf(';', Math.max(0, start - 1)) + 1, start);
    SqlContext.Place place = SqlContext.at(before);
    if (place == SqlContext.Place.OPERATOR || place == SqlContext.Place.ORDER) {
      for (String w : place == SqlContext.Place.OPERATOR ? OPERATORS : ORDER_WORDS) {
        rs.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(cased(w, lower)).bold(), WORD_PRIORITY));
      }
      if (place == SqlContext.Place.ORDER) return;
    }
    if (place == SqlContext.Place.ON) addJoinConditions(rs, project, c, before, lower);
    Set<String> related = place == SqlContext.Place.JOIN_TABLE ? referenced(project, c, aliases.values()) : Set.of();
    List<Meta.Table> tables = SchemaCache.tables(project, c);
    if (tables == null) {
      rs.addLookupAdvertisement(EiDbBundle.message(connected ? "completion.loading.tables" : "completion.not.connected"));
    } else {
      for (Meta.Table t : tables) {
        boolean near = related.contains(t.name().toUpperCase(Locale.ROOT));
        LookupElementBuilder e = LookupElementBuilder.create(cased(t.name(), lower))
            .withIcon("VIEW".equals(t.type()) ? AllIcons.Nodes.DataSchema : AllIcons.Nodes.DataTables)
            .withTypeText(near ? "외래 키로 이어짐  " + t.comment() : t.comment(), true);
        rs.addElement(near ? PrioritizedLookupElement.withPriority(e.bold(), RELATED_PRIORITY) : e);
      }
    }
    Set<String> done = new HashSet<>();
    for (String table : aliases.values()) {
      if (done.add(table.toUpperCase(Locale.ROOT))) addColumns(rs, project, c, table, lower);
    }
  }

  /** 조건 자리 칼럼 뒤에 낼 비교 기호. */
  private static final List<String> OPERATORS = List.of("=", "<>", ">", ">=", "<", "<=", "LIKE", "NOT LIKE", "IN ()", "NOT IN ()", "BETWEEN  AND ", "IS NULL", "IS NOT NULL");
  /** ORDER BY 칼럼 뒤에 낼 말. */
  private static final List<String> ORDER_WORDS = List.of("ASC", "DESC", "NULLS LAST", "NULLS FIRST", "DESC NULLS LAST");
  /** 칼럼 · 표 이름보다 위에 보일 순서 값. */
  private static final double WORD_PRIORITY = 100;
  private static final double RELATED_PRIORITY = 50;
  private static final double CONDITION_PRIORITY = 200;

  /** 문장에 나온 표들의 외래 키가 가리키는 표(대문자). 아직 못 읽은 표는 빠진다. */
  private static Set<String> referenced(Project project, DbConfig c, java.util.Collection<String> tables) {
    Set<String> out = new HashSet<>();
    for (String t : tables) {
      List<Meta.ForeignKey> fks = SchemaCache.importedKeys(project, c, t);
      if (fks == null) continue;
      for (Meta.ForeignKey fk : fks) out.add(SqlParts.simpleName(fk.pkTable()).toUpperCase(Locale.ROOT));
    }
    return out;
  }

  /**
   * ON 자리: 방금 JOIN 한 표(FROM 의 마지막 표)와 앞 표들을 잇는 조건을 낸다. 외래 키를 먼저, 없으면 이름으로 짐작한 것(「추정」).
   * 칼럼 · 외래 키를 아직 못 읽은 표가 있으면 그 표는 이번에 빠지고 다음 자동완성 때 나온다.
   */
  private static void addJoinConditions(CompletionResultSet rs, Project project, DbConfig c, String before, boolean lower) {
    SqlParts.Part from = SqlParts.find(SqlParts.mainBlock(SqlParts.split(before)), "FROM");
    if (from == null) return;
    List<SqlParts.Source> sources = SqlParts.sources(from.body());
    if (sources.size() < 2) return;
    List<QueryBuilder.Table> tables = new ArrayList<>();
    Map<String, List<Meta.Column>> columns = new HashMap<>();
    List<Meta.ForeignKey> fks = new ArrayList<>();
    for (SqlParts.Source s : sources) {
      if (s.subquery()) continue;
      String name = SqlParts.simpleName(s.name()).replace("\"", "");
      tables.add(new QueryBuilder.Table(name, s.ref()));
      List<Meta.Column> cols = SchemaCache.columns(project, c, name);
      if (cols != null) columns.put(JoinAdvisor.key(name), cols);
      List<Meta.ForeignKey> keys = SchemaCache.importedKeys(project, c, name);
      if (keys != null) fks.addAll(keys);
    }
    if (tables.size() < 2) return;
    String last = tables.get(tables.size() - 1).alias();
    for (QueryBuilder.Join j : JoinAdvisor.suggest(tables, columns, fks)) {
      if (!j.rightAlias().equals(last) && !j.leftAlias().equals(last)) continue;
      String cond = j.leftAlias() + "." + cased(j.leftColumn(), lower) + " = " + j.rightAlias() + "." + cased(j.rightColumn(), lower);
      rs.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(cond).bold()
          .withIcon(AllIcons.Nodes.DataColumn)
          .withTypeText(j.guessed() ? "추정 (이름이 같음)" : "외래 키", true), CONDITION_PRIORITY));
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
