package local.bootservers.code;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import local.bootservers.BootServersBundle;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * 묶을 매개변수 고르기 · DTO 클래스 이름 · 필드 이름 고치기.
 * 확인은 클래스 이름이 자바 이름이고, 둘 이상 골랐고, 고른 필드 이름이 자바 이름이며 겹치지 않을 때만 된다.
 */
final class ParamBundleDialog extends DialogWrapper {

    private static final int MIN_SELECTED = 2;
    private static final int CHECK_COLUMN = 0;
    private static final int PARAM_COLUMN = 1;
    private static final int FIELD_COLUMN = 2;
    private static final int CHECK_COLUMN_WIDTH = 50;
    private static final int VISIBLE_ROWS = 8;

    private final JBTextField className;
    private final List<String> labels;
    private final List<Boolean> selected = new ArrayList<>();
    private final List<String> fieldNames;
    private final JBTable table;

    /** labels 와 fieldNames 는 같은 순서 · 같은 길이. */
    ParamBundleDialog(Project project, String defaultClassName, List<String> labels, List<String> fieldNames) {
        super(project);
        if (labels.size() != fieldNames.size() || labels.size() < MIN_SELECTED) {
            throw new IllegalArgumentException("매개변수가 둘 이상이고 필드 이름과 개수가 같아야 합니다");
        }
        this.labels = List.copyOf(labels);
        this.fieldNames = new ArrayList<>(fieldNames);
        for (int i = 0; i < labels.size(); i++) {
            selected.add(Boolean.TRUE);
        }
        className = new JBTextField(defaultClassName);
        table = new JBTable(new Model());
        table.getColumnModel().getColumn(CHECK_COLUMN).setMaxWidth(JBUI.scale(CHECK_COLUMN_WIDTH));
        table.setVisibleRowCount(VISIBLE_ROWS);
        setTitle(ParamBundleAction.title());
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent(BootServersBundle.message("params.dialog.class"), className)
                .addLabeledComponentFillVertically(BootServersBundle.message("params.dialog.params"), ScrollPaneFactory.createScrollPane(table))
                .getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return className;
    }

    /** 확인을 누를 때만 불린다(initValidation 을 쓰지 않음). 편집 중인 칸은 먼저 확정한다. */
    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (table.isEditing() && table.getCellEditor() != null) {
            table.getCellEditor().stopCellEditing();
        }
        if (!ParamBundle.isValidName(className().strip())) {
            return new ValidationInfo(BootServersBundle.message("params.dialog.class.invalid"), className);
        }
        List<Integer> chosen = selectedIndexes();
        if (chosen.size() < MIN_SELECTED) {
            return new ValidationInfo(BootServersBundle.message("params.dialog.too.few"), table);
        }
        List<String> names = new ArrayList<>();
        for (int i : chosen) {
            String name = fieldNames.get(i).strip();
            if (!ParamBundle.isValidName(name)) {
                return new ValidationInfo(BootServersBundle.message("params.dialog.field.invalid", name), table);
            }
            names.add(name);
        }
        String duplicate = ParamBundle.duplicate(names);
        return duplicate == null ? null : new ValidationInfo(BootServersBundle.message("params.dialog.field.duplicate", duplicate), table);
    }

    String className() {
        return className.getText().strip();
    }

    List<Integer> selectedIndexes() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            if (selected.get(i)) {
                out.add(i);
            }
        }
        return out;
    }

    String fieldName(int index) {
        return fieldNames.get(index).strip();
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {BootServersBundle.message("params.dialog.column.bundle"), BootServersBundle.message("params.dialog.column.param"), BootServersBundle.message("params.dialog.column.field")};

        @Override
        public int getRowCount() {
            return labels.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == CHECK_COLUMN ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column != PARAM_COLUMN;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return switch (column) {
                case CHECK_COLUMN -> selected.get(row);
                case PARAM_COLUMN -> labels.get(row);
                default -> fieldNames.get(row);
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == CHECK_COLUMN) {
                selected.set(row, Boolean.TRUE.equals(value));
            } else if (column == FIELD_COLUMN) {
                fieldNames.set(row, value == null ? "" : value.toString());
            }
            fireTableCellUpdated(row, column);
        }
    }
}
