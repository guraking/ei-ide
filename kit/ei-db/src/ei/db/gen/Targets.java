package ei.db.gen;

import com.intellij.ide.util.PsiNavigationSupport;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import ei.db.EiDbBundle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 생성 파일이 갈 자리(소스 · 리소스 루트, 기본 패키지)와 파일 쓰기 · 덧붙이기. */
final class Targets {
  private Targets() {}

  static final String FALLBACK_PACKAGE = "com.example";
  private static final String DEFAULT_JAVA = "src/main/java";
  private static final String DEFAULT_RESOURCES = "src/main/resources";
  private static final String RESOURCES_DIR = "resources";
  private static final String TEST_SEGMENT = "/test/";
  /** generated-sources · generated/sources 등 빌드가 만드는 소스 루트. */
  private static final String GENERATED = "/generated";
  private static final String MAPPER_SUFFIX = "Mapper.java";
  private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
  /** 매퍼 패키지 끝 마디가 이 중 하나면 그 위를 기본 패키지로 본다. */
  static final List<String> LAYER_SEGMENTS = List.of("mapper", "dao", "repository", "persistence");

  /** 소스 · 리소스 루트의 절대 경로와 짐작한 기본 패키지. 배경 스레드에서 read action 안에서 만든다. */
  record Roots(String base, String java, String resources, String basePackage) {
    /** 화면에 보일 때 프로젝트 기준 상대 경로. */
    String rel(String abs) {
      if (base == null) return abs;
      Path b = Path.of(base);
      Path p = Path.of(abs);
      return p.startsWith(b) ? b.relativize(p).toString().replace('\\', '/') : abs;
    }

    /** 상대 경로면 프로젝트 기준으로 붙인다. */
    String abs(String path) {
      Path p = Path.of(path.strip());
      return (p.isAbsolute() || base == null ? p : Path.of(base).resolve(p)).normalize().toString().replace('\\', '/');
    }

    String javaFile(String fqn) {
      return java + "/" + fqn.replace('.', '/') + ".java";
    }
  }

  /**
   * 테스트 · 생성 소스(generated)가 아닌 소스 루트를 모듈별로 모아, 자바 루트가 있는 첫 모듈에서 자바 · 리소스 루트를 함께 고른다.
   * 이름이 resources 인 루트는 리소스, 나머지는 자바로 본다. 그 모듈에 리소스 루트가 없으면 자바 루트 옆 resources 로 둔다.
   * shortcut: 루트 종류(JavaSourceRootType)가 빌드 클래스패스에 없어 이름 · 경로로 가른다. 이름이 다른 리소스 루트는 자바로 잡힌다.
   */
  static Roots find(Project project) {
    String base = project.getBasePath();
    ProjectFileIndex index = ProjectFileIndex.getInstance(project);
    Map<Module, VirtualFile> javaOf = new LinkedHashMap<>();
    Map<Module, VirtualFile> resOf = new LinkedHashMap<>();
    for (VirtualFile root : ProjectRootManager.getInstance(project).getContentSourceRoots()) {
      String path = root.getPath();
      if (path.contains(TEST_SEGMENT) || path.contains(GENERATED)) continue;
      Module module = index.getModuleForFile(root);
      (RESOURCES_DIR.equals(root.getName()) ? resOf : javaOf).putIfAbsent(module, root);
    }
    if (javaOf.isEmpty()) {
      VirtualFile res = resOf.isEmpty() ? null : resOf.values().iterator().next();
      return new Roots(base, base + "/" + DEFAULT_JAVA, res != null ? res.getPath() : base + "/" + DEFAULT_RESOURCES, FALLBACK_PACKAGE);
    }
    Map.Entry<Module, VirtualFile> first = javaOf.entrySet().iterator().next();
    VirtualFile java = first.getValue();
    VirtualFile res = resOf.get(first.getKey());
    String resPath = res != null ? res.getPath() : siblingResources(java);
    return new Roots(base, java.getPath(), resPath, guessPackage(java));
  }

  /** src/main/java → src/main/resources. 부모가 없으면 자바 루트 아래 resources. */
  private static String siblingResources(VirtualFile java) {
    VirtualFile parent = java.getParent();
    return (parent != null ? parent.getPath() : java.getPath()) + "/" + RESOURCES_DIR;
  }

  /** 있는 *Mapper.java 의 패키지(층 이름 마디는 뗌), 없으면 하위 폴더가 하나뿐인 동안 내려간 패키지, 그것도 없으면 com.example. */
  private static String guessPackage(VirtualFile javaRoot) {
    VirtualFile[] found = new VirtualFile[1];
    VfsUtilCore.visitChildrenRecursively(javaRoot, new VirtualFileVisitor<Void>() {
      @Override
      public boolean visitFile(VirtualFile file) {
        if (found[0] != null) return false;
        if (!file.isDirectory() && file.getName().endsWith(MAPPER_SUFFIX)) found[0] = file;
        return found[0] == null;
      }
    });
    if (found[0] != null) {
      try {
        Matcher m = PACKAGE.matcher(VfsUtilCore.loadText(found[0]));
        if (m.find()) {
          String pkg = m.group(1);
          String last = Names.simple(pkg);
          return LAYER_SEGMENTS.contains(last) && pkg.contains(".") ? Names.packageOf(pkg) : pkg;
        }
      } catch (IOException e) {
        // 읽지 못하면 폴더 구조로 짐작한다(기본값을 고르는 일이라 실패해도 사용자가 고쳐 쓴다).
      }
    }
    List<String> parts = new ArrayList<>();
    VirtualFile dir = javaRoot;
    while (true) {
      VirtualFile only = null;
      int dirs = 0;
      boolean files = false;
      for (VirtualFile ch : dir.getChildren()) {
        if (ch.isDirectory()) { dirs++; only = ch; } else { files = true; }
      }
      if (dirs != 1 || files) break;
      parts.add(only.getName());
      dir = only;
    }
    return parts.isEmpty() ? FALLBACK_PACKAGE : String.join(".", parts);
  }

  static boolean exists(String absPath) {
    return find(absPath) != null;
  }

  /** 이미 있으면 그 글. 열린 편집기의 저장 안 한 글까지 본다. 없으면 null. */
  static String read(String absPath) throws IOException {
    VirtualFile f = find(absPath);
    if (f == null) return null;
    Document doc = FileDocumentManager.getInstance().getDocument(f);
    return doc != null ? doc.getText() : VfsUtilCore.loadText(f);
  }

  /**
   * 할 일 하나. 파일이 없으면 text 로 새로 만들고, 있으면 replace 일 때만 text 로 바꾼다(덧붙인 결과를 미리 만들어 넘긴다).
   */
  record Job(String absPath, String text, boolean replace) {}

  /** 화면 스레드에서 부른다. 쓰기는 한 번의 명령(되돌리기 한 번)으로 묶는다. */
  static void write(Project project, String commandName, List<Job> jobs) throws IOException {
    WriteCommandAction.writeCommandAction(project).withName(commandName).run(() -> {
      for (Job job : jobs) {
        Path path = Path.of(job.absPath());
        VirtualFile f = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path);
        if (f == null) {
          f = mkdirs(path.getParent()).createChildData(Targets.class, path.getFileName().toString());
          f.setBinaryContent(job.text().getBytes(StandardCharsets.UTF_8));
        } else if (job.replace()) {
          Document doc = FileDocumentManager.getInstance().getDocument(f);
          if (doc == null) throw new IOException(EiDbBundle.message("targets.not.text.file", job.absPath()));
          doc.setText(job.text());
        }
      }
    });
  }

  private static VirtualFile find(String absPath) {
    return VirtualFileManager.getInstance().findFileByNioPath(Path.of(absPath));
  }

  /** 가장 가까운 있는 조상부터 빠진 폴더를 VFS 로 만든다(쓰기 동작 안에서). */
  private static VirtualFile mkdirs(Path dir) throws IOException {
    VirtualFile found = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(dir);
    if (found != null) return found;
    if (dir.getParent() == null) throw new IOException(EiDbBundle.message("targets.mkdir.failed", dir));
    return mkdirs(dir.getParent()).createChildDirectory(Targets.class, dir.getFileName().toString());
  }

  /** 편집기로 연다. 파일이 없으면 아무것도 하지 않는다. */
  static void open(Project project, String absPath) {
    VirtualFile f = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(Path.of(absPath));
    if (f != null) PsiNavigationSupport.getInstance().createNavigatable(project, f, 0).navigate(true);
  }
}
