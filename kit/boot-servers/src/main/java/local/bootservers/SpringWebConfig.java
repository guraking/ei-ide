package local.bootservers;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaResourceRootType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 프로젝트의 스프링 설정 파일 · 웹 뿌리 · 뷰 prefix/suffix 모으기. 읽기 동작 안에서 부른다.
 * 설정 파일: 테스트가 아닌 리소스 폴더(와 그 안 config 폴더)의 application*.yml · yaml · properties.
 * 웹 뿌리: 모듈 폴더의 src/main/webapp, src/main/resources/META-INF/resources (Gradle 소스셋 모듈은 webapp · resources/META-INF/resources).
 */
final class SpringWebConfig {

    private static final Logger LOG = Logger.getInstance(SpringWebConfig.class);

    private static final String CONFIG_NAME_START = "application";
    private static final String CONFIG_SUBFOLDER = "config";
    private static final Set<String> YAML_EXTENSIONS = Set.of("yml", "yaml");
    private static final String PROPERTIES_EXTENSION = "properties";
    private static final String XML_EXTENSION = "xml";
    private static final String RESOLVER_MARK = "InternalResourceViewResolver";
    private static final String MVC_JSP_MARK = "<mvc:jsp";
    private static final String WEB_INF = "WEB-INF";
    private static final String[] WEB_ROOT_PATHS = {
            "src/main/webapp", "src/main/resources/META-INF/resources", "webapp", "resources/META-INF/resources"};
    private static final String META_INF_RESOURCES = "META-INF/resources";

    /** 뷰 찾기에 쓰는 값. prefixes · suffixes 는 찾은 값(없으면 기본값 하나). */
    record ViewSettings(List<String> prefixes, List<String> suffixes, List<VirtualFile> webRoots) {
    }

    private SpringWebConfig() {
    }

    static boolean isYaml(VirtualFile file) {
        String extension = file.getExtension();
        return extension != null && YAML_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    /** application*.yml · yaml · properties 파일들(이름 순). */
    static List<VirtualFile> configFiles(Project project) {
        List<VirtualFile> result = new ArrayList<>();
        for (VirtualFile root : resourceRoots(project)) {
            addConfigFiles(root, result);
            VirtualFile sub = root.findChild(CONFIG_SUBFOLDER);
            if (sub != null && sub.isDirectory()) {
                addConfigFiles(sub, result);
            }
        }
        result.sort((a, b) -> a.getPath().compareTo(b.getPath()));
        return result;
    }

    /** 모듈 하나의 설정 파일과 읽은 값. sources 는 files 와 같은 차례다. */
    record ModuleConfig(List<VirtualFile> files, List<Links.Source> sources) {
    }

    /**
     * 모듈 본 리소스 폴더의 application*.yml · yaml · properties, 그다음 그 안 config 폴더의 것(스프링이 읽는 차례, 뒤가 이긴다).
     * 읽기 동작 안에서 부른다. 읽지 못한 파일은 값 없이 넣는다.
     */
    static ModuleConfig moduleConfig(Module module) {
        List<VirtualFile> files = new ArrayList<>();
        List<VirtualFile> roots = ModuleRootManager.getInstance(module).getSourceRoots(JavaResourceRootType.RESOURCE);
        for (VirtualFile root : roots) {
            addConfigFiles(root, files);
        }
        for (VirtualFile root : roots) {
            VirtualFile sub = root.findChild(CONFIG_SUBFOLDER);
            if (sub != null && sub.isDirectory()) {
                addConfigFiles(sub, files);
            }
        }
        List<Links.Source> sources = new ArrayList<>();
        for (VirtualFile file : files) {
            String text = text(file);
            sources.add(new Links.Source(file.getName(), isYaml(file), text == null ? List.of() : ConfigKeys.entries(text, isYaml(file))));
        }
        return new ModuleConfig(files, sources);
    }

    private static void addConfigFiles(VirtualFile dir, List<VirtualFile> result) {
        for (VirtualFile child : dir.getChildren()) {
            String extension = child.getExtension();
            if (child.isDirectory() || extension == null || !child.getName().startsWith(CONFIG_NAME_START)) {
                continue;
            }
            if (isYaml(child) || PROPERTIES_EXTENSION.equalsIgnoreCase(extension)) {
                result.add(child);
            }
        }
    }

    private static List<VirtualFile> resourceRoots(Project project) {
        return ProjectRootManager.getInstance(project).getModuleSourceRoots(Set.of(JavaResourceRootType.RESOURCE));
    }

    /** 프로젝트 모든 모듈의 웹 뿌리. */
    static List<VirtualFile> webRoots(Project project) {
        Set<VirtualFile> result = new LinkedHashSet<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            for (VirtualFile contentRoot : ModuleRootManager.getInstance(module).getContentRoots()) {
                for (String path : WEB_ROOT_PATHS) {
                    VirtualFile dir = contentRoot.findFileByRelativePath(path);
                    if (dir != null && dir.isDirectory()) {
                        result.add(dir);
                    }
                }
            }
        }
        for (VirtualFile root : resourceRoots(project)) {
            VirtualFile dir = root.findFileByRelativePath(META_INF_RESOURCES);
            if (dir != null && dir.isDirectory()) {
                result.add(dir);
            }
        }
        return new ArrayList<>(result);
    }

    /** prefix · suffix · 웹 뿌리. PSI · 파일 · 프로젝트 구조가 바뀌면 다시 모은다. */
    static ViewSettings viewSettings(Project project) {
        return CachedValuesManager.getManager(project).getCachedValue(project, () -> CachedValueProvider.Result.create(
                collectViewSettings(project),
                PsiModificationTracker.getInstance(project),
                VirtualFileManager.getInstance(),
                ProjectRootManager.getInstance(project)));
    }

    private static ViewSettings collectViewSettings(Project project) {
        Set<String> prefixes = new LinkedHashSet<>();
        Set<String> suffixes = new LinkedHashSet<>();
        for (VirtualFile file : configFiles(project)) {
            String text = text(file);
            if (text != null) {
                add(ViewPath.fromConfig(text, isYaml(file)), prefixes, suffixes);
            }
        }
        List<VirtualFile> webRoots = webRoots(project);
        List<VirtualFile> xmlDirs = new ArrayList<>(resourceRoots(project));
        for (VirtualFile webRoot : webRoots) {
            VirtualFile webInf = webRoot.findChild(WEB_INF);
            if (webInf != null && webInf.isDirectory()) {
                xmlDirs.add(webInf);
            }
        }
        for (VirtualFile dir : xmlDirs) {
            for (VirtualFile xml : xmlFiles(dir)) {
                String text = text(xml);
                if (text != null && (text.contains(RESOLVER_MARK) || text.contains(MVC_JSP_MARK))) {
                    for (String[] pair : ViewPath.fromXml(text)) {
                        add(pair, prefixes, suffixes);
                    }
                }
            }
        }
        if (prefixes.isEmpty()) {
            prefixes.add(ViewPath.DEFAULT_PREFIX);
        }
        if (suffixes.isEmpty()) {
            suffixes.add(ViewPath.DEFAULT_SUFFIX);
        }
        return new ViewSettings(List.copyOf(prefixes), List.copyOf(suffixes), webRoots);
    }

    private static void add(String[] pair, Set<String> prefixes, Set<String> suffixes) {
        if (pair[0] != null) {
            prefixes.add(pair[0]);
        }
        if (pair[1] != null) {
            suffixes.add(pair[1]);
        }
    }

    private static List<VirtualFile> xmlFiles(VirtualFile dir) {
        List<VirtualFile> result = new ArrayList<>();
        VfsUtilCore.visitChildrenRecursively(dir, new VirtualFileVisitor<Void>() {
            @Override
            public boolean visitFile(@NotNull VirtualFile file) {
                if (!file.isDirectory() && XML_EXTENSION.equalsIgnoreCase(file.getExtension())) {
                    result.add(file);
                }
                return true;
            }
        });
        return result;
    }

    /** 열려 있으면 편집 중인 글, 아니면 디스크 글. 못 읽으면 기록하고 null. */
    static String text(VirtualFile file) {
        Document document = FileDocumentManager.getInstance().getCachedDocument(file);
        if (document != null) {
            return document.getText();
        }
        try {
            return VfsUtilCore.loadText(file);
        } catch (IOException e) {
            LOG.warn("설정 파일을 읽지 못했습니다: " + file.getPath(), e);
            return null;
        }
    }
}
