// Copyright 2026 KingGura IDE. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.customization.java;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.AnActionWrapper;
import com.intellij.openapi.actionSystem.Constraints;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 주 메뉴를 이클립스 순서로 다시 세운다.
 * File · Edit · Source · Refactor · Navigate · Search · Project · Run · (그 밖의 메뉴) · Window · Help
 *
 * View · Code · Build · Tools 메뉴는 주 메뉴에서만 빼고 동작 자체는 등록된 채로 둔다.
 * 그 안의 항목은 Source · Project · Window 메뉴에서 다시 참조하거나, Find Action 으로 계속 쓸 수 있다.
 * Git 처럼 여기 이름이 없는 메뉴는 지우지 않고 Run 과 Window 사이로 모은다.
 */
final class KingGuraMainMenuCustomizer implements ActionConfigurationCustomizer,
                                                  ActionConfigurationCustomizer.SyncHeavyCustomizeStrategy {
  private static final List<String> HEAD = List.of(
    "FileMenu", "EditMenu", "KG.SourceMenu", "RefactoringMenu", "GoToMenu", "KG.SearchMenu", "KG.ProjectMenu", "RunMenu");
  private static final Set<String> HIDDEN = Set.of("ViewMenu", "CodeMenu", "BuildMenu", "ToolsMenu");
  private static final List<String> TAIL = List.of("WindowMenu", "HelpMenu");

  @Override
  public void customize(ActionManager actionManager) {
    if (!(actionManager.getAction("MainMenu") instanceof DefaultActionGroup mainMenu)) {
      return;
    }

    List<AnAction> others = new ArrayList<>();
    for (AnAction child : mainMenu.getChildActionsOrStubs()) {
      String id = actionManager.getId(child);
      if (id == null || (!HEAD.contains(id) && !HIDDEN.contains(id) && !TAIL.contains(id))) {
        others.add(child);
      }
    }

    mainMenu.removeAll();
    addById(mainMenu, HEAD, actionManager);
    for (AnAction other : others) {
      mainMenu.add(other, actionManager);
    }
    addById(mainMenu, TAIL, actionManager);

    // 인텔리제이의 Navigate 메뉴(GoToMenu)를 이클립스 이름으로 부른다.
    AnAction navigate = actionManager.getAction("GoToMenu");
    if (navigate != null) {
      navigate.getTemplatePresentation().setText("_Navigate");
    }

    addProjectRefreshToTreePopup(actionManager);
  }

  /**
   * 프로젝트 트리 우클릭 맨 위에 이클립스의 Maven › Update Project, Gradle › Refresh 에 해당하는 동작을 둔다.
   * 메이븐 · 그래들 플러그인은 이 플러그인보다 늦게 올라올 수 있어 XML 참조 대신 여기서 찾는다.
   * 동작이 없으면(플러그인을 끈 경우) 그 항목만 건너뛴다.
   */
  private static void addProjectRefreshToTreePopup(ActionManager actionManager) {
    if (!(actionManager.getAction("ProjectViewPopupMenu") instanceof DefaultActionGroup treePopup)) {
      return;
    }
    // Maven.ReimportProject: 고른 프로젝트만 다시 읽기(Maven 프로젝트가 아니면 스스로 숨는다),
    // ExternalSystem.RefreshAllProjects: 그래들 프로젝트 전체 다시 읽기(연결된 프로젝트가 없어도 보이므로 GradleOnly 로 감싼다)
    List<AnAction> refresh = new ArrayList<>();
    AnAction maven = actionManager.getAction("Maven.ReimportProject");
    if (maven != null) {
      refresh.add(maven);
    }
    AnAction gradle = actionManager.getAction("ExternalSystem.RefreshAllProjects");
    if (gradle != null) {
      refresh.add(new GradleOnly(gradle));
    }
    if (refresh.isEmpty()) {
      return;
    }
    treePopup.add(Separator.getInstance(), Constraints.FIRST, actionManager);
    for (int i = refresh.size() - 1; i >= 0; i--) {
      treePopup.add(refresh.get(i), Constraints.FIRST, actionManager);
    }
  }

  /** 모듈 콘텐츠 루트 어디에도 Gradle 파일이 없으면 숨긴다. */
  private static final class GradleOnly extends AnActionWrapper {
    private static final List<String> GRADLE_FILES = List.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts");

    GradleOnly(AnAction action) {
      super(action);
    }

    @Override
    public void update(AnActionEvent e) {
      super.update(e);
      if (e.getPresentation().isVisible() && !hasGradleRoot(e.getProject())) {
        e.getPresentation().setEnabledAndVisible(false);
      }
    }

    private static boolean hasGradleRoot(Project project) {
      if (project == null) {
        return false;
      }
      for (Module module : ModuleManager.getInstance(project).getModules()) {
        for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
          for (String name : GRADLE_FILES) {
            if (root.findChild(name) != null) {
              return true;
            }
          }
        }
      }
      return false;
    }
  }

  private static void addById(DefaultActionGroup group, List<String> ids, ActionManager actionManager) {
    for (String id : ids) {
      AnAction action = actionManager.getAction(id);
      if (action != null) {
        group.add(action, actionManager);
      }
    }
  }
}
