package ei.db.console;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.panels.HorizontalLayout;
import ei.db.BarAction;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import ei.db.DbConfig;
import ei.db.DbKind;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.QueryResult;
import ei.db.gen.DtoDialog;
import ei.db.gen.EnumDialog;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;

import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JSpinner;
import javax.swing.JViewport;
import com.intellij.ui.JBIntSpinner;
import java.awt.Toolkit;
import java.text.ParseException;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.sql.Connection;
import ei.db.Meta;
import java.util.ArrayList;
import java.awt.event.MouseEvent;
import java.awt.event.MouseAdapter;
import javax.swing.SwingUtilities;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Dimension;
import javax.swing.table.JTableHeader;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.awt.font.TextAttribute;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.intellij.openapi.actionSystem.CommonShortcuts;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.SearchTextField;
import ei.db.SchemaCache;
import ei.db.SqlRunner;
import org.jetbrains.annotations.NotNull;
import javax.swing.DefaultCellEditor;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import java.awt.Color;

/** 결과 탭 하나의 내용: 조회면 도구줄 + 표, 변경이면 한 줄 요약, 오류면 붉은 글. */
final class ResultPanel extends SimpleToolWindowPanel {

  /** 칸 너비를 잴 때 보는 앞쪽 행 수와, 처음 칸 너비가 넘지 않을 화면 폭의 몫(긴 글 칸 하나가 화면을 다 차지하지 않게). */
  private static final int FIT_ROWS = 50;
  private static final double MAX_COLUMN_SCREEN_SHARE = 0.4;
  /** 엑셀이 UTF-8 로 읽게 하는 표식. */
  private static final String BOM = "﻿";

  /** 반영 미리보기 창을 처음 열 때 화면에 대한 크기 몫. 사용자가 바꾼 크기는 PREVIEW_DIMENSION_KEY 로 기억한다. */
  private static final double PREVIEW_WIDTH_RATIO = 0.4;
  private static final double PREVIEW_HEIGHT_RATIO = 0.35;
  private static final String PREVIEW_DIMENSION_KEY = "ei.db.result.applyPreview";
  /** 맞춘 칸 너비에 더하는 여백(px, 배율 전). */
  private static final int CELL_PADDING = 16;
  /** 도구줄 왼쪽 여백 · 묶음 사이 간격(px, 배율 전). */
  private static final int BAR_INSET = 8;
  private static final String TOOLBAR_PLACE = "EiDbResult";
  /** 고쳤지만 아직 반영하지 않은 칸의 바탕. */
  private static final Color EDITED_BACKGROUND = new JBColor(new Color(0xFFF1C2), new Color(0x4D4421));
  /** 지움 표시했지만 아직 반영하지 않은 행의 바탕(글자에는 취소선). */
  private static final Color DELETED_BACKGROUND = new JBColor(new Color(0xFFE0E0), new Color(0x4D2A2A));
  /** 결과 표 행 높이 · 건수 꼬리표와 실행 시간 사이 · 오류 아이콘과 문구 사이(px, 배율 전). */
  private static final int ROW_HEIGHT = 26;
  private static final int MILLIS_GAP = 6;
  private static final int ERROR_ICON_GAP = 8;
  private static final String NULL_TEXT = "NULL";
  /** 머리줄 링크 사이 구분 글. */
  private static final String LINK_DOT = " · ";

  private final Project project;
  private final DbConfig config;
  private final QueryResult result;
  private final String table;
  /** 머리줄 제목(결과 탭 이름과 같다: 「결과 N · 표」). */
  private final String title;
  private JBTable grid;
  private TableRowSorter<Model> sorter;
  private JBLabel count;
  private SearchTextField searchBox;
  /** 반영 · 되돌리기 단추가 든 도구줄. 고친 값이 바뀌면 다시 판정하게 한다. */
  private ActionToolbar actions;
  /** 고친 값: 모델 행 → (결과 칼럼 번호 → 새 값). 반영 전까지 화면에만 있다. */
  private final Map<Integer, Map<Integer, Object>> edits = new TreeMap<>();
  /**
   * 지움 표시한 행(결과 행 객체 자체로 견준다: 행이 빠져 행 번호가 밀려도 어긋나지 않는다). 반영 전까지 화면에만 있다.
   * 지움 표시한 행에는 고친 값을 두지 않고 칸도 고칠 수 없다. 「되돌리기」가 고친 값과 함께 푼다.
   */
  private final Set<List<Object>> deleted = Collections.newSetFromMap(new IdentityHashMap<>());
  /** 표의 칼럼 중 결과에 없는 칼럼 수. 0 이어야 행을 지울 수 있다(지우기 전 백업에 모든 칼럼이 필요). pkColumns 와 함께 채운다. */
  private int missingColumns;
  /** 「적용」의 문장들이 실행되는 중. 끝날 때까지(성공 · 0행 · 오류 · 취소 · 거절) 다시 적용할 수 없다: 같은 UPDATE · DELETE 가 두 번 나가지 않게 한다. */
  private boolean applying;
  /** 결과 칼럼마다 PK 여부. null 이면 아직 표 정의를 읽지 않았다. */
  private boolean[] pkColumns;
  /** 반영 문장에 쓸 표 · 칼럼(결과 칼럼 번호 순) 표기: 표 정의의 이름 그대로를 SqlText.ident 로. pkColumns 와 함께 채운다. */
  private String editTable;
  /** 마지막 왼쪽 누름이 행 번호 칸이었는지(끌어서 여러 행을 고르는 중인지). */
  private boolean rowPick;
  private String[] editColumns;
  /** 고치기를 막는 이유. null 이면 막지 않거나 아직 모른다. */
  private String editBlocked;
  private boolean loadingEdit;

  ResultPanel(Project project, DbConfig c, QueryResult result, String table, String title) {
    super(true, true);
    this.project = project;
    this.config = c;
    this.result = result;
    this.table = table;
    this.title = title;
    if (result.isQuery()) buildQuery(c);
    else buildUpdate(c);
  }

  /** 결과 비교(ResultCompare)가 읽는 값. */
  QueryResult result() { return result; }

  DbConfig config() { return config; }

  /** 결과 칼럼마다 PK 여부. 고치기를 시작해 표 정의를 읽었고 고칠 수 있는 결과일 때만 있고, 아니면 null. */
  boolean[] knownPkColumns() { return editBlocked == null ? pkColumns : null; }

  /** 오류 탭: 위에 오류 아이콘 + 굵은 메시지, 아래에 실행한 SQL(회색 고정폭). 둘 다 골라 복사할 수 있다. */
  static JComponent error(String sql, String message) {
    JBTextArea text = readOnlyArea(message);
    text.setForeground(UiColors.DANGER);
    text.setFont(text.getFont().deriveFont(Font.BOLD));
    JBLabel icon = new JBLabel(AllIcons.General.Error);
    icon.setVerticalAlignment(SwingConstants.TOP);
    JPanel head = new JPanel(new BorderLayout(JBUI.scale(ERROR_ICON_GAP), 0));
    head.setBorder(JBUI.Borders.empty(8, 12));
    head.add(icon, BorderLayout.WEST);
    head.add(text, BorderLayout.CENTER);
    JBTextArea code = readOnlyArea(sql);
    code.setForeground(UIUtil.getContextHelpForeground());
    code.setFont(monoFont());
    code.setBorder(JBUI.Borders.empty(0, 12, 8, 12));
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(head, BorderLayout.NORTH);
    panel.add(ScrollPaneFactory.createScrollPane(code, true), BorderLayout.CENTER);
    return panel;
  }

  private static JBTextArea readOnlyArea(String value) {
    JBTextArea text = new JBTextArea(value);
    text.setEditable(false);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setOpaque(false);
    return text;
  }

  // 편집기 글꼴에 없는 글자(드문 한자, 이모지 등)는 IDE 가 다른 글꼴로 대신 그리게 한다.
  private static Font monoFont() {
    return UIUtil.getFontWithFallback(EditorColorsManager.getInstance().getGlobalScheme().getFont(EditorFontType.PLAIN));
  }

  private void buildUpdate(DbConfig c) {
    int count = Math.max(result.updateCount(), 0);
    boolean auto = DbSessions.get(project).autoCommit(c);
    JBLabel label = new JBLabel(EiDbBundle.message(auto ? "result.updated.auto" : "result.updated.pending", count) + " · " + result.millis() + " ms");
    label.setForeground(auto ? UiColors.SUCCESS : UiColors.WARNING);
    label.setToolTipText(result.sql());
    label.setBorder(JBUI.Borders.empty(10, 12));
    add(label, BorderLayout.NORTH);
  }

  /** 두 줄 머리의 위아래 여백(px, 배율 전). */
  private static final int HEADER_PADDING = 10;

  private void buildQuery(DbConfig c) {
    boolean twoLineHeader = !result.comments().isEmpty();
    Model model = new Model(result);
    grid = new JBTable(model) {
      // 칸들이 창보다 좁으면 창 폭을 채우고(칸이 함께 늘어남), 넓으면 가로 스크롤.
      @Override
      public boolean getScrollableTracksViewportWidth() {
        return getParent() instanceof JViewport v && getPreferredSize().width < v.getWidth();
      }

      @Override
      protected JTableHeader createDefaultTableHeader() {
        return new JBTableHeader() {
          // 주석 줄이 있으면 머리를 두 줄 높이로 둔다. 기본 머리는 한 줄 높이로 고정돼 아래 줄이 잘린다.
          @Override
          public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            if (twoLineHeader) d.height = Math.max(d.height, getFontMetrics(getFont()).getHeight() * 2 + JBUI.scale(HEADER_PADDING));
            return d;
          }
        };
      }

      // 줄무늬가 칸 바탕을 덮으므로 고친 칸 · 행 번호 칸 바탕은 그 뒤에 칠한다.
      @Override
      public @NotNull Component prepareRenderer(@NotNull TableCellRenderer renderer, int row, int column) {
        Component c = super.prepareRenderer(renderer, row, column);
        Color background = isCellSelected(row, column) ? null : cellBackground(this, row, column);
        if (background != null) c.setBackground(background);
        if (isDeletedRow(convertRowIndexToModel(row))) c.setFont(struck(c.getFont()));
        return c;
      }
    };
    grid.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
    grid.setRowHeight(JBUI.scale(ROW_HEIGHT));
    grid.setStriped(true);
    grid.setShowGrid(false);
    grid.getEmptyText().setText(EiDbBundle.message("result.empty"));
    sorter = new TableRowSorter<>(model) {
      // 머리 한 번 누름은 칼럼 고르기라 정렬하지 않는다. 정렬은 머리 두 번 누름 · 우클릭 메뉴로 setSortKeys 를 부른다.
      @Override public void toggleSortOrder(int column) { }
    };
    for (int i = 0; i < model.getColumnCount(); i++) sorter.setComparator(i, ResultPanel::compareValues);
    grid.setRowSorter(sorter);
    grid.setDefaultRenderer(Object.class, new CellRenderer());
    grid.setDefaultEditor(Object.class, new ValueEditor());
    grid.getTableHeader().setReorderingAllowed(false);
    // 칸 단위로 고를 수 있게 한다(Ctrl+C 는 고른 칸만 복사). 머리를 누르면 그 칼럼 전체를 고른다.
    grid.setCellSelectionEnabled(true);
    grid.getTableHeader().addMouseListener(new MouseAdapter() {
      @Override public void mousePressed(MouseEvent e) { onHeader(e); }
      @Override public void mouseReleased(MouseEvent e) { onHeader(e); }
    });
    grid.addMouseListener(new MouseAdapter() {
      @Override public void mousePressed(MouseEvent e) { onCell(e); }
      @Override public void mouseReleased(MouseEvent e) { onCell(e); }
    });
    // 행 번호 칸에서 끌면 JTable 이 칼럼 선택을 끈 자리까지로 줄이므로, 끄는 동안 행 전체 선택을 유지한다.
    grid.addMouseMotionListener(new MouseAdapter() {
      @Override public void mouseDragged(MouseEvent e) {
        if (rowPick && grid.getColumnCount() > 1) grid.setColumnSelectionInterval(1, grid.getColumnCount() - 1);
      }
    });
    fitColumns(grid);
    CellActions.installShortcut(grid, project, c, result);

    // 머리줄: 왼쪽 「결과 N · 표」(굵게) · 건수 꼬리표 · 실행 시간 · 더 읽기 · 찾기 칸, 오른쪽 반영 · 되돌리기 단추와 내보내기 링크.
    // 머리줄 아래 선은 SimpleToolWindowPanel 이 그린다(새 UI 는 표를 내렸을 때만). 칸 테두리를 따로 두면 선이 겹친다.
    JPanel bar = new JPanel(new HorizontalLayout(0, SwingConstants.CENTER));
    bar.setBorder(JBUI.Borders.emptyLeft(BAR_INSET));
    JBLabel heading = new JBLabel(title);
    heading.setFont(heading.getFont().deriveFont(Font.BOLD));
    heading.setToolTipText(result.sql());
    heading.setBorder(JBUI.Borders.emptyRight(BAR_INSET));
    bar.add(heading);
    count = new JBLabel();
    showCount(EiDbBundle.message("common.rows", result.rows().size()));
    JBLabel millis = new JBLabel(result.millis() + " ms", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
    millis.setBorder(JBUI.Borders.emptyLeft(MILLIS_GAP));
    bar.add(count);
    bar.add(millis);
    if (result.truncated()) {
      JBLabel cut = new JBLabel(EiDbBundle.message("result.truncated", result.rows().size()));
      cut.setForeground(UiColors.WARNING);
      if (result.rows().size() >= DbSessions.MAX_ROW_LIMIT) cut.setToolTipText(EiDbBundle.message("result.at.limit", DbSessions.MAX_ROW_LIMIT));
      bar.add(cut);
    }
    // 다시 읽기는 문장을 처음부터 다시 실행하므로 변경이 섞인 문장(INSERT … RETURNING 등)에는 두지 않는다.
    // 상한(MAX_ROW_LIMIT)까지 읽었으면 더 읽지 않는다.
    if (result.truncated() && SqlText.isRereadable(result.sql()) && result.rows().size() < DbSessions.MAX_ROW_LIMIT) {
      bar.add(button(EiDbBundle.message("result.more", DbSessions.MAX_ROWS), null, () -> readMore(c, result.rows().size() + DbSessions.MAX_ROWS)));
      JBIntSpinner wanted = new JBIntSpinner(Math.min(result.rows().size() + DbSessions.MAX_ROWS, DbSessions.MAX_ROW_LIMIT), 1, DbSessions.MAX_ROW_LIMIT);
      wanted.setToolTipText(EiDbBundle.message("result.wanted.tooltip"));
      Runnable byInput = () -> readWanted(c, wanted);
      ((JSpinner.DefaultEditor) wanted.getEditor()).getTextField().addActionListener(e -> byInput.run());
      bar.add(Box.createHorizontalStrut(JBUI.scale(BAR_INSET)));
      bar.add(wanted);
      bar.add(new JBLabel(EiDbBundle.message("result.rows.unit")));
      bar.add(button(EiDbBundle.message("result.fetch"), null, byInput));
    }
    BarAction apply = new BarAction(EiDbBundle.message("result.apply"), EiDbBundle.message("backup.grid.apply.tooltip"), AllIcons.Actions.Commit, this::applyEdits) {
      @Override
      public void update(@NotNull AnActionEvent e) {
        super.update(e);
        e.getPresentation().setText(EiDbBundle.message("result.apply.count", edits.size() + deleted.size()));
      }
    };
    actions = BarAction.toolbar(TOOLBAR_PLACE, this,
        apply.visibleWhen(this::hasPending).enabledWhen(() -> !applying).withText(),
        new BarAction(EiDbBundle.message("result.revert"), EiDbBundle.message("backup.grid.revert.tooltip"), AllIcons.Actions.Rollback, this::revertEdits)
            .visibleWhen(this::hasPending).withText(),
        new BarAction(EiDbBundle.message("backup.grid.delete"), EiDbBundle.message("backup.grid.delete.tooltip"), AllIcons.General.Remove, this::deleteRows)
            .visibleWhen(this::canOfferDelete).enabledWhen(() -> grid.getSelectedRowCount() > 0));
    // 내보내기 · 복사 · 생성은 흐린 글자 링크로, 「·」 로 나눈다.
    JPanel links = new JPanel(new HorizontalLayout(0, SwingConstants.CENTER));
    links.setBorder(JBUI.Borders.empty(0, BAR_INSET));
    links.add(actions.getComponent());
    addLink(links, EiDbBundle.message("common.copy"), null, this::copyTsv);
    addLink(links, "CSV", EiDbBundle.message("result.csv.tooltip"), this::exportCsv);
    addLink(links, EiDbBundle.message("result.excel"), EiDbBundle.message("result.excel.tooltip"), this::exportXlsx);
    addLink(links, "INSERT", EiDbBundle.message("result.insert.tooltip"), this::copyInserts);
    addLink(links, "UPDATE", EiDbBundle.message("result.update.tooltip"), () -> copyUpdates(c));
    addLink(links, "DTO", EiDbBundle.message("dto.command"), () -> DtoDialog.show(project, c, result));
    addLink(links, "enum", EiDbBundle.message("result.enum.tooltip"), () -> EnumDialog.show(project, c, result));
    // 찾기 칸은 머리줄 왼쪽 묶음과 오른쪽 링크 사이를 다 채운다.
    JPanel head = new JPanel(new BorderLayout(JBUI.scale(BAR_INSET), 0));
    head.add(bar, BorderLayout.WEST);
    head.add(searchField(), BorderLayout.CENTER);
    head.add(links, BorderLayout.EAST);

    setToolbar(head);
    setContent(ScrollPaneFactory.createScrollPane(grid, true));
    // 결과 창 안에서 Ctrl+F(찾기 단축키)는 IDE 찾기 대신 검색칸으로 간다.
    DumbAwareAction.create(e -> searchBox.getTextEditor().requestFocusInWindow()).registerCustomShortcutSet(CommonShortcuts.getFind(), this);
  }

  /** 머리줄 오른쪽의 흐린 글자 링크 하나. 첫 링크가 아니면 앞에 「·」 를 둔다. */
  private static void addLink(JPanel links, String text, String tooltip, Runnable action) {
    if (links.getComponentCount() > 1) {
      links.add(new JBLabel(LINK_DOT, UIUtil.ComponentStyle.REGULAR, UIUtil.FontColor.BRIGHTER));
    }
    ActionLink link = new ActionLink(text, e -> { action.run(); });
    link.setForeground(UIUtil.getContextHelpForeground());
    link.setToolTipText(tooltip);
    links.add(link);
  }

  /** 결과 안에서 찾기: 글자가 든 칸이 하나라도 있는 행만 보인다(대소문자 무시, null 칸 · 행 번호 제외). */
  private JComponent searchField() {
    searchBox = new SearchTextField(false);
    searchBox.getTextEditor().getEmptyText().setText(EiDbBundle.message("result.search.hint"));
    searchBox.setToolTipText(EiDbBundle.message("result.search.tooltip"));
    searchBox.addDocumentListener(new DocumentAdapter() {
      @Override protected void textChanged(@NotNull DocumentEvent e) { filter(searchBox.getText()); }
    });
    return searchBox;
  }

  private void filter(String text) {
    String needle = text.toLowerCase(Locale.ROOT);
    sorter.setRowFilter(needle.isEmpty() ? null : new Contains(needle));
    int total = result.rows().size();
    showCount(needle.isEmpty() ? EiDbBundle.message("common.rows", total) : EiDbBundle.message("result.filtered", grid.getRowCount(), total));
  }

  /** 건수를 초록 꼬리표로 보인다. 화면 읽기 프로그램용으로 접근성 이름에도 둔다. */
  private void showCount(String text) {
    count.setIcon(new Badge(text, UiColors.SUCCESS_BG, UiColors.SUCCESS));
    count.getAccessibleContext().setAccessibleName(text);
  }

  private static final class Contains extends RowFilter<Model, Integer> {
    private final String needle;

    Contains(String needle) { this.needle = needle; }

    @Override
    public boolean include(Entry<? extends Model, ? extends Integer> entry) {
      for (int i = 1; i < entry.getValueCount(); i++) {
        Object v = entry.getValue(i);
        if (v != null && text(v).toLowerCase(Locale.ROOT).contains(needle)) return true;
      }
      return false;
    }
  }

  /**
   * 같은 문장을 limit 건(MAX_ROW_LIMIT 까지)까지 다시 읽어 이 탭을 바꾼다. 처음부터 다시 실행하므로 그 사이 바뀐 데이터나 ORDER BY 없는 순서는 달라질 수 있다.
   * 이미 실행된 조회 문장이라 위험 문장 확인은 다시 하지 않는다. 진행 막대에서 취소할 수 있다.
   */
  private void readMore(DbConfig c, int limit) {
    if (grid.isEditing()) grid.getCellEditor().stopCellEditing();
    // 다시 읽으면 이 패널을 새 결과로 바꾸므로 반영하지 않은 고친 값은 사라진다.
    if (hasPending() && Messages.showYesNoDialog(project,
        EiDbBundle.message(deleted.isEmpty() ? "result.more.discard" : "backup.grid.more.discard", edits.size() + deleted.size()),
        EiDbBundle.message("result.more.discard.title"), Messages.getWarningIcon()) != Messages.YES) {
      return;
    }
    SqlRunner.reread(project, c, result.sql(), result.binds(), Math.min(limit, DbSessions.MAX_ROW_LIMIT),
        more -> replaceWith(new ResultPanel(project, c, more, table, title)));
  }

  /** 건수 칸에 쓴 수로 다시 읽는다. 1 ~ MAX_ROW_LIMIT 의 수가 아니면 알리고 읽지 않는다. */
  private void readWanted(DbConfig c, JBIntSpinner wanted) {
    try {
      wanted.commitEdit();
    } catch (ParseException e) {
      String text = ((JSpinner.DefaultEditor) wanted.getEditor()).getTextField().getText();
      Notify.error(project, EiDbBundle.message("result.wanted.invalid", DbSessions.MAX_ROW_LIMIT, text));
      return;
    }
    readMore(c, wanted.getNumber());
  }

  /** 이 패널이 든 결과 탭의 내용을 바꾼다. 그 사이 탭을 닫았으면 아무것도 하지 않는다. */
  private void replaceWith(ResultPanel next) {
    if (project.isDisposed()) return;
    ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(ResultView.TOOL_WINDOW_ID);
    if (window == null) return;
    Content content = window.getContentManager().getContent(this);
    if (content != null) content.setComponent(next);
  }

  private static JButton button(String text, javax.swing.Icon icon, Runnable action) {
    JButton b = new JButton(text, icon);
    b.addActionListener(e -> action.run());
    return b;
  }

  private static JButton button(String text, javax.swing.Icon icon, String tooltip, Runnable action) {
    JButton b = button(text, icon, action);
    b.setToolTipText(tooltip);
    return b;
  }

  private static void fitColumns(JBTable table) {
    int maxWidth = (int) (Toolkit.getDefaultToolkit().getScreenSize().width * MAX_COLUMN_SCREEN_SHARE);
    for (int col = 0; col < table.getColumnCount(); col++) {
      TableColumn column = table.getColumnModel().getColumn(col);
      Component header = table.getTableHeader().getDefaultRenderer()
          .getTableCellRendererComponent(table, column.getHeaderValue(), false, false, -1, col);
      int width = header.getPreferredSize().width;
      int rows = Math.min(table.getRowCount(), FIT_ROWS);
      for (int row = 0; row < rows; row++) {
        width = Math.max(width, table.prepareRenderer(table.getCellRenderer(row, col), row, col).getPreferredSize().width);
      }
      column.setPreferredWidth(Math.min(width + JBUI.scale(CELL_PADDING), maxWidth));
    }
  }

  /** 고른 행(없으면 전체)의 모델 행 번호. 정렬돼 있으면 화면에 보이는 순서대로. */
  private int[] chosenRows() {
    int[] selected = grid.getSelectedRows();
    int n = selected.length > 0 ? selected.length : grid.getRowCount();
    int[] out = new int[n];
    for (int i = 0; i < n; i++) out[i] = grid.convertRowIndexToModel(selected.length > 0 ? selected[i] : i);
    return out;
  }

  /**
   * 정렬 비교. 같은 형식이면 그 형식의 순서(숫자 · 날짜 · 글자), 형식이 다른 숫자끼리는 값 크기, 나머지는 보이는 글자 순서.
   * null 은 RowSorter 가 따로 맨 앞(오름차순)으로 보낸다.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static int compareValues(Object a, Object b) {
    if (a instanceof RowNumber x && b instanceof RowNumber y) return Integer.compare(x.value(), y.value());
    if (a instanceof Comparable ca && a.getClass() == b.getClass()) return ca.compareTo(b);
    if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue());
    return text(a).compareTo(text(b));
  }

  /** order 가 null 이면 정렬을 풀어 처음 읽은 순서로 돌린다. modelColumn 은 행 번호 칸을 포함한 번호. */
  private void sortBy(int modelColumn, SortOrder order) {
    grid.getRowSorter().setSortKeys(order == null ? null : List.of(new RowSorter.SortKey(modelColumn, order)));
  }

  /** 머리 두 번 누름: 그 칼럼이 오름차순이면 내림차순, 아니면 오름차순. */
  private void toggleSort(int modelColumn) {
    List<? extends RowSorter.SortKey> keys = grid.getRowSorter().getSortKeys();
    boolean ascending = !keys.isEmpty() && keys.get(0).getColumn() == modelColumn && keys.get(0).getSortOrder() == SortOrder.ASCENDING;
    sortBy(modelColumn, ascending ? SortOrder.DESCENDING : SortOrder.ASCENDING);
  }

  /** 머리 왼쪽 누름: 칼럼 고르기(Ctrl 은 더하기), 두 번 누르면 정렬. 오른쪽 누름: 칼럼 복사 · 정렬 메뉴. 0번(행 번호) 칼럼은 건너뛴다. */
  private void onHeader(MouseEvent e) {
    int view = grid.getTableHeader().columnAtPoint(e.getPoint());
    if (view < 0) return;
    int col = grid.convertColumnIndexToModel(view);
    if (e.isPopupTrigger()) {
      if (col > 0) columnMenu(col - 1).show(e.getComponent(), e.getX(), e.getY());
      return;
    }
    if (e.getID() != MouseEvent.MOUSE_PRESSED || !SwingUtilities.isLeftMouseButton(e) || grid.getRowCount() == 0) return;
    if (e.getClickCount() == 2) toggleSort(col);
    if (e.isControlDown()) grid.addColumnSelectionInterval(view, view);
    else grid.setColumnSelectionInterval(view, view);
    grid.setRowSelectionInterval(0, grid.getRowCount() - 1);
  }

  /**
   * 칸 오른쪽 누름: 값 · 칼럼 · 행 복사 메뉴. 누른 칸이 골라져 있지 않으면 그 칸만 고른다.
   * 행 번호 칸 왼쪽 누름: 그 행 전체를 고른다(Ctrl · Shift 로 행을 더하거나 끌어서 여러 행을 고르는 것은 JTable 이 먼저 처리한다). 행 번호 칸 자신은 복사에 섞이지 않게 뺀다.
   */
  private void onCell(MouseEvent e) {
    if (e.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
      int view = grid.columnAtPoint(e.getPoint());
      rowPick = view >= 0 && grid.rowAtPoint(e.getPoint()) >= 0 && grid.convertColumnIndexToModel(view) == 0 && grid.getColumnCount() > 1;
      if (rowPick) grid.setColumnSelectionInterval(1, grid.getColumnCount() - 1);
      return;
    }
    if (e.getID() == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
      int row = grid.rowAtPoint(e.getPoint());
      int view = grid.columnAtPoint(e.getPoint());
      if (row >= 0 && view >= 0) beginEdit(row, view);
      return;
    }
    if (!e.isPopupTrigger()) return;
    int row = grid.rowAtPoint(e.getPoint());
    int view = grid.columnAtPoint(e.getPoint());
    if (row < 0 || view < 0) return;
    if (!grid.isCellSelected(row, view)) grid.changeSelection(row, view, false, false);
    int col = grid.convertColumnIndexToModel(view);
    JPopupMenu menu = col > 0 ? columnMenu(col - 1) : new JPopupMenu();
    if (col > 0) {
      Object v = result.rows().get(grid.convertRowIndexToModel(row)).get(col - 1);
      menu.insert(item(EiDbBundle.message("result.menu.copy.value"), () -> copy(v == null ? "" : text(v), EiDbBundle.message("result.copied.value"))), 0);
      CellActions.addTo(menu, 1, project, config, result, grid.convertRowIndexToModel(row), col - 1);
    }
    menu.addSeparator();
    menu.add(item(EiDbBundle.message("result.menu.copy.rows"), this::copyTsv));
    if (canOfferDelete()) menu.add(item(EiDbBundle.message("backup.grid.delete"), this::deleteRows));
    menu.show(e.getComponent(), e.getX(), e.getY());
  }

  /** index 는 결과 칼럼 번호(행 번호 칼럼 제외). */
  private JPopupMenu columnMenu(int index) {
    String name = result.columns().get(index);
    JPopupMenu menu = new JPopupMenu();
    menu.add(item(EiDbBundle.message("result.menu.copy.column.name", name), () -> copy(name, EiDbBundle.message("result.copied.column.name"))));
    menu.add(item(EiDbBundle.message("result.menu.copy.column.names"), () -> copy(String.join(", ", result.columns()), EiDbBundle.message("result.copied.column.names", result.columns().size()))));
    menu.add(item(EiDbBundle.message("result.menu.copy.column.values"), () -> {
      List<String> values = columnValues(index);
      copy(String.join("\n", values), EiDbBundle.message("result.copied.values", values.size()));
    }));
    menu.add(item(EiDbBundle.message("result.menu.copy.in.list"), () -> {
      List<String> quoted = new ArrayList<>();
      for (String v : columnValues(index)) quoted.add("'" + v.replace("'", "''") + "'");
      copy("(" + String.join(", ", quoted) + ")", EiDbBundle.message("result.copied.in.list", quoted.size()));
    }));
    menu.addSeparator();
    menu.add(item(EiDbBundle.message("result.sort.asc"), () -> sortBy(index + 1, SortOrder.ASCENDING)));
    menu.add(item(EiDbBundle.message("result.sort.desc"), () -> sortBy(index + 1, SortOrder.DESCENDING)));
    menu.add(item(EiDbBundle.message("result.sort.none"), () -> sortBy(index + 1, null)));
    return menu;
  }

  /** 고른 행(없으면 전체)의 한 칼럼 값. null 은 빈 글자. */
  private List<String> columnValues(int index) {
    List<String> out = new ArrayList<>();
    for (int row : chosenRows()) {
      Object v = result.rows().get(row).get(index);
      out.add(v == null ? "" : text(v));
    }
    return out;
  }

  private static JMenuItem item(String text, Runnable action) {
    JMenuItem item = new JMenuItem(text);
    item.addActionListener(e -> action.run());
    return item;
  }

  private void copy(String text, String message) {
    CopyPasteManager.getInstance().setContents(new StringSelection(text));
    Notify.info(project, message);
  }

  private void copyTsv() {
    StringBuilder sb = new StringBuilder(String.join("\t", result.columns())).append('\n');
    for (int row : chosenRows()) {
      List<Object> values = result.rows().get(row);
      for (int i = 0; i < values.size(); i++) {
        if (i > 0) sb.append('\t');
        Object v = values.get(i);
        if (v != null) sb.append(text(v).replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
      }
      sb.append('\n');
    }
    CopyPasteManager.getInstance().setContents(new StringSelection(sb.toString()));
    Notify.info(project, EiDbBundle.message("result.copied.rows", chosenRows().length));
  }

  /** 저장할 파일을 묻는다. 취소하면 null. */
  private Path askSavePath(String title, String description, String extension) {
    FileSaverDescriptor descriptor = new FileSaverDescriptor(title, description, extension);
    VirtualFileWrapper target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        .save((table == null ? "result" : table) + "." + extension);
    return target == null ? null : target.getFile().toPath();
  }

  /** 화면에 보이는(거르고 정렬된) 행을 보이는 순서대로. 값은 처음 읽은 값(반영 전 고친 값 제외). */
  private List<List<Object>> visibleRows() {
    List<List<Object>> rows = new ArrayList<>();
    for (int i = 0; i < grid.getRowCount(); i++) rows.add(result.rows().get(grid.convertRowIndexToModel(i)));
    return rows;
  }

  private void exportCsv() {
    Path path = askSavePath(EiDbBundle.message("result.csv.export"), EiDbBundle.message("result.csv.export.description"), "csv");
    if (path == null) return;
    StringBuilder sb = new StringBuilder(BOM);
    appendCsvLine(sb, List.copyOf(result.columns()));
    for (List<Object> row : visibleRows()) appendCsvLine(sb, row);
    String csv = sb.toString();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        Files.writeString(path, csv, StandardCharsets.UTF_8);
        Notify.info(project, EiDbBundle.message("result.csv.saved", path));
      } catch (IOException e) {
        Notify.error(project, EiDbBundle.message("result.csv.failed", e.getMessage()));
      }
    });
  }

  private void exportXlsx() {
    Path path = askSavePath(EiDbBundle.message("result.excel.export"), EiDbBundle.message("result.excel.export.description"), "xlsx");
    if (path == null) return;
    List<List<Object>> rows = visibleRows();
    List<String> comments = commentRow();
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        XlsxExport.write(path, result.columns(), comments, rows);
        Notify.info(project, EiDbBundle.message("result.excel.saved", path));
      } catch (IOException e) {
        Notify.error(project, EiDbBundle.message("result.excel.failed", e.getMessage()));
      }
    });
  }

  /** 칼럼 순서대로 한글 주석(없으면 빈 글자). 주석이 하나도 없으면 null. */
  private List<String> commentRow() {
    Map<String, String> comments = result.comments();
    if (comments == null) return null;
    List<String> out = new ArrayList<>();
    boolean any = false;
    for (String name : result.columns()) {
      String comment = comments.getOrDefault(name, "");
      out.add(comment == null ? "" : comment);
      any |= comment != null && !comment.isBlank();
    }
    return any ? out : null;
  }

  private static void appendCsvLine(StringBuilder sb, List<?> values) {
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) sb.append(',');
      Object v = values.get(i);
      if (v == null) continue;
      String s = text(v);
      if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) s = "\"" + s.replace("\"", "\"\"") + "\"";
      sb.append(s);
    }
    sb.append("\r\n");
  }

  private void copyInserts() {
    String target = table == null ? null : writtenTable();
    if (target == null) {
      target = Messages.showInputDialog(project, EiDbBundle.message("result.insert.table"), EiDbBundle.message("result.insert.title"), null);
      if (target == null || target.isBlank()) return;
      target = target.strip();
    }
    List<List<Object>> rows = new ArrayList<>();
    for (int row : chosenRows()) rows.add(result.rows().get(row));
    String sql;
    try {
      sql = SqlText.inserts(target, result.columns(), result.columnTypes(), rows, config.kind);
    } catch (IllegalArgumentException e) {
      Notify.error(project, e.getMessage());
      return;
    }
    CopyPasteManager.getInstance().setContents(new StringSelection(sql));
    Notify.info(project, EiDbBundle.message("result.insert.copied", rows.size()) + lobNotice(sql));
  }

  /** 복사 문장에 쓸 표 이름: 원래 문장에 쓴 그대로(따옴표 포함). 찾지 못하면 따옴표를 뗀 table. */
  private String writtenTable() {
    String ref = ResultView.tableRef(result.sql());
    return ref != null ? ref : table;
  }

  /** 고른 행(없으면 전체)을 UPDATE 문으로 복사. PK 는 접속해서 표 정의로 찾으므로 뒤에서 읽은 뒤 복사한다. */
  private void copyUpdates(DbConfig c) {
    String target = table == null ? null : writtenTable();
    if (target == null) {
      target = Messages.showInputDialog(project, EiDbBundle.message("result.update.table"), EiDbBundle.message("result.update.title"), null);
      if (target == null || target.isBlank()) return;
      target = target.strip();
    }
    String name = target;
    int[] rowIndexes = chosenRows();
    List<List<Object>> rows = new ArrayList<>();
    for (int row : rowIndexes) rows.add(result.rows().get(row));
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      Set<String> pks = new HashSet<>();
      try {
        for (Meta.Column col : tableColumns(c, name).columns()) {
          if (col.pk()) pks.add(col.name().toUpperCase(Locale.ROOT));
        }
      } catch (Exception e) {
        // PK 를 못 읽어도 첫 칼럼 조건 + 확인 주석으로 만든다(SqlText.updates).
      }
      String sql;
      try {
        sql = SqlText.updates(name, result.columns(), result.columnTypes(), rows, pks, c.kind);
      } catch (IllegalArgumentException e) {
        Notify.error(project, e.getMessage());
        return;
      }
      ApplicationManager.getApplication().invokeLater(() -> {
        CopyPasteManager.getInstance().setContents(new StringSelection(sql));
        Notify.info(project, EiDbBundle.message("result.update.copied", rows.size()) + (pks.isEmpty() ? EiDbBundle.message("result.update.no.pk") : "") + lobNotice(sql));
      }, project.getDisposed());
    });
  }

  private static String lobNotice(String sql) {
    return sql.startsWith(SqlText.LOB_EXCLUDED) ? EiDbBundle.message("result.lob.excluded") : "";
  }

  /**
   * 표 정의 칼럼(따옴표를 뗀 이름으로). 조회 문장에 「스키마.표」 로 썼으면 그 스키마에서, 아니면 접속 스키마에서 찾는다.
   * Oracle 은 대문자, PostgreSQL 은 소문자로 저장되므로 쓴 그대로 · 대문자 · 소문자 순서로 찾는다. 못 찾으면 빈 목록. 화면 스레드에서 부르지 않는다.
   */
  private TableDef tableColumns(DbConfig c, String name) throws Exception {
    String bare = name.substring(name.lastIndexOf('.') + 1).replace("\"", "");
    ei.db.bench.SqlParts.TableRef ref = FollowRef.singleTable(result.sql());
    String written = ref != null && ref.schema() != null ? Meta.storedName(c, ref.schema()) : null;
    DbSessions sessions = DbSessions.get(project);
    Connection conn = sessions.connection(c);
    try {
      for (String candidate : new String[]{bare, bare.toUpperCase(Locale.ROOT), bare.toLowerCase(Locale.ROOT)}) {
        List<Meta.Column> columns = SchemaCache.columns(conn, c, written != null ? written : Meta.schemaOf(c), candidate);
        if (!columns.isEmpty()) return new TableDef(written, candidate, columns);
      }
      return new TableDef(written, bare, List.of());
    } finally {
      sessions.endReadTransaction(c);
    }
  }

  /** 찾은 표 이름(저장된 대소문자 그대로)과 그 칼럼. 못 찾으면 칼럼이 빈 목록. schema 는 문장에 쓴 스키마(저장된 이름), 안 썼으면 null. */
  private record TableDef(String schema, String name, List<Meta.Column> columns) {
    /** UPDATE · DELETE · 백업에 쓸 표 표기. 문장에 스키마를 썼으면 「스키마.표」(접속 스키마의 같은 이름 표를 고치지 않게). */
    String sqlName(DbKind kind) {
      return (schema == null ? "" : SqlText.ident(kind, schema) + ".") + SqlText.ident(kind, name);
    }
  }

  /**
   * 칸 두 번 누름: 고치기 시작. 처음엔 표 정의를 뒤에서 읽어 PK 를 찾고, 고칠 수 없으면 이유를 알린다.
   * 고칠 수 있는 칸이면 JTable 이 이미 편집을 시작했으므로 그대로 둔다.
   */
  private void beginEdit(int viewRow, int viewColumn) {
    int col = grid.convertColumnIndexToModel(viewColumn) - 1;
    if (col < 0 || grid.isEditing()) return;
    if (table == null) {
      Notify.error(project, EiDbBundle.message("result.edit.single.table"));
      return;
    }
    if (pkColumns == null && editBlocked == null) {
      loadEditInfo(() -> beginEdit(viewRow, viewColumn));
      return;
    }
    String reason = editBlocked != null ? editBlocked : cellBlocked(col);
    if (reason == null && viewRow < grid.getRowCount() && isDeletedRow(grid.convertRowIndexToModel(viewRow))) {
      reason = EiDbBundle.message("backup.grid.row.deleted");
    }
    if (reason != null) {
      Notify.error(project, reason);
      return;
    }
    if (viewRow < grid.getRowCount()) grid.editCellAt(viewRow, viewColumn);
  }

  /** 표 정의를 뒤에서 읽어 고칠 수 있는지(PK · 칼럼)를 채운 뒤 then 을 화면 스레드에서 부른다. 조회 문장부터 고칠 수 없거나 읽지 못하면 알리고 then 은 부르지 않는다. */
  private void loadEditInfo(Runnable then) {
    String early = config.readOnly ? EiDbBundle.message("result.edit.read.only") : SqlText.notEditable(result.sql());
    if (early != null) {
      editBlocked = early;
      Notify.error(project, early);
      return;
    }
    if (loadingEdit) return;
    loadingEdit = true;
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      try {
        TableDef def = tableColumns(config, table);
        ApplicationManager.getApplication().invokeLater(() -> {
          loadingEdit = false;
          boolean[] keys = new boolean[result.columns().size()];
          String[] names = new String[keys.length];
          editBlocked = editBlockReason(def.columns(), keys, names);
          editTable = def.sqlName(config.kind);
          editColumns = names;
          pkColumns = keys;
          missingColumns = countMissing(def.columns());
          then.run();
        }, project.getDisposed());
      } catch (Exception e) {
        // 다음 두 번 누름에서 다시 읽게 상태를 두지 않는다.
        ApplicationManager.getApplication().invokeLater(() -> loadingEdit = false, project.getDisposed());
        Notify.error(project, EiDbBundle.message("result.edit.no.definition", ResultView.message(e)));
      }
    });
  }

  /**
   * 결과 칼럼이 모두 이 표의 칼럼이고 PK 칼럼이 모두 결과에 있어야 고칠 수 있다. keys 에 결과 칼럼마다 PK 여부를,
   * names 에 그 칼럼의 SQL 표기(표 정의 이름 그대로를 SqlText.ident 로)를 채운다.
   */
  private String editBlockReason(List<Meta.Column> defs, boolean[] keys, String[] names) {
    if (defs.isEmpty()) return EiDbBundle.message("result.edit.table.missing", table);
    Map<String, Meta.Column> byName = new HashMap<>();
    for (Meta.Column d : defs) byName.put(d.name().toUpperCase(Locale.ROOT), d);
    boolean anyPk = false;
    for (Meta.Column d : defs) anyPk |= d.pk();
    if (!anyPk) return EiDbBundle.message("result.edit.no.pk", table);
    for (int i = 0; i < keys.length; i++) {
      Meta.Column d = byName.get(result.columns().get(i).toUpperCase(Locale.ROOT));
      if (d == null) return EiDbBundle.message("result.edit.column.missing", result.columns().get(i), table);
      boolean pk = d.pk();
      keys[i] = pk;
      names[i] = SqlText.ident(config.kind, d.name());
      if (pk && SqlText.isUneditableType(typeOf(i))) return EiDbBundle.message("result.edit.pk.binary", result.columns().get(i));
    }
    Set<String> shown = new HashSet<>();
    for (String name : result.columns()) shown.add(name.toUpperCase(Locale.ROOT));
    for (Meta.Column d : defs) {
      if (d.pk() && !shown.contains(d.name().toUpperCase(Locale.ROOT))) return EiDbBundle.message("result.edit.pk.missing", d.name());
    }
    return null;
  }

  /** 이 칼럼(결과 칼럼 번호)만 고칠 수 없는 이유. 고칠 수 있으면 null. */
  private String cellBlocked(int col) {
    String name = result.columns().get(col);
    if (pkColumns[col]) return EiDbBundle.message("result.edit.pk.column", name);
    if (SqlText.isUneditableType(typeOf(col))) return EiDbBundle.message("result.edit.lob.column", name);
    return null;
  }

  private String typeOf(int col) {
    return col < result.columnTypes().size() ? result.columnTypes().get(col) : "";
  }

  private boolean isEditable(int col) {
    return pkColumns != null && editBlocked == null && cellBlocked(col) == null;
  }

  /** 편집기에서 받은 글을 칼럼 형식 값으로 바꿔 둔다. 처음 값과 같아지면 고친 표시를 지운다. 형식이 틀리면 알리고 두지 않는다. */
  private void edit(int row, int col, String text) {
    Object value;
    try {
      value = SqlText.parseEdit(text, typeOf(col));
    } catch (IllegalArgumentException e) {
      Notify.error(project, result.columns().get(col) + ": " + e.getMessage());
      return;
    }
    Map<Integer, Object> changed = edits.computeIfAbsent(row, k -> new TreeMap<>());
    if (literal(value).equals(literal(result.rows().get(row).get(col)))) changed.remove(col);
    else changed.put(col, value);
    if (changed.isEmpty()) edits.remove(row);
    updateEditButtons();
  }

  /** 반영 · 되돌리기의 보임과 건수는 도구줄이 BarAction.update 로 다시 판정한다. */
  private void updateEditButtons() {
    actions.updateActionsAsync();
  }

  private void revertEdits() {
    if (grid.isEditing()) grid.getCellEditor().cancelCellEditing();
    edits.clear();
    deleted.clear();
    updateEditButtons();
    grid.repaint();
  }

  private boolean hasPending() {
    return !edits.isEmpty() || !deleted.isEmpty();
  }

  /** 「행 지우기」를 보일지: 표 하나를 조회한 결과이고 읽기 전용 접속이 아닐 때. 실제로 지울 수 있는지(PK · 모든 칼럼)는 누를 때 표 정의로 판정한다. */
  private boolean canOfferDelete() {
    return table != null && !config.readOnly;
  }

  private boolean isDeletedRow(int modelRow) {
    return !deleted.isEmpty() && modelRow >= 0 && modelRow < result.rows().size() && deleted.contains(result.rows().get(modelRow));
  }

  private static Font struck(Font font) {
    return font.deriveFont(Map.of(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON));
  }

  /** 표의 칼럼 중 결과 칼럼에 없는 것의 수(대소문자 무시). */
  private int countMissing(List<Meta.Column> defs) {
    Set<String> shown = new HashSet<>();
    for (String name : result.columns()) shown.add(name.toUpperCase(Locale.ROOT));
    int missing = 0;
    for (Meta.Column d : defs) {
      if (!shown.contains(d.name().toUpperCase(Locale.ROOT))) missing++;
    }
    return missing;
  }

  /** 지금 그 행 객체가 있는 모델 행 번호. 그 사이 표에서 빠졌으면 -1. */
  private int indexOfRow(List<Object> row) {
    List<List<Object>> rows = result.rows();
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i) == row) return i;
    }
    return -1;
  }

  /** 고른 칸이 걸친 행들에 지움 표시를 한다. 실제 DELETE 는 「적용」 때 나간다. 고른 행이 없으면 알린다. */
  private void deleteRows() {
    if (grid.isEditing() && !grid.getCellEditor().stopCellEditing()) return;
    int[] selected = grid.getSelectedRows();
    if (selected.length == 0) {
      Notify.error(project, EiDbBundle.message("backup.grid.delete.none"));
      return;
    }
    Set<List<Object>> picked = Collections.newSetFromMap(new IdentityHashMap<>());
    for (int view : selected) picked.add(result.rows().get(grid.convertRowIndexToModel(view)));
    markDeleted(picked);
  }

  /**
   * 고치기와 같은 조건(표 하나 조회 · PK 가 결과에 있음 · 읽기 전용 아님)에 더해 표의 모든 칼럼이 결과에 있을 때만 표시한다.
   * 처음이면 표 정의를 뒤에서 읽은 뒤 이어서 한다. 표시한 행의 고친 값은 버린다.
   */
  private void markDeleted(Set<List<Object>> picked) {
    if (table == null) {
      Notify.error(project, EiDbBundle.message("result.edit.single.table"));
      return;
    }
    if (pkColumns == null && editBlocked == null) {
      loadEditInfo(() -> markDeleted(picked));
      return;
    }
    if (editBlocked != null) {
      Notify.error(project, editBlocked);
      return;
    }
    if (missingColumns > 0) {
      Notify.error(project, EiDbBundle.message("backup.grid.delete.partial", table, missingColumns));
      return;
    }
    if (grid.isEditing()) grid.getCellEditor().cancelCellEditing();
    List<List<Object>> rows = result.rows();
    for (int i = 0; i < rows.size(); i++) {
      if (!picked.contains(rows.get(i))) continue;
      deleted.add(rows.get(i));
      edits.remove(i);
    }
    updateEditButtons();
    grid.repaint();
  }

  /** 반영된 DELETE 의 행을 화면 표에서 뺀다: 그 뒤 행들의 고친 값(edits)은 행 번호를 하나씩 당기고, 건수 꼬리표를 고친다. */
  private void removeRow(int at) {
    if (grid.isEditing()) grid.getCellEditor().cancelCellEditing();
    deleted.remove(result.rows().remove(at));
    Map<Integer, Map<Integer, Object>> moved = new TreeMap<>();
    for (Map.Entry<Integer, Map<Integer, Object>> e : edits.entrySet()) {
      if (e.getKey() != at) moved.put(e.getKey() > at ? e.getKey() - 1 : e.getKey(), e.getValue());
    }
    edits.clear();
    edits.putAll(moved);
    ((Model) grid.getModel()).fireTableRowsDeleted(at, at);
    int total = result.rows().size();
    showCount(searchBox.getText().isEmpty() ? EiDbBundle.message("common.rows", total) : EiDbBundle.message("result.filtered", grid.getRowCount(), total));
    updateEditButtons();
  }

  /**
   * 고친 행마다 UPDATE 문(SET 은 고친 칼럼, WHERE 는 PK 처음 값), 지움 표시한 행마다 DELETE 문(WHERE 는 PK)을 만들어 미리보기로 확인받고
   * SqlRunner 로 차례로(UPDATE 먼저, 그다음 DELETE) 실행한다. 실행은 ? 자리 + binds 로 하고(값 이스케이프를 드라이버에 맡김) 미리보기에만 리터럴 문장을 보인다.
   * 확인받은 뒤 첫 문장 전에 고치기 전 백업을 남긴다: 고친 칸은 표가 들고 있는 처음 값으로 되돌리는 UPDATE, 지울 행은 INSERT(다시 조회하지 않는다).
   * 백업을 남기지 못하면 백업 없이 실행할지 묻고, 취소하면 아무것도 실행하지 않는다. SqlRunner 에는 백업을 맡기지 않는다(이중 백업 금지).
   * 운영 접속 확인 · 읽기 전용 거절은 SqlRunner 가 한다.
   * 앞 문장이 행을 바꾼 뒤에만 다음 문장을 실행하고, 그 행만 화면 값을 고친 값으로 바꾸고 고친 표시를 지운다(DELETE 는 그 행을 화면 표에서 뺀다).
   * 오류 · 취소 · 거절이면 그 행부터 고친 표시 · 지움 표시를 남겨 다시 반영할 수 있게 한다. 바뀐 행이 0 이면 알리고 멈춘다.
   * 행은 번호가 아니라 행 객체로 기억한다(실행 중 다른 행이 빠져 번호가 밀려도 맞는 행을 고친다).
   */
  private void applyEdits() {
    if (applying || (grid.isEditing() && !grid.getCellEditor().stopCellEditing())) return;
    if (!hasPending()) return;
    List<List<Object>> rows = new ArrayList<>();
    List<Map<Integer, Object>> values = new ArrayList<>();
    List<SqlText.Update> statements = new ArrayList<>();
    List<String> undo = new ArrayList<>();
    for (Map.Entry<Integer, Map<Integer, Object>> e : edits.entrySet()) {
      rows.add(result.rows().get(e.getKey()));
      values.add(new TreeMap<>(e.getValue()));
      statements.add(updateStatement(e.getKey(), e.getValue()));
      undo.add(undoStatement(e.getKey(), e.getValue()));
    }
    List<List<Object>> doomed = new ArrayList<>();
    List<SqlText.Update> deletes = new ArrayList<>();
    for (List<Object> row : result.rows()) {
      if (!deleted.contains(row)) continue;
      doomed.add(row);
      deletes.add(deleteStatement(row));
    }
    List<SqlText.Update> all = new ArrayList<>(statements);
    all.addAll(deletes);
    if (!confirmApply(all, !deletes.isEmpty())) return;
    List<Backups.Draft> drafts = backupDrafts(statements, undo, doomed, deletes);
    if (drafts == null) return;
    applying = true;
    updateEditButtons();
    Runnable thenDelete = () -> deleteNext(doomed, deletes, 0, statements.size());
    Backups.saveThen(project, config, drafts, () -> {
      if (statements.isEmpty()) thenDelete.run();
      else applyNext(rows, values, statements, 0, thenDelete);
    }, this::endApply);
  }

  private void endApply() {
    applying = false;
    updateEditButtons();
  }

  /**
   * 반영 전에 남길 고치기 전 백업. 백업이 꺼져 있으면 빈 목록.
   * 지울 행의 INSERT 를 만들 수 없으면(LOB · 이진 칼럼뿐) 백업 없이 실행할지 묻고, 취소하면 null.
   */
  private List<Backups.Draft> backupDrafts(List<SqlText.Update> updates, List<String> undo, List<List<Object>> doomed, List<SqlText.Update> deletes) {
    List<Backups.Draft> drafts = new ArrayList<>();
    if (!Backups.enabled()) return drafts;
    if (!undo.isEmpty()) {
      drafts.add(new Backups.Draft(BackupSql.Origin.GRID_EDIT, editTable, previews(updates), undo.size(),
          new BackupSql.Restore(String.join("\n", undo), List.of(), BackupSql.Mode.UPDATE)));
    }
    if (!doomed.isEmpty()) {
      try {
        drafts.add(new Backups.Draft(BackupSql.Origin.GRID_DELETE, editTable, previews(deletes), doomed.size(),
            BackupSql.restore(true, editTable, result.columns(), result.columnTypes(), doomed, Set.of(), config.kind)));
      } catch (IllegalArgumentException e) {
        if (!Backups.ask(project, EiDbBundle.message("backup.failed.ask", e.getMessage()))) return null;
      }
    }
    return drafts;
  }

  private static List<String> previews(List<SqlText.Update> statements) {
    List<String> out = new ArrayList<>();
    for (SqlText.Update u : statements) out.add(u.preview());
    return out;
  }

  /** applyEdits 의 i 번째 UPDATE 를 실행한다. 실행 중 사용자가 같은 칸을 다시 고쳤으면 그 칸의 고친 표시는 남긴다. 모두 끝나면 then. */
  private void applyNext(List<List<Object>> rows, List<Map<Integer, Object>> values, List<SqlText.Update> statements, int i, Runnable then) {
    SqlText.Update u = statements.get(i);
    SqlRunner.run(project, config, u.sql(), u.binds(), done -> {
      int row = indexOfRow(rows.get(i));
      if (done.updateCount() == 0) {
        Notify.error(project, EiDbBundle.message("result.apply.missed", row + 1, i, statements.size()));
        endApply();
        return;
      }
      // 그 사이 표에서 빠진 행이면 화면에 고칠 것이 없다.
      if (row >= 0) {
        Map<Integer, Object> changed = edits.get(row);
        for (Map.Entry<Integer, Object> v : values.get(i).entrySet()) {
          result.rows().get(row).set(v.getKey(), v.getValue());
          if (changed != null && literal(v.getValue()).equals(literal(changed.get(v.getKey())))) changed.remove(v.getKey());
        }
        if (changed != null && changed.isEmpty()) edits.remove(row);
      }
      updateEditButtons();
      grid.repaint();
      if (i + 1 < statements.size()) applyNext(rows, values, statements, i + 1, then);
      else then.run();
    }, null, this::endApply);
  }

  /**
   * applyEdits 의 i 번째 DELETE 를 실행하고, 지워졌으면 그 행을 화면 표에서 뺀다. 지운 행이 0 이면 알리고 멈춘다(지움 표시는 남는다).
   * updated 는 앞서 반영한 UPDATE 문 수. 모두 끝나면 반영한 문장 수를 알린다.
   */
  private void deleteNext(List<List<Object>> doomed, List<SqlText.Update> deletes, int i, int updated) {
    if (i >= deletes.size()) {
      Notify.info(project, deletes.isEmpty() ? EiDbBundle.message("result.apply.done", updated)
          : EiDbBundle.message("backup.grid.apply.done", updated + deletes.size(), updated, deletes.size()));
      endApply();
      return;
    }
    SqlText.Update d = deletes.get(i);
    SqlRunner.run(project, config, d.sql(), d.binds(), done -> {
      int row = indexOfRow(doomed.get(i));
      if (done.updateCount() == 0) {
        Notify.error(project, EiDbBundle.message("backup.grid.delete.missed", row + 1, updated + i, updated + deletes.size()));
        endApply();
        return;
      }
      if (row >= 0) removeRow(row);
      deleteNext(doomed, deletes, i + 1, updated);
    }, null, this::endApply);
  }

  /** 고친 칸을 표가 들고 있는 처음 값으로 되돌리는 UPDATE 문(리터럴, 고치기 전 백업용). WHERE 는 PK 처음 값. */
  private String undoStatement(int row, Map<Integer, Object> changed) {
    List<String> names = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    for (Integer col : changed.keySet()) {
      names.add(editColumns[col]);
      values.add(result.rows().get(row).get(col));
    }
    int setCount = names.size();
    for (int i = 0; i < pkColumns.length; i++) {
      if (!pkColumns[i]) continue;
      names.add(editColumns[i]);
      values.add(result.rows().get(row).get(i));
    }
    boolean[] key = new boolean[names.size()];
    Arrays.fill(key, setCount, key.length, true);
    return SqlText.update(editTable, names, values, key, config.kind);
  }

  /** 그 행을 PK 로 찾아 지우는 DELETE 문. */
  private SqlText.Update deleteStatement(List<Object> row) {
    List<String> names = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    for (int i = 0; i < pkColumns.length; i++) {
      if (!pkColumns[i]) continue;
      names.add(editColumns[i]);
      values.add(row.get(i));
    }
    return BackupSql.delete(editTable, names, values, config.kind);
  }

  private SqlText.Update updateStatement(int row, Map<Integer, Object> changed) {
    List<String> names = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    for (Map.Entry<Integer, Object> v : changed.entrySet()) {
      names.add(editColumns[v.getKey()]);
      values.add(v.getValue());
    }
    int setCount = names.size();
    for (int i = 0; i < pkColumns.length; i++) {
      if (!pkColumns[i]) continue;
      names.add(editColumns[i]);
      values.add(result.rows().get(row).get(i));
    }
    boolean[] key = new boolean[names.size()];
    Arrays.fill(key, setCount, key.length, true);
    return SqlText.updateWithBinds(editTable, names, values, key);
  }

  private boolean confirmApply(List<SqlText.Update> statements, boolean withDeletes) {
    List<String> previews = new ArrayList<>();
    for (SqlText.Update u : statements) previews.add(u.preview());
    JBTextArea text = new JBTextArea(String.join("\n", previews));
    text.setEditable(false);
    text.setFont(monoFont());
    JBScrollPane scroll = new JBScrollPane(text);
    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
    scroll.setPreferredSize(new Dimension((int) (screen.width * PREVIEW_WIDTH_RATIO), (int) (screen.height * PREVIEW_HEIGHT_RATIO)));
    JBLabel note = new JBLabel(EiDbBundle.message("result.apply.note", config.name) + (DbSessions.get(project).autoCommit(config) ? "" : EiDbBundle.message("result.apply.note.commit")));
    note.setBorder(JBUI.Borders.emptyBottom(8));
    DialogBuilder dialog = new DialogBuilder(project);
    dialog.setDimensionServiceKey(PREVIEW_DIMENSION_KEY);
    dialog.setTitle(EiDbBundle.message(withDeletes ? "backup.grid.apply.title" : "result.apply.title", statements.size()));
    dialog.setNorthPanel(note);
    dialog.setCenterPanel(scroll);
    dialog.addOkAction().setText(EiDbBundle.message("common.run"));
    dialog.addCancelAction();
    return dialog.showAndGet();
  }

  /** ANSI 표기(TIMESTAMP · DATE 글자)라 Oracle · MySQL · PostgreSQL 에서 같이 쓴다. */
  static String literal(Object v) {
    return SqlText.literal(v);
  }

  static String text(Object v) {
    return SqlText.text(v);
  }

  /** 0번 칸은 행 번호. 머리글은 칼럼 이름 + 한글 주석(작고 흐리게) 두 줄. 고친 값(edits)이 있으면 그 값을 보인다. */
  private final class Model extends AbstractTableModel {
    private final QueryResult result;
    private final String[] headers;

    Model(QueryResult result) {
      this.result = result;
      Map<String, String> comments = result.comments();
      boolean anyComment = comments != null && !comments.isEmpty();
      String muted = ColorUtil.toHex(UIUtil.getContextHelpForeground());
      headers = new String[result.columns().size() + 1];
      headers[0] = "#";
      for (int i = 0; i < result.columns().size(); i++) {
        String name = result.columns().get(i);
        if (!anyComment) {
          headers[i + 1] = "<html><b>" + StringUtil.escapeXmlEntities(name) + "</b></html>";
          continue;
        }
        String comment = comments.get(name);
        String second = comment == null || comment.isBlank() ? "&nbsp;" : StringUtil.escapeXmlEntities(comment);
        headers[i + 1] = "<html><b>" + StringUtil.escapeXmlEntities(name) + "</b><br><font size='-1' color='#" + muted + "'>" + second + "</font></html>";
      }
    }

    @Override public int getRowCount() { return result.rows().size(); }
    @Override public int getColumnCount() { return headers.length; }
    @Override public String getColumnName(int column) { return headers[column]; }
    @Override public boolean isCellEditable(int row, int column) { return column > 0 && isEditable(column - 1) && !isDeletedRow(row); }

    @Override
    public Object getValueAt(int row, int column) {
      if (column == 0) return new RowNumber(row + 1);
      return isEdited(row, column) ? edits.get(row).get(column - 1) : result.rows().get(row).get(column - 1);
    }

    @Override
    public void setValueAt(Object value, int row, int column) {
      edit(row, column - 1, (String) value);
      fireTableRowsUpdated(row, row);
    }

    boolean isDeleted(int row) { return isDeletedRow(row); }

    boolean isEdited(int row, int column) {
      Map<Integer, Object> changed = edits.get(row);
      return column > 0 && changed != null && changed.containsKey(column - 1);
    }
  }

  /** 고칠 때는 보이는 글(숫자는 지수 표기 없이) 그대로 시작하고, null 은 빈 칸으로 시작한다. */
  private static final class ValueEditor extends DefaultCellEditor {
    ValueEditor() { super(new JBTextField()); }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean selected, int row, int column) {
      return super.getTableCellEditorComponent(table, value == null ? "" : text(value), selected, row, column);
    }
  }

  private record RowNumber(int value) {
    @Override public String toString() { return String.valueOf(value); }
  }

  /** 고친 칸이면 고친 칸 바탕, 행 번호 칸이면 머리와 같은 바탕, 아니면 null(표 · 줄무늬 바탕). */
  private static Color cellBackground(JTable table, int row, int column) {
    int modelColumn = table.convertColumnIndexToModel(column);
    if (table.getModel() instanceof Model m && m.isDeleted(table.convertRowIndexToModel(row))) return DELETED_BACKGROUND;
    if (table.getModel() instanceof Model m && m.isEdited(table.convertRowIndexToModel(row), modelColumn)) return EDITED_BACKGROUND;
    return modelColumn == 0 ? UIUtil.getPanelBackground() : null;
  }

  /**
   * NULL 은 작은 흐린 기울임 「NULL」, 값은 고정폭(코드 글꼴), 숫자는 천 단위 쉼표 · 숫자 색, 숫자 · 행 번호는 오른쪽 정렬.
   * 쉼표는 화면 글(SqlText.display)에만 넣고 칸 값은 그대로라 복사 · 내보내기 · 고치기는 원래 값을 쓴다.
   */
  private static final class CellRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int column) {
      // setBackground 는 다음 칸에도 남는 바탕색이 되므로 매번 비우고 시작한다(고친 칸 바탕은 표의 prepareRenderer 가 칠한다).
      setBackground(null);
      boolean number = value instanceof Number;
      super.getTableCellRendererComponent(table, value == null ? NULL_TEXT : number ? SqlText.display(value) : text(value), selected, focused, row, column);
      Font font = table.getFont();
      if (value == null) font = font.deriveFont(Font.ITALIC, UIUtil.getFontSize(UIUtil.FontSize.SMALL));
      else if (!(value instanceof RowNumber)) font = monoFont().deriveFont(font.getSize2D());
      setFont(font);
      boolean muted = value == null || value instanceof RowNumber;
      // DefaultTableCellRenderer 는 지정한 글자색을 다음 칸에도 쓰므로 매번 정한다.
      if (!selected) setForeground(muted ? UIUtil.getContextHelpForeground() : number ? UiColors.NUMBER : table.getForeground());
      setHorizontalAlignment(number || value instanceof RowNumber ? SwingConstants.RIGHT : SwingConstants.LEFT);
      return this;
    }
  }
}
