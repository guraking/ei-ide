package ei.db.bench;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;

/**
 * 쿼리 작업대 · 실행 전 쿼리 점검 설정(접속 · 탭 · JOIN 모양 · 값 자리 · 자세히 · 건수 자동 · 점검 켜짐)은 프로젝트마다 둔다.
 * 그 프로젝트에 값이 없으면 예전 판이 IDE 전체에 저장한 값을 읽어 이어 쓰고, 쓸 때는 프로젝트에만 쓴다.
 * 칸 너비 · 분할 비율 · 창 크기는 화면 모양이라 IDE 전체에 그대로 둔다.
 */
final class BenchSettings {
  private BenchSettings() {}

  static String get(Project project, String key, String fallback) {
    String own = PropertiesComponent.getInstance(project).getValue(key);
    return own != null ? own : PropertiesComponent.getInstance().getValue(key, fallback);
  }

  static boolean getBoolean(Project project, String key, boolean fallback) {
    return Boolean.parseBoolean(get(project, key, String.valueOf(fallback)));
  }

  static int getInt(Project project, String key, int fallback) {
    try {
      return Integer.parseInt(get(project, key, String.valueOf(fallback)));
    } catch (NumberFormatException e) {
      return fallback; // 깨진 값이면 처음 값으로
    }
  }

  /** 기본값과 같아도 지우지 않고 쓴다. 지우면 다음에 IDE 전체의 옛 값을 다시 읽는다. */
  static void set(Project project, String key, Object value) {
    PropertiesComponent.getInstance(project).setValue(key, String.valueOf(value));
  }
}
