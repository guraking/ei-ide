package ei.db.mybatis;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiDocCommentOwner;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiWildcardType;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;
import ei.db.EiDbBundle;
import ei.db.Notify;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 편집기 우클릭 「Swagger 주석 만들기」(springdoc · swagger v3).
 * 매핑 어노테이션이 있는 컨트롤러 메서드: @Operation(summary · description · parameters · requestBody · responses 200)을 만든다.
 *   @RequestBody 매개변수는 requestBody, @RequestParam · @PathVariable · @RequestHeader 와 어노테이션 없는 단순 형식 매개변수는 parameters,
 *   그 밖(HttpServletRequest · Model 등)은 건너뛴다. 응답 형식은 ResponseEntity · List 등을 벗긴 안쪽 형식.
 * 메서드 밖(컨트롤러 클래스): 클래스 위 @Tag(name · description)를 만든다.
 * 넣기 전에 확인 창에서 글을 고친다. 이미 있으면 바꿀지 묻는다. 어노테이션 import 는 함께 넣는다.
 * 글은 문서 글로 넣어(PSI 서식 적용 없음) 「=」 자리 맞춤이 그대로 남는다.
 */
public final class SwaggerDocAction extends AnAction {
  private static final String PKG = "io.swagger.v3.oas.annotations.";
  private static final String OPERATION = "Operation";
  private static final String TAG = "Tag";
  /** 만든 글에 이 글이 있으면 그 어노테이션을 import 한다. */
  private static final Map<String, String> IMPORTS = imports();
  private static final String REQUEST_BODY = "RequestBody";
  private static final String PATH_VARIABLE = "PathVariable";
  private static final Set<String> PARAM_ANNOTATIONS = Set.of("RequestParam", PATH_VARIABLE, "RequestHeader");
  /** 안쪽 형식을 응답 · 본문 형식으로 쓰는 감싸는 형식. */
  private static final Set<String> WRAPPERS = Set.of("ResponseEntity", "HttpEntity", "List", "ArrayList", "Collection", "Set", "Optional",
      "Page", "Slice", "Mono", "Flux", "Callable", "DeferredResult", "CompletableFuture", "Iterable");
  private static final String OPTIONAL = "Optional";
  /** implementation 으로 써도 뜻이 없는 형식. */
  private static final Set<String> NO_SCHEMA = Set.of("Object", "Void", "void");
  /** 감싼 형식을 벗기는 최대 횟수(ResponseEntity&lt;List&lt;X[]&gt;&gt; 정도). */
  private static final int MAX_UNWRAP = 6;

  private static Map<String, String> imports() {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("@Operation(", PKG + OPERATION);
    m.put("@Parameter(", PKG + "Parameter");
    m.put("@ApiResponse(", PKG + "responses.ApiResponse");
    m.put("@Content(", PKG + "media.Content");
    m.put("@Schema(", PKG + "media.Schema");
    m.put("@Tag(", PKG + "tags.Tag");
    return m;
  }

  /** 주소 · 쿼리 · 헤더 매개변수 하나의 재료. name 은 요청에서의 이름, type 은 보이는 형식 글. */
  private record ParamInfo(String name, DocComment.Param param, boolean required) {}

  /**
   * 넣을 자리와 재료. method 가 false 면 클래스 @Tag. start ~ end 는 바꿀 범위(새로 넣으면 같은 값), stamp 는 그때의 문서 판.
   * library 가 false 면 이 모듈에 swagger 어노테이션이 없다. oldFirst · oldDescription 은 바꿀 어노테이션에 적혀 있던
   * summary(@Tag 는 name) · description 글자 상수(없으면 null)로, 확인 창에 먼저 채운다.
   */
  private record Target(boolean method, String name, String docLine, String oldFirst, String oldDescription, List<ParamInfo> params, DocComment.Param body, String bodyType,
                        String responseType, int start, int end, String indent, boolean replace, long stamp, boolean library) {}

  @Override
  public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    boolean ok = editor != null && e.getProject() != null && file != null && ownerAt(file, editor.getCaretModel().getOffset()) != null;
    e.getPresentation().setEnabledAndVisible(ok);
  }

  /**
   * 커서를 감싼 매핑 메서드, 메서드 밖이면 컨트롤러 클래스. 그 밖(매핑 없는 메서드 안, 컨트롤러 아닌 클래스, 자바 아닌 파일)은 null.
   * 읽기 접근 안에서 부른다.
   */
  private static @Nullable PsiDocCommentOwner ownerAt(PsiFile file, int offset) {
    if (!(file instanceof PsiJavaFile)) return null;
    PsiElement at = file.findElementAt(offset);
    PsiMethod m = PsiTreeUtil.getParentOfType(at, PsiMethod.class, false);
    boolean inMethod = m != null;
    // 람다 · 익명 클래스 안에 커서가 있으면 그것을 감싼 매핑 메서드를 찾는다.
    while (m != null && !LayerRules.any(LayerNav.annotationNames(m), LayerRules.MAPPING_ANNOTATIONS)) {
      m = PsiTreeUtil.getParentOfType(m, PsiMethod.class, true);
    }
    if (inMethod) return m;
    PsiClass c = PsiTreeUtil.getParentOfType(at, PsiClass.class, false);
    if (c == null || c instanceof PsiAnonymousClass || c.getName() == null) return null;
    return LayerRules.any(LayerNav.annotationNames(c), LayerRules.CONTROLLER_ANNOTATIONS) ? c : null;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    Editor editor = e.getData(CommonDataKeys.EDITOR);
    PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
    if (project == null || editor == null || !(file instanceof PsiJavaFile javaFile)) return;
    if (DumbService.isDumb(project)) {
      Notify.warning(project, EiDbBundle.message("doccomment.dumb"));
      return;
    }
    DocComment.Dict dict = DocCommentSettings.dict(project);
    if (dict == null) return;
    Document document = editor.getDocument();
    PsiDocumentManager.getInstance(project).commitDocument(document);
    int offset = editor.getCaretModel().getOffset();
    Target t;
    try {
      t = ProgressManager.getInstance().runProcessWithProgressSynchronously(
          (ThrowableComputable<Target, RuntimeException>) () -> ReadAction.compute(() -> target(javaFile, document, offset)),
          EiDbBundle.message("doccomment.progress"), true, project);
    } catch (ProcessCanceledException ex) {
      return; // 사용자가 진행 창에서 취소한 경우다.
    } catch (IndexNotReadyException ex) {
      Notify.warning(project, EiDbBundle.message("doccomment.dumb"));
      return;
    }
    if (t == null) {
      Notify.warning(project, EiDbBundle.message("swaggerdoc.no.target"));
      return;
    }
    if (!t.library()) {
      Notify.error(project, EiDbBundle.message("swaggerdoc.no.library"));
      return;
    }
    String title = EiDbBundle.message(t.method() ? "swaggerdoc.title" : "swaggerdoc.tag.title");
    if (t.replace() && Messages.showYesNoDialog(project, EiDbBundle.message(t.method() ? "swaggerdoc.replace" : "swaggerdoc.tag.replace"), title,
        EiDbBundle.message("doccomment.replace.ok"), EiDbBundle.message("common.cancel"), Messages.getQuestionIcon()) != Messages.YES) {
      return;
    }
    String text = t.method() ? askOperation(project, title + " — " + t.name(), t, dict) : askTag(project, title + " — " + t.name(), t, dict);
    if (text == null) return;
    if (document.getModificationStamp() != t.stamp()) {
      Notify.warning(project, EiDbBundle.message("doccomment.changed"));
      return;
    }
    // 새로 넣을 때: @Operation 묶음 뒤에는 빈 줄을 하나 두고, @Tag 는 바로 다음 줄에 원래 글이 온다.
    String tail = t.replace() ? "" : (t.method() ? "\n\n" : "\n") + t.indent();
    String placed = text.replace("\n", "\n" + t.indent()) + tail;
    WriteCommandAction.writeCommandAction(project, file).withName(title).run(() -> {
      document.replaceString(t.start(), t.end(), placed);
      PsiDocumentManager.getInstance(project).commitDocument(document);
      addImports(project, javaFile, text);
    });
  }

  /** 확인 창을 띄워 @Operation 글을 만든다. 취소하면 null. */
  private static @Nullable String askOperation(Project project, String title, Target t, DocComment.Dict d) {
    List<SwaggerDocDialog.Row> rows = new ArrayList<>();
    rows.add(new SwaggerDocDialog.Row(EiDbBundle.message("swaggerdoc.row.summary"), orElse(t.oldFirst(), SwaggerDoc.summary(t.docLine(), t.name(), d)), true));
    rows.add(new SwaggerDocDialog.Row(EiDbBundle.message("swaggerdoc.row.description"), orElse(t.oldDescription(), SwaggerDoc.description(t.docLine(), t.name(), d)), false));
    for (ParamInfo p : t.params()) {
      rows.add(new SwaggerDocDialog.Row(EiDbBundle.message("swaggerdoc.row.param", p.name()), DocComment.paramText(p.param(), Map.of(), Map.of(), d), false));
      rows.add(new SwaggerDocDialog.Row(EiDbBundle.message("swaggerdoc.row.example", p.name()), "", false));
    }
    if (t.body() != null) rows.add(new SwaggerDocDialog.Row(EiDbBundle.message("swaggerdoc.row.body"), SwaggerDoc.bodyDescription(t.body(), d), false));
    rows.add(new SwaggerDocDialog.Row(EiDbBundle.message("swaggerdoc.row.response"), SwaggerDoc.responseDescription(t.name(), d), false));
    SwaggerDocDialog dialog = new SwaggerDocDialog(project, title, rows);
    if (!dialog.showAndGet()) return null;
    List<String> v = dialog.values();
    int i = 0;
    String summary = v.get(i++);
    String description = v.get(i++);
    List<SwaggerDoc.Param> params = new ArrayList<>();
    for (ParamInfo p : t.params()) {
      String text = v.get(i++);
      String example = v.get(i++);
      params.add(new SwaggerDoc.Param(p.name(), text, p.required(), SwaggerDoc.schemaType(p.param().type()), example));
    }
    SwaggerDoc.Body body = t.body() == null ? null : new SwaggerDoc.Body(v.get(i++), t.bodyType());
    return SwaggerDoc.operation(new SwaggerDoc.Op(summary, description, params, body, v.get(i), t.responseType()));
  }

  /** 확인 창을 띄워 @Tag 글을 만든다. 취소하면 null. */
  private static @Nullable String askTag(Project project, String title, Target t, DocComment.Dict d) {
    String name = orElse(t.oldFirst(), SwaggerDoc.tagName(t.docLine(), t.name(), d));
    SwaggerDocDialog dialog = new SwaggerDocDialog(project, title, List.of(
        new SwaggerDocDialog.Row(EiDbBundle.message("swaggerdoc.row.tag.name"), name, true),
        new SwaggerDocDialog.Row(EiDbBundle.message("swaggerdoc.row.description"), orElse(t.oldDescription(), SwaggerDoc.tagDescription(name)), false)));
    if (!dialog.showAndGet()) return null;
    return SwaggerDoc.tag(dialog.values().get(0), dialog.values().get(1));
  }

  /**
   * 만든 글에 쓰인 어노테이션을 import 한다. 쓰기 명령 안에서, 문서를 커밋한 뒤 부른다.
   * 같은 단순 이름의 다른 클래스가 이미 import 돼 있으면 IntelliJ 가 넣지 않는다. 그때는 어느 어노테이션인지 알린다(사용자가 정규 이름으로 고친다).
   */
  private static void addImports(Project project, PsiJavaFile file, String text) {
    JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
    JavaCodeStyleManager style = JavaCodeStyleManager.getInstance(project);
    List<String> clashes = new ArrayList<>();
    for (Map.Entry<String, String> im : IMPORTS.entrySet()) {
      if (!text.contains(im.getKey())) continue;
      PsiClass c = facade.findClass(im.getValue(), file.getResolveScope());
      if (c == null) continue;
      style.addImport(file, c);
      if (!c.equals(facade.getResolveHelper().resolveReferencedClass(c.getName(), file))) clashes.add(im.getValue());
    }
    if (!clashes.isEmpty()) Notify.warning(project, EiDbBundle.message("swaggerdoc.import.clash", String.join(", ", clashes)));
  }

  // ---------------------------------------------------------------- 재료

  /** 읽기 접근 안에서 부른다. 대상이 없으면 null. */
  private static @Nullable Target target(PsiJavaFile file, Document document, int offset) {
    PsiDocCommentOwner owner = ownerAt(file, offset);
    PsiModifierList modifiers = owner == null ? null : owner.getModifierList();
    if (modifiers == null) return null;
    boolean method = owner instanceof PsiMethod;
    PsiAnnotation old = annotation(modifiers.getAnnotations(), method ? OPERATION : TAG);
    CharSequence text = document.getCharsSequence();
    int start = modifiers.getTextRange().getStartOffset();
    int end = start;
    if (old != null) {
      int[] range = SwaggerDoc.withMarkers(text, old.getTextRange().getStartOffset(), old.getTextRange().getEndOffset());
      start = range[0];
      end = range[1];
    }
    int lineStart = document.getLineStartOffset(document.getLineNumber(start));
    String before = text.subSequence(lineStart, start).toString();
    String indent = before.isBlank() ? before : "";
    boolean library = JavaPsiFacade.getInstance(file.getProject()).findClass(PKG + OPERATION, file.getResolveScope()) != null;
    List<ParamInfo> params = new ArrayList<>();
    DocComment.Param body = null;
    String bodyType = null;
    String responseType = null;
    if (owner instanceof PsiMethod m) {
      for (PsiParameter p : m.getParameterList().getParameters()) {
        DocComment.Param dp = new DocComment.Param(p.getName(), p.getType().getPresentableText());
        PsiAnnotation[] anns = p.getAnnotations();
        if (annotation(anns, REQUEST_BODY) != null) {
          if (body == null) {
            body = dp;
            bodyType = schemaClass(p.getType());
          }
          continue;
        }
        PsiAnnotation a = paramAnnotation(anns);
        if (a != null) params.add(new ParamInfo(requestName(a, p.getName()), dp, required(a, p.getType())));
        else if (anns.length == 0 && SwaggerDoc.isSimple(dp.type())) params.add(new ParamInfo(p.getName(), dp, false));
      }
      PsiType rt = m.getReturnType();
      responseType = rt == null ? null : schemaClass(rt);
    }
    return new Target(method, owner.getName(), docLine(owner.getDocComment()), literal(old, method ? "summary" : "name"), literal(old, "description"), params, body, bodyType, responseType,
        start, end, indent, old != null, document.getModificationStamp(), library);
  }

  private static @Nullable PsiAnnotation annotation(PsiAnnotation[] annotations, String shortName) {
    for (PsiAnnotation a : annotations) {
      if (shortName.equals(shortName(a))) return a;
    }
    return null;
  }

  /** 어노테이션 속성의 글자 상수. 어노테이션 · 속성이 없거나 상수가 아니거나 비었으면 null. */
  private static @Nullable String literal(@Nullable PsiAnnotation a, String attribute) {
    PsiAnnotationMemberValue v = a == null ? null : a.findDeclaredAttributeValue(attribute);
    return v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s && !s.isBlank() ? s : null;
  }

  private static String orElse(@Nullable String first, String second) {
    return first != null ? first : second;
  }

  private static @Nullable PsiAnnotation paramAnnotation(PsiAnnotation[] annotations) {
    for (PsiAnnotation a : annotations) {
      if (PARAM_ANNOTATIONS.contains(shortName(a))) return a;
    }
    return null;
  }

  private static @Nullable String shortName(PsiAnnotation a) {
    PsiJavaCodeReferenceElement ref = a.getNameReferenceElement();
    return ref == null ? null : ref.getReferenceName();
  }

  /** 어노테이션의 value 또는 name 글자 상수. 없거나 상수가 아니면 변수 이름. */
  private static String requestName(PsiAnnotation a, String variable) {
    for (String attr : new String[]{"value", "name"}) {
      PsiAnnotationMemberValue v = a.findDeclaredAttributeValue(attr);
      if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s && !s.isBlank()) return s;
    }
    return variable;
  }

  /** required = false 를 적었거나, defaultValue 가 있거나, Optional 형식이면 false. 그 밖에는 true(스프링 기본값). */
  private static boolean required(PsiAnnotation a, PsiType type) {
    PsiAnnotationMemberValue r = a.findDeclaredAttributeValue("required");
    if (r != null && "false".equals(r.getText())) return false;
    if (a.findDeclaredAttributeValue("defaultValue") != null) return false;
    return !(type instanceof PsiClassType c && OPTIONAL.equals(c.getClassName()));
  }

  /**
   * implementation 에 쓸 형식 단순 이름. 감싸는 형식 · 배열 · 와일드카드를 벗긴 안쪽 형식이며, void · Object · 형식 변수 · 풀리지 않는 형식 ·
   * 안쪽을 모르는 감싸는 형식(ResponseEntity&lt;?&gt;)은 null.
   * shortcut: 다른 클래스 안에 든 형식은 바깥 이름 없이 단순 이름만 쓴다, 컴파일 오류가 나면 사용자가 바깥 이름을 붙인다.
   */
  private static @Nullable String schemaClass(PsiType type) {
    PsiType t = type;
    for (int i = 0; i < MAX_UNWRAP && t != null; i++) {
      if (t instanceof PsiWildcardType w) {
        t = w.getBound();
      } else if (t instanceof PsiArrayType a) {
        t = a.getComponentType();
      } else if (t instanceof PsiClassType c && WRAPPERS.contains(c.getClassName())) {
        PsiType[] args = c.getParameters();
        t = args.length == 1 ? args[0] : null;
      } else {
        break;
      }
    }
    if (t instanceof PsiPrimitiveType) return NO_SCHEMA.contains(t.getPresentableText()) ? null : t.getPresentableText();
    if (!(t instanceof PsiClassType c)) return null;
    PsiClass resolved = c.resolve();
    String name = c.getClassName();
    if (resolved == null || resolved instanceof PsiTypeParameter || name == null || NO_SCHEMA.contains(name) || WRAPPERS.contains(name)) return null;
    return name;
  }

  /** 문서 주석 설명의 첫 줄(별표 · 빈칸 뺌). 없으면 null. */
  private static @Nullable String docLine(@Nullable PsiDocComment doc) {
    if (doc == null) return null;
    StringBuilder b = new StringBuilder();
    for (PsiElement el : doc.getDescriptionElements()) b.append(el.getText());
    for (String line : b.toString().split("\\R")) {
      String s = line.trim();
      while (s.startsWith("*")) s = s.substring(1).trim();
      if (!s.isEmpty()) return s;
    }
    return null;
  }
}
