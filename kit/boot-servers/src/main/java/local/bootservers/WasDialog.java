package local.bootservers;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import local.bootservers.was.JeusBase;
import local.bootservers.was.WasSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.event.DocumentEvent;
import javax.swing.table.AbstractTableModel;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** WAS 추가 · 편집 대화 상자. 확인을 누르면 {@link #result()} 로 고친 사본을 돌려준다(원본은 건드리지 않는다). */
final class WasDialog extends DialogWrapper {

    private final WasSettings.Server server;

    private final ComboBox<WasSettings.Kind> kind = new ComboBox<>(WasSettings.Kind.values());
    private final JBTextField name = new JBTextField();
    private final TextFieldWithBrowseButton home = new TextFieldWithBrowseButton();
    private final JBLabel found = new JBLabel(" ");
    private final JBTextField httpPort = new JBTextField();
    private final JBTextField shutdownPort = new JBTextField();
    private final JBLabel shutdownLabel = new JBLabel();
    private final JBTextField debugPort = new JBTextField();
    private final JBTextField vmOptions = new JBTextField();
    private final JBCheckBox reloadable = new JBCheckBox(BootServersBundle.message("was.dialog.reloadable"));
    // JEUS 전용 칸. 다른 종류에서는 보이되 꺼 둔다.
    private final JBTextField jeusDomain = new JBTextField();
    private final JBTextField jeusServer = new JBTextField();
    private final JBTextField jeusUser = new JBTextField();
    private final JBTextField startCommand = new JBTextField();
    private final JBTextField stopCommand = new JBTextField();
    private final JBTextField deployCommand = new JBTextField();
    private final JBCheckBox forgetPassword = new JBCheckBox(BootServersBundle.message("was.dialog.forget.password"));
    private final JBLabel jeusHint = new JBLabel(BootServersBundle.message("was.dialog.jeus.hint"));
    private final Project project;
    private final List<Row> rows = new ArrayList<>();

    /** 표 한 줄: 올릴지 · 폴더 모듈 이름 · 컨텍스트 경로. */
    private static final class Row {
        boolean on;
        final String module;
        String contextPath;

        Row(boolean on, String module, String contextPath) {
            this.on = on;
            this.module = module;
            this.contextPath = contextPath;
        }
    }

    WasDialog(Project project, @Nullable WasSettings.Server existing) {
        super(project);
        this.project = project;
        this.server = existing == null ? new WasSettings.Server() : existing.copy();
        setTitle(existing == null ? BootServersBundle.message("was.dialog.title.add") : BootServersBundle.message("was.dialog.title.edit"));
        setOKButtonText(existing == null ? BootServersBundle.message("was.dialog.ok.add") : BootServersBundle.message("common.save"));

        kind.setRenderer(SimpleListCellRenderer.create("", k -> k.supported ? k.label : BootServersBundle.message("was.dialog.kind.unsupported", k.label)));
        kind.setSelectedItem(server.kind());
        name.setText(server.name.isEmpty() ? defaultName(server.kind()) : server.name);
        kind.addActionListener(e -> kindChanged());
        home.setText(server.home);
        home.addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle(BootServersBundle.message("was.dialog.home.chooser")));
        home.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                updateFound();
            }
        });
        httpPort.setText(String.valueOf(server.httpPort));
        shutdownPort.setText(String.valueOf(server.shutdownPort));
        debugPort.setText(String.valueOf(server.debugPort));
        vmOptions.setText(server.vmOptions);
        vmOptions.getEmptyText().setText("-Xmx1g -Dspring.profiles.active=local");
        reloadable.setSelected(server.reloadable);
        jeusDomain.setText(server.jeusDomain);
        jeusServer.setText(server.jeusServer);
        jeusUser.setText(server.jeusUser);
        startCommand.setText(server.startCommand);
        startCommand.getEmptyText().setText(JeusBase.DEFAULT_START);
        stopCommand.setText(server.stopCommand);
        stopCommand.getEmptyText().setText(JeusBase.DEFAULT_STOP);
        deployCommand.setText(server.deployCommand);
        deployCommand.getEmptyText().setText(JeusBase.DEFAULT_DEPLOY);
        String placeholders = BootServersBundle.message("was.dialog.placeholders");
        for (JBTextField f : List.of(startCommand, stopCommand, deployCommand)) {
            f.setToolTipText(placeholders);
        }
        jeusHint.setForeground(JBColor.GRAY);

        List<String> modules = new ArrayList<>();
        for (Module m : ModuleManager.getInstance(project).getModules()) {
            String n = m.getName();
            if (!n.endsWith(".main") && !n.endsWith(".test")) {
                modules.add(n);
            }
        }
        modules.sort(String::compareTo);
        for (String m : modules) {
            WasSettings.Deployment d = server.deployments.stream().filter(x -> x.module.equals(m)).findFirst().orElse(null);
            rows.add(new Row(d != null, m, d != null ? d.contextPath : "/" + m));
        }
        updateFound();
        init();
    }

    private WasSettings.Kind selectedKind() {
        return (WasSettings.Kind) kind.getSelectedItem();
    }

    /** WebLogic 은 종료(관리) 포트를 쓰지 않는다. */
    private static boolean usesSecondPort(WasSettings.Kind k) {
        return k != WasSettings.Kind.WEBLOGIC;
    }

    private static String defaultName(WasSettings.Kind k) {
        return BootServersBundle.message("was.dialog.default.name", k.label.replace(" / ", " "));
    }

    // 종류별 기본 HTTP · 종료(관리) 포트. 사용자가 고친 값은 종류를 바꿔도 그대로 둔다.
    private static int defaultHttp(WasSettings.Kind k) {
        return switch (k) {
            case WILDFLY -> 8180;
            case WEBLOGIC -> 7001;
            case JEUS -> 8088;
            default -> 8090;
        };
    }

    private static int defaultSecond(WasSettings.Kind k) {
        return switch (k) {
            case WILDFLY -> 9990;
            // JEUS 8 기본(관리) 포트. 중지 · 배포 명령이 이 포트로 붙는다.
            case JEUS -> 9736;
            default -> 8005;
        };
    }

    /** WildFly · JEUS 는 두 번째 포트가 관리 포트다. */
    private static boolean usesAdminPort(WasSettings.Kind k) {
        return k == WasSettings.Kind.WILDFLY || k == WasSettings.Kind.JEUS;
    }

    private void kindChanged() {
        WasSettings.Kind k = selectedKind();
        for (WasSettings.Kind other : WasSettings.Kind.values()) {
            if (name.getText().strip().equals(defaultName(other))) {
                name.setText(defaultName(k));
            }
            if (httpPort.getText().strip().equals(String.valueOf(defaultHttp(other)))) {
                httpPort.setText(String.valueOf(defaultHttp(k)));
            }
            if (shutdownPort.getText().strip().equals(String.valueOf(defaultSecond(other)))) {
                shutdownPort.setText(String.valueOf(defaultSecond(k)));
            }
        }
        updateFound();
    }

    private void updateFound() {
        WasSettings.Kind k = selectedKind();
        shutdownLabel.setText(usesAdminPort(k) ? BootServersBundle.message("was.dialog.port.admin") : usesSecondPort(k) ? BootServersBundle.message("was.dialog.port.shutdown") : BootServersBundle.message("was.dialog.port.shutdown.unused"));
        shutdownPort.setEnabled(usesSecondPort(k));
        boolean jeus = k == WasSettings.Kind.JEUS;
        for (JComponent c : List.of(jeusDomain, jeusServer, jeusUser, startCommand, stopCommand, deployCommand, forgetPassword, jeusHint)) {
            c.setEnabled(jeus);
        }
        String version = versionOf(k, home.getText().strip());
        String hint = switch (k) {
            case WILDFLY -> BootServersBundle.message("was.dialog.hint.wildfly");
            case WEBLOGIC -> BootServersBundle.message("was.dialog.hint.weblogic");
            case JEUS -> BootServersBundle.message("was.dialog.hint.jeus");
            default -> BootServersBundle.message("was.dialog.hint.tomcat");
        };
        found.setText(version == null ? BootServersBundle.message("was.dialog.choose.home", k.label, hint) : BootServersBundle.message("was.dialog.found", version));
        found.setForeground(version == null ? JBColor.GRAY : new JBColor(0x3E9C46, 0x5FB865));
    }

    private static String versionOf(WasSettings.Kind k, String path) {
        if (path.isEmpty()) {
            return null;
        }
        try {
            return WasRunner.detectVersion(k, Path.of(path));
        }
        catch (InvalidPathException e) {
            return null;
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBTable table = new JBTable(new DeployModel());
        table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(50));
        table.setVisibleRowCount(6);
        JComponent panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(BootServersBundle.message("was.dialog.kind"), kind)
                .addLabeledComponent(BootServersBundle.message("servers.boot.name"), name)
                .addLabeledComponent(BootServersBundle.message("was.dialog.home"), home)
                .addComponentToRightColumn(found)
                .addLabeledComponent(BootServersBundle.message("was.dialog.port.http"), httpPort)
                .addLabeledComponent(shutdownLabel, shutdownPort)
                .addLabeledComponent(BootServersBundle.message("was.dialog.port.debug"), debugPort)
                .addLabeledComponent(BootServersBundle.message("servers.boot.vm"), vmOptions)
                .addComponent(reloadable)
                .addSeparator()
                .addLabeledComponent(BootServersBundle.message("was.dialog.jeus.domain"), jeusDomain)
                .addLabeledComponent(BootServersBundle.message("was.dialog.jeus.server"), jeusServer)
                .addLabeledComponent(BootServersBundle.message("was.dialog.jeus.user"), jeusUser)
                .addComponentToRightColumn(forgetPassword)
                .addLabeledComponent(BootServersBundle.message("was.dialog.command.start"), startCommand)
                .addLabeledComponent(BootServersBundle.message("was.dialog.command.stop"), stopCommand)
                .addLabeledComponent(BootServersBundle.message("was.dialog.command.deploy"), deployCommand)
                .addComponentToRightColumn(jeusHint)
                .addLabeledComponentFillVertically(BootServersBundle.message("was.dialog.deployments"), ScrollPaneFactory.createScrollPane(table))
                .getPanel();
        panel.setPreferredSize(JBUI.size(720, 760));
        return panel;
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (!selectedKind().supported) {
            return new ValidationInfo(BootServersBundle.message("was.not.supported", selectedKind().label), kind);
        }
        if (name.getText().isBlank()) {
            return new ValidationInfo(BootServersBundle.message("was.dialog.name.required"), name);
        }
        if (versionOf(selectedKind(), home.getText().strip()) == null) {
            return new ValidationInfo(BootServersBundle.message("was.dialog.not.home", selectedKind().label), home.getTextField());
        }
        if (selectedKind() == WasSettings.Kind.JEUS) {
            for (JBTextField f : List.of(jeusDomain, jeusServer, jeusUser)) {
                if (f.getText().isBlank()) {
                    return new ValidationInfo(BootServersBundle.message("was.dialog.jeus.required"), f);
                }
            }
        }
        Set<Integer> ports = new HashSet<>();
        for (JBTextField f : usesSecondPort(selectedKind()) ? List.of(httpPort, shutdownPort, debugPort) : List.of(httpPort, debugPort)) {
            Integer p = port(f);
            if (p == null) {
                return new ValidationInfo(BootServersBundle.message("servers.boot.port.invalid"), f);
            }
            if (!ports.add(p)) {
                return new ValidationInfo(BootServersBundle.message("was.dialog.ports.distinct"), f);
            }
        }
        Set<String> paths = new HashSet<>();
        for (Row r : rows) {
            if (!r.on) {
                continue;
            }
            if (!r.contextPath.startsWith("/") || r.contextPath.contains(" ")) {
                return new ValidationInfo(BootServersBundle.message("was.dialog.context.invalid", r.module));
            }
            if (!paths.add(r.contextPath.replaceAll("/+$", ""))) {
                return new ValidationInfo(BootServersBundle.message("was.dialog.context.duplicate", r.contextPath));
            }
        }
        return null;
    }

    private static Integer port(JBTextField f) {
        try {
            int p = Integer.parseInt(f.getText().strip());
            return p >= 1 && p <= 65535 ? p : null;
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /** 확인을 누른 뒤에만 부른다(검증을 통과한 값). */
    WasSettings.Server result() {
        server.kind = selectedKind().name();
        server.name = name.getText().strip();
        server.home = home.getText().strip();
        server.httpPort = port(httpPort);
        Integer second = port(shutdownPort);
        server.shutdownPort = second != null ? second : defaultSecond(selectedKind());
        server.debugPort = port(debugPort);
        server.vmOptions = vmOptions.getText().strip();
        server.reloadable = reloadable.isSelected();
        server.jeusDomain = jeusDomain.getText().strip();
        server.jeusServer = jeusServer.getText().strip();
        server.jeusUser = jeusUser.getText().strip();
        server.startCommand = startCommand.getText().strip();
        server.stopCommand = stopCommand.getText().strip();
        server.deployCommand = deployCommand.getText().strip();
        if (selectedKind() == WasSettings.Kind.JEUS && forgetPassword.isSelected()) {
            WasRunner.getInstance(project).forgetJeusPassword(server);
        }
        server.deployments.clear();
        for (Row r : rows) {
            if (r.on) {
                server.deployments.add(new WasSettings.Deployment(r.module, r.contextPath.strip()));
            }
        }
        return server;
    }

    private final class DeployModel extends AbstractTableModel {
        private final String[] columns = {BootServersBundle.message("was.dialog.column.deploy"), BootServersBundle.message("was.dialog.column.project"), BootServersBundle.message("was.dialog.column.context")};

        @Override
        public int getRowCount() {
            return rows.size();
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
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column != 1;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Row r = rows.get(row);
            return switch (column) {
                case 0 -> r.on;
                case 1 -> r.module;
                default -> r.contextPath;
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            Row r = rows.get(row);
            if (column == 0) {
                r.on = (Boolean) value;
            }
            else if (column == 2) {
                r.contextPath = String.valueOf(value).strip();
            }
        }
    }
}
