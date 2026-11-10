package ei.db.mybatis;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import ei.db.EiDbBundle;
import ei.db.Notify;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 「주석 만들기」 설정(IDE 전체): 작성자(비우면 Git user.name), 날짜(@since) 넣기, 사용자 사전.
 * 사전 글은 기본 사전(resources/doc/DocWords.properties)과 같은 꼴로, 기본 위에 덮는다.
 */
public final class DocCommentSettings {
  private DocCommentSettings() {}

  private static final String KEY_AUTHOR = "ei.db.doc.author";
  private static final String KEY_DATE = "ei.db.doc.date";
  private static final String KEY_WORDS = "ei.db.doc.words";
  private static final String DEFAULT_WORDS = "/doc/DocWords.properties";

  static boolean withDate() {
    return PropertiesComponent.getInstance().getBoolean(KEY_DATE, true);
  }

  /** 설정의 작성자. 비었으면 dir 이 든 저장소(없으면 사용자)의 Git user.name. 둘 다 없으면 null. 파일을 읽으므로 화면 스레드 밖에서 부른다. */
  static @Nullable String author(@Nullable Path dir) {
    String set = PropertiesComponent.getInstance().getValue(KEY_AUTHOR, "").trim();
    if (!set.isEmpty()) return set;
    String home = System.getProperty("user.home");
    return dir == null ? null : DocComment.gitUserName(dir, home == null ? null : Path.of(home));
  }

  /** 기본 사전 + 사용자 사전. 기본 사전을 읽지 못하면 알리고 null. */
  static @Nullable DocComment.Dict dict(Project project) {
    try (InputStream in = DocCommentSettings.class.getResourceAsStream(DEFAULT_WORDS)) {
      if (in == null) throw new IOException(DEFAULT_WORDS);
      try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
        return DocComment.dict(r, PropertiesComponent.getInstance().getValue(KEY_WORDS, ""));
      }
    } catch (IOException | IllegalArgumentException e) {
      Notify.error(project, EiDbBundle.message("doccomment.dict.failed", e.getMessage()));
      return null;
    }
  }

  /** 설정 → 도구 → 주석 만들기. */
  public static final class Page implements Configurable {
    private JBTextField author;
    private JBCheckBox date;
    private JBTextArea words;

    @Override
    public @Nls String getDisplayName() { return EiDbBundle.message("doccomment.settings.title"); }

    @Override
    public JComponent createComponent() {
      author = new JBTextField();
      author.getEmptyText().setText(EiDbBundle.message("doccomment.settings.author.empty"));
      date = new JBCheckBox(EiDbBundle.message("doccomment.settings.date"));
      words = new JBTextArea();
      words.getEmptyText().setText(EiDbBundle.message("doccomment.settings.words.empty"));
      reset();
      return FormBuilder.createFormBuilder()
          .addLabeledComponent(EiDbBundle.message("doccomment.settings.author"), author)
          .addComponent(date)
          .addLabeledComponentFillVertically(EiDbBundle.message("doccomment.settings.words"), new JBScrollPane(words))
          .getPanel();
    }

    @Override
    public boolean isModified() {
      PropertiesComponent p = PropertiesComponent.getInstance();
      return !author.getText().trim().equals(p.getValue(KEY_AUTHOR, ""))
          || date.isSelected() != withDate()
          || !words.getText().equals(p.getValue(KEY_WORDS, ""));
    }

    @Override
    public void apply() {
      PropertiesComponent p = PropertiesComponent.getInstance();
      p.setValue(KEY_AUTHOR, author.getText().trim(), "");
      p.setValue(KEY_DATE, date.isSelected(), true);
      p.setValue(KEY_WORDS, words.getText(), "");
    }

    @Override
    public void reset() {
      PropertiesComponent p = PropertiesComponent.getInstance();
      author.setText(p.getValue(KEY_AUTHOR, ""));
      date.setSelected(withDate());
      words.setText(p.getValue(KEY_WORDS, ""));
    }
  }
}
