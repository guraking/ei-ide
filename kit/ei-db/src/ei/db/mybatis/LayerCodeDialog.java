package ei.db.mybatis;

import com.intellij.ide.util.TreeClassChooser;
import com.intellij.ide.util.TreeClassChooserFactory;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import ei.db.EiDbBundle;
import ei.db.Notify;
import ei.db.gen.Names;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 매퍼 문장 하나에서 컨트롤러 · 서비스 인터페이스 · 서비스 구현 · 매퍼 인터페이스 메서드 글을 만들어 보이는 창.
 * 글은 고칠 수 있고 「복사」 하거나 고른 클래스에 넣는다. 「부모-자식」 탭은 부모 목록에 자식 목록을 키로 묶어 채우는 서비스 메서드를 만든다.
 * 클래스 찾기는 창을 열기 전에(gather) 화면 스레드 밖에서 한다.
 */
final class LayerCodeDialog extends DialogWrapper {
  private static final String JAVA_EXT = "java";
  private static final int CODE_WIDTH = 1200;
  private static final int CODE_HEIGHT = 180;
  private static final int JOIN_HEIGHT = 300;
  private static final int URL_COLUMNS = 50;
  private static final int LIST_NAME_COLUMNS = 40;
  private static final int SCROLL_HEIGHT = 700;
  private static final JBColor ERROR = JBColor.RED;

  /** 창이 쓰는 값. 클래스들은 찾지 못하면 null. */
  record Info(LayerCode.Located at, String id, ServiceCode.Sig sig, boolean mapperHasMethod, String mapperSimple,
              @Nullable PsiClass mapper, @Nullable PsiClass service, String mapperField,
              @Nullable PsiClass serviceInterface, @Nullable PsiClass controller, String serviceField,
              List<ServiceCode.Prop> parentProps, boolean parentLombok, List<ChildRef> selects) {}

  /** 자식 후보 문장. */
  record ChildRef(String namespace, String id, VirtualFile file) {
    @Override
    public String toString() { return Names.simple(namespace) + "." + id; }
  }

  /** 고른 자식 문장의 모양 · 매퍼 필드 · 결과 DTO 속성. */
  private record Child(ChildRef ref, ServiceCode.Sig sig, String mapperField, List<ServiceCode.Prop> props) {}

  /** 색인 중이 아닐 때, 읽기 접근 안에서 부른다. */
  static Info gather(Project project, LayerCode.Located at) {
    MapperDoc doc = at.doc();
    String id = at.statement().attr("id");
    String mapperSimple = doc.shortNamespace();
    PsiClass mapper = LayerCode.findClass(project, doc.namespace);
    PsiClass service = null;
    String mapperField = ServiceCode.mapperField(mapperSimple);
    LayerCode.Holder sh = LayerCode.holderOf(project, mapper);
    if (sh != null) {
      service = sh.cls();
      mapperField = sh.field();
    }
    PsiClass serviceInterface = LayerCode.projectInterface(service);
    PsiClass controller = null;
    String serviceField = ServiceCode.serviceField(mapperSimple);
    LayerCode.Holder ch = LayerCode.holderOf(project, serviceInterface != null ? serviceInterface : service);
    if (ch != null) {
      controller = ch.cls();
      serviceField = ch.field();
    }
    ServiceCode.Sig sig = LayerCode.sigFor(project, doc, at.statement());
    String element = ServiceCode.elementType(sig.returnType());
    PsiClass parentDto = element == null ? null : LayerCode.findClass(project, element);
    List<ChildRef> selects = new ArrayList<>();
    for (MapperDoc.Node s : doc.statements) {
      if (ServiceCode.SELECT.equals(s.name)) selects.add(new ChildRef(doc.namespace, s.attr("id"), at.file()));
    }
    return new Info(at, id, sig, LayerCode.mapperMethod(project, doc.namespace, id) != null, mapperSimple,
        mapper, service, mapperField, serviceInterface, controller, serviceField,
        LayerCode.props(parentDto), LayerCode.lombok(parentDto), selects);
  }

  private final Project project;
  private final Info info;
  private final FileType javaType = FileTypeManager.getInstance().getFileTypeByExtension(JAVA_EXT);
  private final JBTextField url = new JBTextField(URL_COLUMNS);
  private final ComboBox<String> http = new ComboBox<>(ServiceCode.HTTP_METHODS.toArray(new String[0]));
  private final Section controllerCode;
  private final Section interfaceCode;
  private final Section serviceCode;
  private final Section mapperCode;
  private final ComboBox<ChildRef> childBox = new ComboBox<>();
  private final ComboBox<String> keyBox = new ComboBox<>();
  private final JBTextField listName = new JBTextField(LIST_NAME_COLUMNS);
  private final JBLabel joinError = new JBLabel();
  private final Section joinCode;
  private final Section fieldCode;
  private Child child;

  /** 편집할 수 있는 코드 칸 하나와 그 단추들. target 은 넣을 때 고르는 창의 기본값. */
  private final class Section {
    final EditorTextField editor;
    final JBLabel targetLabel = new JBLabel();
    final Supplier<Set<String>> imports;
    @Nullable PsiClass target;

    Section(int height, @Nullable PsiClass target, Supplier<Set<String>> imports) {
      this.editor = new EditorTextField(EditorFactory.getInstance().createDocument(""), project, javaType, false, false);
      this.editor.setPreferredSize(JBUI.size(CODE_WIDTH, height));
      this.editor.addSettingsProvider(e -> {
        e.setVerticalScrollbarVisible(true);
        e.setHorizontalScrollbarVisible(true);
        e.getSettings().setLineNumbersShown(true);
      });
      this.target = target;
      this.imports = imports;
      showTarget();
    }

    /** insertable 이 아니면 「복사」 만. */
    JPanel panel(String title, boolean insertable) {
      JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
      bar.add(new JBLabel(title));
      JButton copy = new JButton(EiDbBundle.message("common.copy"));
      copy.addActionListener(e -> {
        CopyPasteManager.copyTextToClipboard(editor.getText());
        Notify.info(project, EiDbBundle.message("layercode.copied", title));
      });
      bar.add(copy);
      if (insertable) {
        JButton put = new JButton(EiDbBundle.message("layercode.insert.into"));
        put.addActionListener(e -> insert(title));
        bar.add(put);
        bar.add(targetLabel);
      }
      JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(2)));
      p.setBorder(JBUI.Borders.emptyBottom(8));
      p.add(bar, BorderLayout.NORTH);
      p.add(editor, BorderLayout.CENTER);
      return p;
    }

    private void insert(String title) {
      TreeClassChooserFactory f = TreeClassChooserFactory.getInstance(project);
      String chooserTitle = EiDbBundle.message("layercode.chooser.title", title);
      TreeClassChooser chooser = target != null && target.isValid()
          ? f.createProjectScopeChooser(chooserTitle, target) : f.createProjectScopeChooser(chooserTitle);
      chooser.showDialog();
      PsiClass picked = chooser.getSelected();
      if (picked == null) return; // 고르는 창을 닫은 경우다.
      target = picked;
      showTarget();
      LayerCode.insert(project, picked, editor.getText(), imports.get());
    }

    private void showTarget() {
      String name = target == null ? null : ReadAction.compute(() -> target.isValid() ? target.getQualifiedName() : null);
      targetLabel.setText(name == null ? EiDbBundle.message("layercode.target.none") : EiDbBundle.message("layercode.target.default", name));
    }

    void setText(String text) { editor.setText(text); }
  }

  LayerCodeDialog(Project project, Info info) {
    super(project);
    this.project = project;
    this.info = info;
    url.setText(ServiceCode.defaultUrl(info.id()));
    http.setSelectedItem(ServiceCode.defaultHttpMethod(info.at().statement().name));
    controllerCode = new Section(CODE_HEIGHT, info.controller(),
        () -> ServiceCode.controllerImports(info.sig(), (String) http.getSelectedItem()));
    interfaceCode = new Section(CODE_HEIGHT, info.serviceInterface(), () -> ServiceCode.importsOf(info.sig()));
    serviceCode = new Section(CODE_HEIGHT, info.service(), () -> ServiceCode.importsOf(info.sig()));
    mapperCode = new Section(CODE_HEIGHT, info.mapper(), () -> ServiceCode.importsOf(info.sig()));
    joinCode = new Section(JOIN_HEIGHT, info.service(), this::joinImports);
    fieldCode = new Section(CODE_HEIGHT, null, this::fieldImports);
    setTitle(EiDbBundle.message("layercode.title.with", info.mapperSimple() + "." + info.id()));
    setOKButtonText(EiDbBundle.message("common.close"));
    init();
    refreshLayers();
    for (ChildRef r : info.selects()) childBox.addItem(r);
    ChildRef first = null;
    for (ChildRef r : info.selects()) {
      if (first == null && !r.id().equals(info.id())) first = r;
    }
    childBox.setSelectedItem(first);
    childBox.addActionListener(e -> loadChild());
    if (first != null) loadChild();
    else refreshJoin();
  }

  @Override
  protected Action @NotNull [] createActions() {
    return new Action[] {getOKAction()};
  }

  @Override
  protected @Nullable JComponent createCenterPanel() {
    JBTabbedPane tabs = new JBTabbedPane();
    tabs.addTab(EiDbBundle.message("layercode.tab.layers"), layersTab());
    tabs.addTab(EiDbBundle.message("layercode.tab.join"), joinTab());
    return tabs;
  }

  private JComponent layersTab() {
    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
    top.add(new JBLabel("URL"));
    top.add(url);
    top.add(new JBLabel(EiDbBundle.message("layercode.http.method")));
    top.add(http);
    if (info.sig().guessed()) top.add(new JBLabel(EiDbBundle.message("layercode.guessed")));
    url.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) { refreshController(); }
    });
    http.addActionListener(e -> refreshController());
    JPanel list = new JPanel();
    list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
    list.add(controllerCode.panel(EiDbBundle.message("layercode.panel.controller"), true));
    list.add(interfaceCode.panel(EiDbBundle.message("layercode.panel.interface"), true));
    list.add(serviceCode.panel(EiDbBundle.message("layercode.panel.service"), true));
    if (!info.mapperHasMethod()) list.add(mapperCode.panel(EiDbBundle.message("layercode.panel.mapper"), true));
    JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(6)));
    p.add(top, BorderLayout.NORTH);
    JBScrollPane scroll = new JBScrollPane(list);
    scroll.setPreferredSize(JBUI.size(CODE_WIDTH + 40, SCROLL_HEIGHT));
    p.add(scroll, BorderLayout.CENTER);
    return p;
  }

  private JComponent joinTab() {
    JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
    row1.add(new JBLabel(EiDbBundle.message("layercode.parent.child", info.id())));
    row1.add(childBox);
    row1.add(new ActionLink(EiDbBundle.message("layercode.other.mapper"), (ActionListener) e -> pickOther()));
    JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
    keyBox.setEditable(true);
    row2.add(new JBLabel(EiDbBundle.message("layercode.join.key")));
    row2.add(keyBox);
    row2.add(new JBLabel(EiDbBundle.message("layercode.child.list.name")));
    row2.add(listName);
    joinError.setForeground(ERROR);
    row2.add(joinError);
    if (keyBox.getEditor().getEditorComponent() instanceof JTextField tf) {
      tf.getDocument().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(@NotNull DocumentEvent e) { refreshJoin(); }
      });
    }
    keyBox.addActionListener(e -> refreshJoin());
    listName.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) { refreshJoin(); }
    });
    JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    top.add(row1);
    top.add(row2);
    JPanel list = new JPanel();
    list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
    list.add(joinCode.panel(EiDbBundle.message("layercode.panel.join"), true));
    list.add(fieldCode.panel(info.parentLombok() ? EiDbBundle.message("layercode.panel.field.lombok") : EiDbBundle.message("layercode.panel.field"), false));
    JPanel p = new JPanel(new BorderLayout(0, JBUI.scale(6)));
    p.add(top, BorderLayout.NORTH);
    p.add(new JBScrollPane(list), BorderLayout.CENTER);
    return p;
  }

  private void refreshLayers() {
    refreshController();
    interfaceCode.setText(ServiceCode.declaration(info.id(), info.sig()));
    serviceCode.setText(ServiceCode.serviceMethod(info.mapperField(), info.id(), info.sig()));
    mapperCode.setText(ServiceCode.declaration(info.id(), info.sig()));
  }

  private void refreshController() {
    controllerCode.setText(ServiceCode.controllerMethod(info.serviceField(), info.id(), info.sig(), url.getText().trim(),
        (String) http.getSelectedItem()));
  }

  /** 고른 자식 문장을 읽는다(짧은 진행 창). 읽지 못하면 알리고 자식을 비운다. */
  private void loadChild() {
    ChildRef ref = (ChildRef) childBox.getSelectedItem();
    child = null;
    if (ref != null) {
      if (DumbService.isDumb(project)) {
        Notify.warning(project, EiDbBundle.message("layercode.dumb.child"));
      } else {
        child = withProgress(EiDbBundle.message("layercode.loading.child"), () -> readChild(ref));
      }
    }
    keyBox.removeAllItems();
    if (child != null) {
      for (ServiceCode.Prop p : ServiceCode.commonKeys(info.parentProps(), child.props())) keyBox.addItem(p.name());
      String element = ServiceCode.elementType(child.sig().returnType());
      if (element != null) listName.setText(ServiceCode.childListName(ServiceCode.simple(element)));
    }
    refreshJoin();
  }

  /** 읽기 접근 안에서 부른다. select 가 아니거나 찾지 못하면 null. */
  private @Nullable Child readChild(ChildRef ref) {
    MapperDoc doc = ref.namespace().equals(info.at().doc().namespace) && ref.file().equals(info.at().file())
        ? info.at().doc() : LayerCode.load(ref.file());
    MapperDoc.Node s = doc == null ? null : doc.statement(ref.id());
    if (s == null || !ServiceCode.SELECT.equals(s.name)) return null;
    ServiceCode.Sig sig = LayerCode.sigFor(project, doc, s);
    String field;
    if (ref.namespace().equals(info.at().doc().namespace)) {
      field = info.mapperField();
    } else {
      String found = LayerCode.fieldOfType(info.service(), ref.namespace());
      field = found != null ? found : ServiceCode.mapperField(Names.simple(ref.namespace()));
    }
    String element = ServiceCode.elementType(sig.returnType());
    PsiClass dto = element == null ? null : LayerCode.findClass(project, element);
    return new Child(ref, sig, field, LayerCode.props(dto));
  }

  private void pickOther() {
    if (DumbService.isDumb(project)) {
      Notify.warning(project, EiDbBundle.message("layercode.dumb.list"));
      return;
    }
    List<ChildRef> all = withProgress(EiDbBundle.message("layercode.loading.list"), () -> {
      List<ChildRef> out = new ArrayList<>();
      for (MapperIndex.Entry e : MapperIndex.all(project)) {
        for (String id : e.ids().keySet()) out.add(new ChildRef(e.namespace(), id, e.file()));
      }
      out.sort(Comparator.comparing(ChildRef::toString));
      return out;
    });
    if (all == null || all.isEmpty()) return;
    JBPopupFactory.getInstance().createPopupChooserBuilder(all)
        .setTitle(EiDbBundle.message("layercode.child.title"))
        .setFilterAlwaysVisible(true)
        .setItemChosenCallback(r -> {
          childBox.addItem(r);
          childBox.setSelectedItem(r);
        })
        .createPopup().showInCenterOf(childBox);
  }

  private void refreshJoin() {
    if (child == null) {
      joinError.setText(childBox.getSelectedItem() == null ? EiDbBundle.message("layercode.child.empty") : EiDbBundle.message("layercode.child.invalid"));
      joinCode.setText("");
      fieldCode.setText("");
      return;
    }
    String key = keyText();
    String keyType = "";
    for (ServiceCode.Prop p : child.props()) {
      if (p.name().equals(key)) keyType = p.type();
    }
    if (keyType.isEmpty()) {
      for (ServiceCode.Prop p : info.parentProps()) {
        if (p.name().equals(key)) keyType = p.type();
      }
    }
    ServiceCode.Join j = join(key, keyType);
    try {
      joinCode.setText(ServiceCode.parentChild(j));
      joinError.setText("");
    } catch (IllegalArgumentException ex) {
      joinCode.setText("");
      joinError.setText(ex.getMessage());
    }
    String element = ServiceCode.elementType(child.sig().returnType());
    fieldCode.setText(element == null || listName.getText().isBlank() ? "" : ServiceCode.childField(element, listName.getText(), info.parentLombok()));
  }

  private ServiceCode.Join join(String key, String keyType) {
    return new ServiceCode.Join(info.mapperField(), info.id(), info.sig(), child.mapperField(), child.ref().id(), child.sig(),
        key, keyType, listName.getText());
  }

  private String keyText() {
    Object item = keyBox.getEditor().getEditorComponent() instanceof JTextField tf ? tf.getText() : keyBox.getSelectedItem();
    return item == null ? "" : item.toString().trim();
  }

  private Set<String> joinImports() {
    return child == null ? Set.of() : ServiceCode.parentChildImports(join(keyText(), ""));
  }

  private Set<String> fieldImports() {
    String element = child == null ? null : ServiceCode.elementType(child.sig().returnType());
    return element == null ? Set.of() : ServiceCode.childFieldImports(element);
  }

  /** 짧은 모달 진행 창에서 읽기 접근으로 구한다. 취소하면 null. */
  private <T> @Nullable T withProgress(String title, Supplier<T> find) {
    try {
      return ProgressManager.getInstance().runProcessWithProgressSynchronously(
          (ThrowableComputable<T, RuntimeException>) () -> ReadAction.compute(find::get), title, true, project);
    } catch (ProcessCanceledException ex) {
      return null; // 사용자가 진행 창에서 취소한 경우다.
    }
  }
}
