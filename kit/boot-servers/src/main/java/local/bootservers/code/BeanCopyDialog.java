package local.bootservers.code;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.List;

/** 「꺼낼 객체」 · 「담을 객체」 고르기. 후보는 둘 이상이어야 하고, 같은 것을 둘 다 고르면 확인을 막는다. */
final class BeanCopyDialog extends DialogWrapper {

    private static final int MIN_ITEMS = 2;

    private final ComboBox<String> fromBox;
    private final ComboBox<String> toBox;

    BeanCopyDialog(Project project, List<String> labels) {
        super(project);
        if (labels.size() < MIN_ITEMS) {
            throw new IllegalArgumentException("후보가 둘 이상 있어야 합니다");
        }
        String[] items = labels.toArray(new String[0]);
        fromBox = new ComboBox<>(items);
        toBox = new ComboBox<>(items);
        toBox.setSelectedIndex(1);
        setTitle(BeanCopyAction.TITLE);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent("꺼낼 객체", fromBox)
                .addLabeledComponent("담을 객체", toBox)
                .getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return fromBox;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (fromBox.getSelectedIndex() == toBox.getSelectedIndex()) {
            return new ValidationInfo("꺼낼 객체와 담을 객체가 같습니다.", toBox);
        }
        return null;
    }

    int fromIndex() {
        return fromBox.getSelectedIndex();
    }

    int toIndex() {
        return toBox.getSelectedIndex();
    }
}
