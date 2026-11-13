package ei.db.console;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.panels.HorizontalLayout;
import com.intellij.util.ui.JBUI;
import ei.db.DbConfig;
import ei.db.DbSessions;
import ei.db.EiDbBundle;
import ei.db.Meta;
import ei.db.Notify;
import ei.db.QueryResult;
import ei.db.SchemaCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;

/**
 * 결과 칸의 값을 따로 창에 크게 보인다(읽기 전용, 줄바꿈 켬, 모달 아님). 창 크기는 DIMENSION_KEY 로 기억한다.
 * JSON · XML 이면 뒤에서 줄을 맞춰 그 색으로 보이고 「원문 / 정렬」 로 바꿔 볼 수 있다. FORMAT_LIMIT 자를 넘으면 원문만 보인다.
 * 보이는 글이 WRAP_LIMIT 자를 넘으면 줄바꿈을 끄고 그렇다고 적는다.
 * 이진 값은 크기 안내만 보인다. 결과에 앞부분만 든 CLOB 은 그렇다고 적고, 표 하나를 조회한 결과면 「전체 읽기」로 그 칸을 PK 로 다시 읽는다
 * (FULL_READ_LIMIT 자까지). 다시 읽기에 실패하면 알리고 보이던 값을 그대로 둔다.
 */
final class BigValueDialog extends DialogWrapper {
  private static final String DIMENSION_KEY = "ei.db.result.bigValue";
  /** 처음 열 때 화면에 대한 크기 몫. */
  private static final double FIRST_WIDTH_RATIO = 0.5;
  private static final double FIRST_HEIGHT_RATIO = 0.6;
  /** 이 글자 수를 넘는 값은 줄을 맞추지 않는다. */
  private static final int FORMAT_LIMIT = 5_000_000;
  /** 「전체 읽기」가 읽는 글자 수 한도. */
  private static final int FULL_READ_LIMIT = FORMAT_LIMIT;
  /** 보이는 글이 이 글자 수를 넘으면 줄바꿈(소프트 랩)을 끈다: 줄바꿈 없는 큰 값의 줄 나누기 계산이 화면을 멈춘다. */
  private static final int WRAP_LIMIT = 1_000_000;
  private static final int READ_BUFFER = 1 << 16;
  /** 안내 줄 · 아래 줄의 칸 사이 간격과 편집기와의 간격(px, 배율 전). */
  private static final int GAP = 8;
  private static final String JSON_EXTENSION = "json";
  private static final String XML_EXTENSION = "xml";

  private final Project project;
  private final DbConfig config;
  private final QueryResult result;
  private final List<Object> row;
  private final int col;
  private final boolean binary;

  /** 보이는 값의 원문. 이진 값이면 안내 글. */
  private String raw;
  /** 원문을 줄 맞춘 것. 아직 없거나 JSON · XML 이 아니면 null. */
  private ValueFormat.Formatted formatted;
  private final EditorTextField editor;
  private final JBLabel notice = new JBLabel();
  private final JBLabel wrapNote = new JBLabel(EiDbBundle.message("bigvalue.no.wrap", WRAP_LIMIT));
  /** 지금 보이는 글에 줄바꿈을 켤지. 편집기를 새로 만들 때마다 읽는다. */
  private boolean wrap = true;
  private final ActionLink readFull = new ActionLink(EiDbBundle.message("bigvalue.read.full"), e -> { readFull(); });
  private final JBRadioButton rawButton = new JBRadioButton(EiDbBundle.message("bigvalue.raw"), true);
  private final JBRadioButton prettyButton = new JBRadioButton(EiDbBundle.message("bigvalue.pretty"));
  private final JBLabel status = new JBLabel();
  private final JBLabel length = new JBLabel();
  private final Action copy = new AbstractAction(EiDbBundle.message("common.copy")) {
    @Override
    public void actionPerformed(ActionEvent e) {
      CopyPasteManager.getInstance().setContents(new StringSelection(shownText()));
      Notify.info(project, EiDbBundle.message("result.copied.value"));
    }
  };

  /** row 는 그 행의 값(결과 칼럼 순서), col 은 결과 칼럼 번호. 그 칸의 값은 null 이 아니어야 한다. */
  BigValueDialog(Project project, DbConfig c, QueryResult result, List<Object> row, int col) {
    super(project, true);
    this.project = project;
    this.config = c;
    this.result = result;
    this.row = row;
    this.col = col;
    Object value = row.get(col);
    binary = value instanceof QueryResult.BinaryValue;
    editor = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, PlainTextFileType.INSTANCE, true, false);
    editor.addSettingsProvider(e -> {
      e.getSettings().setUseSoftWraps(wrap);
      e.getSettings().setLineNumbersShown(true);
      e.setVerticalScrollbarVisible(true);
      e.setHorizontalScrollbarVisible(true);
    });
    ButtonGroup group = new ButtonGroup();
    group.add(rawButton);
    group.add(prettyButton);
    rawButton.addActionListener(e -> showText());
    prettyButton.addActionListener(e -> showText());
    setModal(false);
    setTitle(EiDbBundle.message("bigvalue.title", result.columns().get(col)));
    setCancelButtonText(EiDbBundle.message("common.close"));
    init();
    if (binary) {
      showValue(EiDbBundle.message("bigvalue.binary", ((QueryResult.BinaryValue) value).length()), false);
    } else {
      // 잘린 CLOB 은 끝에 표식 한 글자가 붙어 있다(SqlText.isLobValue). 표식은 값이 아니므로 뗀다.
      boolean cut = SqlText.isLobValue(value);
      String text = SqlText.text(value);
      showValue(cut ? text.substring(0, text.length() - 1) : text, cut);
    }
  }

  @Override
  protected @Nullable String getDimensionServiceKey() { return DIMENSION_KEY; }

  @Override
  public Dimension getInitialSize() {
    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
    return new Dimension((int) (screen.width * FIRST_WIDTH_RATIO), (int) (screen.height * FIRST_HEIGHT_RATIO));
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[]{copy, getCancelAction()};
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() { return editor; }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JPanel top = new JPanel(new BorderLayout(JBUI.scale(GAP), 0));
    top.add(notice, BorderLayout.CENTER);
    top.add(readFull, BorderLayout.EAST);
    top.add(wrapNote, BorderLayout.SOUTH);
    wrapNote.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());
    notice.setForeground(UiColors.WARNING);
    JPanel modes = new JPanel(new HorizontalLayout(JBUI.scale(GAP), SwingConstants.CENTER));
    modes.add(rawButton);
    modes.add(prettyButton);
    JPanel bottom = new JPanel(new BorderLayout(JBUI.scale(GAP), 0));
    bottom.add(modes, BorderLayout.WEST);
    bottom.add(status, BorderLayout.CENTER);
    bottom.add(length, BorderLayout.EAST);
    JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(GAP)));
    panel.add(top, BorderLayout.NORTH);
    panel.add(editor, BorderLayout.CENTER);
    panel.add(bottom, BorderLayout.SOUTH);
    return panel;
  }

  /**
   * 값을 바꿔 보인다: 먼저 원문을 보이고, 글이면 뒤에서 줄을 맞춰 JSON · XML 이면 「정렬」 로 넘어간다.
   * cut 이면 앞부분만 든 값이라고 적고, 다시 읽을 수 있는 결과면 「전체 읽기」를 보인다.
   */
  private void showValue(String text, boolean cut) {
    raw = text;
    formatted = null;
    rawButton.setSelected(true);
    rawButton.setEnabled(!binary);
    prettyButton.setEnabled(false);
    copy.setEnabled(!binary);
    length.setText(binary ? "" : EiDbBundle.message("bigvalue.length", text.length()));
    notice.setText(cut ? EiDbBundle.message("bigvalue.truncated", text.length()) : "");
    notice.setVisible(cut);
    // 한도까지 읽고도 잘린 값은 다시 읽어도 같으므로 링크를 두지 않는다.
    readFull.setVisible(cut && text.length() < FULL_READ_LIMIT && canReadFull());
    readFull.setEnabled(true);
    status.setText("");
    showText();
    if (binary) return;
    if (text.length() > FORMAT_LIMIT) {
      status.setText(EiDbBundle.message("bigvalue.too.big", FORMAT_LIMIT));
      return;
    }
    status.setText(EiDbBundle.message("bigvalue.formatting"));
    ModalityState modality = ModalityState.stateForComponent(getContentPanel());
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      ValueFormat.Formatted done;
      try {
        done = ValueFormat.format(text);
      } catch (RuntimeException | OutOfMemoryError e) {
        done = null;
      }
      ValueFormat.Formatted f = done;
      ApplicationManager.getApplication().invokeLater(() -> {
        // 그 사이 창을 닫았거나 「전체 읽기」로 값이 바뀌었으면 버린다.
        if (isDisposed() || raw != text) return;
        status.setText("");
        if (f == null) return;
        formatted = f;
        prettyButton.setEnabled(true);
        prettyButton.setSelected(true);
        showText();
      }, modality);
    });
  }

  private boolean pretty() {
    return prettyButton.isSelected() && formatted != null;
  }

  private String shownText() {
    return pretty() ? formatted.text() : raw;
  }

  /** 고른 보기(원문 · 정렬)의 글을 그 파일 형식 색으로 보인다. 그 형식이 IDE 에 없으면(플러그인 꺼짐) 일반 글로 보인다. */
  private void showText() {
    FileType type = PlainTextFileType.INSTANCE;
    if (pretty()) {
      FileType known = FileTypeManager.getInstance().getFileTypeByExtension(formatted.kind() == ValueFormat.Kind.JSON ? JSON_EXTENSION : XML_EXTENSION);
      if (!(known instanceof UnknownFileType)) type = known;
    }
    String shown = shownText();
    wrap = shown.length() <= WRAP_LIMIT;
    wrapNote.setVisible(!wrap);
    // 편집기 문서는 줄 끝을 \n 만 받는다. 복사는 shownText 의 원래 글을 쓴다.
    editor.setNewDocumentAndFileType(type, EditorFactory.getInstance().createDocument(StringUtil.convertLineSeparators(shown)));
  }

  /** 그 칸을 PK 로 다시 읽을 수 있는 결과인지(표 하나를 조회해 행마다 표의 한 행인 결과: 결과 칸 고치기와 같은 조건). PK 는 누를 때 읽어 확인한다. */
  private boolean canReadFull() {
    return result.isQuery() && ResultView.firstTable(result.sql()) != null && SqlText.notEditable(result.sql()) == null;
  }

  private void readFull() {
    readFull.setEnabled(false);
    ModalityState modality = ModalityState.stateForComponent(getContentPanel());
    new Task.Backgroundable(project, EiDbBundle.message("bigvalue.full.progress", result.columns().get(col)), false) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        try {
          String full = load();
          boolean cut = full.length() > FULL_READ_LIMIT;
          String text = cut ? full.substring(0, FULL_READ_LIMIT) : full;
          ApplicationManager.getApplication().invokeLater(() -> {
            if (!isDisposed()) showValue(text, cut);
          }, modality);
        } catch (Exception e) {
          Notify.error(project, EiDbBundle.message("bigvalue.full.failed", ResultView.message(e)));
          ApplicationManager.getApplication().invokeLater(() -> readFull.setEnabled(true), modality);
        }
      }
    }.queue();
  }

  /**
   * 그 칸의 값을 처음부터 FULL_READ_LIMIT + 1 자까지 읽는다(한도를 넘었는지 알 수 있게 한 글자 더). 그 사이 값이 null 이 됐으면 빈 글.
   * 표 정의 · PK 를 못 찾거나 그 행이 없으면 이유를 담은 예외. 화면 스레드에서 부르지 않는다.
   */
  private String load() throws Exception {
    DbSessions sessions = DbSessions.get(project);
    Connection conn = sessions.connection(config);
    try {
      String bare = ResultView.firstTable(result.sql());
      FollowRef.CellQuery query = null;
      // 문장에 스키마를 붙여 썼으면 그 스키마의 표를 읽는다(접속 스키마의 같은 이름 표를 읽지 않게).
      ei.db.bench.SqlParts.TableRef ref = FollowRef.singleTable(result.sql());
      String written = ref != null && ref.schema() != null ? Meta.storedName(config, ref.schema()) : null;
      String schema = written != null ? written : Meta.schemaOf(config);
      // Oracle 은 대문자, PostgreSQL 은 소문자로 저장되므로 쓴 그대로 · 대문자 · 소문자 순서로 찾는다(ResultPanel 의 표 정의 찾기와 같다).
      for (String candidate : new String[]{bare, bare.toUpperCase(Locale.ROOT), bare.toLowerCase(Locale.ROOT)}) {
        List<Meta.Column> defs = SchemaCache.columns(conn, config, schema, candidate);
        if (defs.isEmpty()) continue;
        query = FollowRef.cellQuery(config.kind, written, candidate, defs, result.columns(), result.columnTypes(), row, col);
        break;
      }
      if (query == null) throw new IllegalStateException(EiDbBundle.message("bigvalue.full.no.key"));
      try (PreparedStatement ps = conn.prepareStatement(query.sql())) {
        for (int i = 0; i < query.binds().size(); i++) ps.setObject(i + 1, query.binds().get(i));
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) throw new IllegalStateException(EiDbBundle.message("bigvalue.full.no.row"));
          try (Reader reader = rs.getCharacterStream(1)) {
            return reader == null ? "" : read(reader);
          }
        }
      }
    } finally {
      sessions.endReadTransaction(config);
    }
  }

  private static String read(Reader reader) throws java.io.IOException {
    StringBuilder sb = new StringBuilder();
    char[] buffer = new char[READ_BUFFER];
    int n;
    while (sb.length() <= FULL_READ_LIMIT && (n = reader.read(buffer, 0, Math.min(buffer.length, FULL_READ_LIMIT + 1 - sb.length()))) > 0) {
      sb.append(buffer, 0, n);
    }
    return sb.toString();
  }
}
