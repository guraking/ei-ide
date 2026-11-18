package ei.db.bench;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.fields.ExtendableTextComponent;
import com.intellij.ui.components.fields.ExtendableTextField;
import org.jetbrains.annotations.NotNull;

import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.event.DocumentEvent;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Supplier;

/**
 * 고르는 글 칸: 테두리 있는 스톡 글 칸 아래에 source 항목 목록을 편다. 직접 쳐도 되고 목록에서 골라도 된다.
 * - 칸을 누르거나 오른쪽 화살표 · ↓ 를 누르면 목록 전체를, 글을 치면 그 글을 담은 항목만 편다.
 * - ↑ ↓ 로 옮기고 Enter · 클릭으로 고른다. Esc · 다른 곳으로 가면 닫는다. 목록이 떠 있는 동안 Enter · Esc 는 대화창으로 가지 않는다.
 * - 여러 값 칸(many)은 골라도 목록이 닫히지 않는다. 고른 항목 앞에 CHOSEN_MARK 가 붙고, 다시 고르면 빠진다.
 * - 목록 창은 초점을 받지 않는다. 그래서 고르는 동안에도 글 칸에 계속 칠 수 있다.
 * 글 규칙(거르기 · 쉼표로 잇기)은 PickText.
 */
final class PickField {
  private static final int MAX_ROWS = 12;
  private static final String CHOSEN_MARK = "✓ ";
  /** 고르지 않은 항목을 고른 항목과 줄 맞추는 빈 칸. */
  private static final String NOT_CHOSEN = "    ";

  final ExtendableTextField field = new ExtendableTextField();
  private final boolean many;
  private final Supplier<List<String>> source;
  private final JBList<String> list = new JBList<>();
  private JWindow popup;
  /** 지금 목록이 거르지 않고 통째로 펴졌는지. 고른 글을 넣는 방식이 달라진다(PickText.picked). */
  private boolean all;
  private boolean adjusting;

  /** emptyText: 항목이 하나도 없을 때 목록 자리에 보일 글. */
  PickField(boolean many, Supplier<List<String>> source, String emptyText) {
    this.many = many;
    this.source = source;
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setFocusable(false);
    list.getEmptyText().setText(emptyText);
    if (many) {
      list.setCellRenderer(SimpleListCellRenderer.create("", item -> (PickText.chosen(field.getText(), item) ? CHOSEN_MARK : NOT_CHOSEN) + item));
    }
    field.addExtension(ExtendableTextComponent.Extension.create(AllIcons.General.ArrowDown, "목록 펼치기", () -> {
      field.requestFocusInWindow();
      open(true);
    }));
    field.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        if (!adjusting && field.isFocusOwner()) open(false);
      }
    });
    field.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        if (popup == null && SwingUtilities.isLeftMouseButton(e)) open(true);
      }
    });
    field.addFocusListener(new FocusAdapter() {
      @Override
      public void focusLost(FocusEvent e) { close(); }
    });
    field.addAncestorListener(new AncestorListener() {
      @Override
      public void ancestorAdded(AncestorEvent e) { }
      @Override
      public void ancestorRemoved(AncestorEvent e) { close(); }
      @Override
      public void ancestorMoved(AncestorEvent e) { close(); }
    });
    field.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (e.getModifiersEx() != 0) return;
        switch (e.getKeyCode()) {
          case KeyEvent.VK_DOWN -> move(1, e);
          case KeyEvent.VK_UP -> move(-1, e);
          case KeyEvent.VK_ENTER -> {
            if (popup == null) return;
            pick(list.getSelectedIndex());
            e.consume();
          }
          case KeyEvent.VK_ESCAPE -> {
            if (popup == null) return;
            close();
            e.consume();
          }
          default -> { }
        }
      }
    });
    // IntelliJ 는 대화창의 Esc(취소)를 Swing 키 처리보다 먼저 돌린다. 목록이 떠 있을 때는 이 칸의 동작이 먼저 받아 목록만 닫는다.
    new DumbAwareAction() {
      @Override
      public void update(@NotNull AnActionEvent e) { e.getPresentation().setEnabled(popup != null); }
      @Override
      public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.EDT; }
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) { close(); }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)), field);
    list.addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) { pick(list.locationToIndex(e.getPoint())); }
    });
    list.addMouseMotionListener(new MouseAdapter() {
      @Override
      public void mouseMoved(MouseEvent e) {
        int index = list.locationToIndex(e.getPoint());
        if (index >= 0) list.setSelectedIndex(index);
      }
    });
  }

  /** 목록이 닫혀 있으면 통째로 펴 첫(마지막) 줄에 놓고, 떠 있으면 고른 줄을 step 만큼 옮긴다(끝에서 멈춤). */
  private void move(int step, KeyEvent e) {
    if (popup == null) open(true);
    int size = list.getModel().getSize();
    if (popup == null || size == 0) return;
    int now = list.getSelectedIndex();
    int next = now < 0 ? (step > 0 ? 0 : size - 1) : Math.max(0, Math.min(size - 1, now + step));
    list.setSelectedIndex(next);
    list.ensureIndexIsVisible(next);
    e.consume();
  }

  /** index 번째 항목을 칸에 넣고 목록을 닫는다(여러 값 칸은 통째로 다시 펴 둔다). 고른 줄이 없으면 닫기만 한다. */
  private void pick(int index) {
    boolean picked = index >= 0 && index < list.getModel().getSize();
    boolean wasFiltered = !all;
    if (picked) {
      String text = PickText.picked(field.getText(), list.getModel().getElementAt(index), many, all);
      adjusting = true;
      try {
        field.setText(text);
        field.setCaretPosition(text.length());
      } finally {
        adjusting = false;
      }
    }
    if (!picked || !many) {
      close();
      return;
    }
    open(true);
    list.setSelectedIndex(index < list.getModel().getSize() && !wasFiltered ? index : -1);
  }

  /** 목록을 편다. 통째로(wantAll) 펼 때는 항목이 없어도 까닭 글을 보이고, 치는 글로 거를 때 맞는 것이 없으면 닫는다. */
  private void open(boolean wantAll) {
    List<String> items = PickText.filter(source.get(), field.getText(), many, wantAll);
    Window owner = SwingUtilities.getWindowAncestor(field);
    if (!field.isShowing() || owner == null || (items.isEmpty() && !wantAll)) {
      close();
      return;
    }
    all = wantAll;
    list.setListData(items.toArray(new String[0]));
    list.clearSelection();
    list.setVisibleRowCount(Math.max(1, Math.min(MAX_ROWS, items.size())));
    if (popup == null || popup.getOwner() != owner) {
      close();
      popup = new JWindow(owner);
      popup.setType(Window.Type.POPUP);
      popup.setFocusableWindowState(false);
      popup.setContentPane(ScrollPaneFactory.createScrollPane(list));
    }
    popup.pack();
    // 빈 목록은 까닭 글 한 줄이 들어갈 만큼만(칸 높이의 두 배).
    Dimension size = new Dimension(field.getWidth(), items.isEmpty() ? 2 * field.getHeight() : popup.getHeight());
    Point at = field.getLocationOnScreen();
    Rectangle screen = field.getGraphicsConfiguration().getBounds();
    // 아래에 자리가 모자라면 칸 위에 편다.
    int y = at.y + field.getHeight() + size.height <= screen.y + screen.height ? at.y + field.getHeight() : at.y - size.height;
    popup.setBounds(at.x, y, size.width, size.height);
    popup.setVisible(true);
  }

  private void close() {
    if (popup == null) return;
    popup.dispose();
    popup = null;
  }
}
