package ei.db;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;

public final class Notify {
  private Notify() {}

  public static void info(Project project, String text) {
    send(project, text, NotificationType.INFORMATION);
  }

  public static void warning(Project project, String text) {
    send(project, text, NotificationType.WARNING);
  }

  public static void error(Project project, String text) {
    send(project, text, NotificationType.ERROR);
  }

  private static void send(Project project, String text, NotificationType type) {
    NotificationGroupManager.getInstance().getNotificationGroup("EI Database").createNotification(text, type).notify(project);
  }
}
