package ei.db.mybatis;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * 서버 창 플러그인의 local.bootservers.ApiReplay 를 부르는 다리.
 * ei-db 는 서버 창 클래스 없이 따로 컴파일되므로(compile_plugins.py 가 플러그인마다 IDE jar 만으로 컴파일) 이름으로 부른다.
 * 서버 창 플러그인이 있을 때만 등록되는 ei-db-bootservers.xml 의 액션에서만 쓴다: 그때 ei-db 클래스 로더가 서버 창 클래스를 찾는다.
 * 서명이 맞지 않으면(판이 어긋난 jar) IllegalStateException.
 */
final class ApiBridge {
  private ApiBridge() {}

  private static final String CLASS = "local.bootservers.ApiReplay";
  private static final String RESULT_CLASS = CLASS + "$Result";

  /** ApiReplay.Result 를 옮긴 것. raw 는 showDiff 에 되돌려 줄 원래 객체. beforeCode 는 저장한 응답이 없으면 음수. */
  record Result(Object raw, int beforeCode, int nowCode, @Nullable String error, boolean same) {}

  @SuppressWarnings("unchecked")
  static List<String> savedKeys(Project project, String classAndMethod) {
    return (List<String>) call("savedKeys", new Class<?>[]{Project.class, String.class}, project, classAndMethod);
  }

  static String title(Project project, String key) {
    return (String) call("title", new Class<?>[]{Project.class, String.class}, project, key);
  }

  static boolean changesData(Project project, String key) {
    return (Boolean) call("changesData", new Class<?>[]{Project.class, String.class}, project, key);
  }

  /** 백그라운드에서만. */
  static Result resend(Project project, String key) {
    Object r = call("resend", new Class<?>[]{Project.class, String.class}, project, key);
    return new Result(r, (Integer) get(r, "beforeCode"), (Integer) get(r, "nowCode"), (String) get(r, "error"), (Boolean) get(r, "same"));
  }

  /** 화면 스레드에서. */
  static void showDiff(Project project, Result result) {
    call("showDiff", new Class<?>[]{Project.class, type(RESULT_CLASS)}, project, result.raw());
  }

  private static Object call(String name, Class<?>[] types, Object... args) {
    try {
      Method m = type(CLASS).getMethod(name, types);
      return m.invoke(null, args);
    } catch (InvocationTargetException e) {
      throw rethrow(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("서버 창 플러그인의 ApiReplay." + name + " 를 찾지 못했습니다: " + e, e);
    }
  }

  private static Object get(Object target, String accessor) {
    try {
      return target.getClass().getMethod(accessor).invoke(target);
    } catch (InvocationTargetException e) {
      throw rethrow(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("서버 창 플러그인의 ApiReplay.Result." + accessor + " 를 찾지 못했습니다: " + e, e);
    }
  }

  private static Class<?> type(String name) {
    try {
      return Class.forName(name, true, ApiBridge.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("서버 창 플러그인을 찾지 못했습니다: " + name, e);
    }
  }

  /** 취소는 그대로, 그 밖의 실행 중 예외는 RuntimeException 으로 넘긴다. */
  private static RuntimeException rethrow(Throwable cause) {
    if (cause instanceof ProcessCanceledException pce) throw pce;
    if (cause instanceof RuntimeException re) return re;
    if (cause instanceof Error err) throw err;
    return new IllegalStateException(String.valueOf(cause), cause);
  }
}
