package local.bootservers;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import local.bootservers.OpenApiSpec.Param;
import local.bootservers.OpenApiSpec.Schema;
import local.bootservers.was.WasSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 편집기 우클릭 「Send API Request」: 커서가 있는 스프링 컨트롤러 메서드(@RequestMapping · @GetMapping 등)의 요청 화면을 편집기 탭으로 연다.
 * 주소 변수 · 쿼리(@RequestParam, 주석 없는 VO 의 필드) · 헤더 · JSON 본문(@RequestBody)을 시험 값으로 채운다.
 * 서버 주소는 이 모듈을 올린 WAS(서버 창) → 실행 중인 스프링 부트 서버 → 설정 파일 server.port 순.
 */
public final class ApiRequestAction extends AnAction {

    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String ACCEPT = "Accept";
    /** JSON API 와 JSP 화면을 모두 받는다. */
    private static final String ACCEPT_ANY = "application/json, text/html, */*";
    private static final String BODY_NAME = "body";

    @Override
    public void update(@NotNull AnActionEvent e) {
        PsiMethod method = method(e);
        e.getPresentation().setEnabledAndVisible(method != null && !UrlMappingAction.of(method).isEmpty());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        List<UrlMappingAction.Endpoint> endpoints = ReadAction.compute(() -> {
            PsiMethod method = method(e);
            return method == null ? List.<UrlMappingAction.Endpoint>of() : UrlMappingAction.of(method);
        });
        if (!endpoints.isEmpty()) {
            // 경로가 여럿이면 첫 경로. 다른 경로는 주소 칸에서 고쳐 보낸다.
            open(project, endpoints.get(0));
        }
    }

    /** 커서가 있는 자바 메서드. 자바 파일이 아니거나 메서드 밖이면 null. */
    private static @Nullable PsiMethod method(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        if (e.getProject() == null || editor == null || !(file instanceof PsiJavaFile)) {
            return null;
        }
        return PsiTreeUtil.getParentOfType(file.findElementAt(editor.getCaretModel().getOffset()), PsiMethod.class, false);
    }

    /** 요청 값을 백그라운드에서 모은 뒤 요청 화면을 연다. 코드가 바뀌어 메서드가 없어졌으면 알린다. */
    static void open(Project project, UrlMappingAction.Endpoint endpoint) {
        ReadAction.nonBlocking(() -> request(project, endpoint))
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), request -> {
                    if (request == null) {
                        Messages.showWarningDialog(project, BootServersBundle.message("url.method.gone"), BootServersBundle.message("api.title"));
                    } else {
                        ApiRequestEditor.open(project, request);
                    }
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    /** 읽기 동작 안에서 부른다. 메서드가 없어졌으면 null. */
    private static @Nullable ApiRequestEditor.Request request(Project project, UrlMappingAction.Endpoint endpoint) {
        PsiMethod method = endpoint.target().getElement();
        if (method == null) {
            return null;
        }
        Map<String, Map<String, Schema>> components = new LinkedHashMap<>();
        List<Param> params = new ArrayList<>();
        Map<String, String> defaults = new HashMap<>();
        Schema body = null;
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            if (AnnotationUtil.findAnnotation(parameter, OpenApiCollector.REQUEST_BODY) != null) {
                body = OpenApiCollector.schema(parameter.getType(), components);
            } else {
                OpenApiCollector.addParams(parameter, params, components);
            }
            PsiAnnotation requestParam = AnnotationUtil.findAnnotation(parameter, OpenApiCollector.REQUEST_PARAM);
            String defaultValue = requestParam == null ? null
                    : AnnotationUtil.getDeclaredStringAttributeValue(requestParam, OpenApiCollector.ATTR_DEFAULT_VALUE);
            if (defaultValue != null) {
                defaults.put(OpenApiCollector.paramName(requestParam, parameter), defaultValue);
            }
        }

        String today = LocalDate.now().toString();
        Map<String, String> pathValues = new HashMap<>();
        List<Map.Entry<String, String>> query = new ArrayList<>();
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(Map.entry(ACCEPT, ACCEPT_ANY));
        if (body != null) {
            headers.add(Map.entry(CONTENT_TYPE, JSON_MEDIA_TYPE));
        }
        for (Param p : params) {
            String value = ApiText.sample(p.name(), p.schema(), defaults.get(p.name()), today);
            switch (p.in()) {
                case OpenApiSpec.IN_PATH -> pathValues.put(p.name(), value);
                case OpenApiSpec.IN_HEADER -> headers.add(Map.entry(p.name(), value));
                default -> query.add(Map.entry(p.name(), value));
            }
        }
        Module module = ModuleUtilCore.findModuleForPsiElement(method);
        String base = wasBase(project, module);
        if (base == null) {
            base = ServerBase.resolve(project, module);
        }
        String httpMethod = OpenApiSpec.methods(endpoint.httpMethod()).get(0).toUpperCase(Locale.ROOT);
        String bodyText = body == null ? "" : ApiText.toJson(ApiText.sampleJson(BODY_NAME, body, components, today));
        return new ApiRequestEditor.Request(httpMethod + " " + endpoint.owner().replace('.', '#'), httpMethod,
                ApiText.join(base, ApiText.fillPath(endpoint.url(), pathValues)), query, headers, bodyText);
    }

    /** 서버 창의 WAS 가운데 이 모듈(또는 이 모듈을 쓰는 모듈)을 올린 첫 WAS 의 주소. 없으면 null. */
    private static @Nullable String wasBase(Project project, @Nullable Module controller) {
        if (controller == null) {
            return null;
        }
        ModuleManager modules = ModuleManager.getInstance(project);
        Module folder = ServerLauncher.folderModule(modules, controller);
        for (WasSettings.Server server : WasSettings.getInstance(project).servers()) {
            for (WasSettings.Deployment d : server.deployments) {
                Module deployed = modules.findModuleByName(d.module);
                if (deployed != null && (deployed.equals(folder) || ModuleUtilCore.getAllDependentModules(folder).contains(deployed))) {
                    return HttpText.baseUrl(server.httpPort, d.contextPath);
                }
            }
        }
        return null;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
