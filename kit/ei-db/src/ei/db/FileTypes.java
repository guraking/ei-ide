package ei.db;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.testFramework.LightVirtualFile;

/** 미리보기 편집기에 줄 파일 종류. */
public final class FileTypes {

  private FileTypes() {}

  /**
   * 확장자의 파일 종류. 확장자 표에 없으면(.sql 처럼 TextMate 가 맡는 확장자) 메모리 파일을 감지시켜 TextMate 종류를 받는다.
   * 감지도 안 되면 UnknownFileType 을 돌려주어 색 없는 편집기가 된다.
   */
  public static FileType byExtension(String extension) {
    FileType type = FileTypeManager.getInstance().getFileTypeByExtension(extension);
    if (type != UnknownFileType.INSTANCE) return type;
    return FileTypeRegistry.getInstance().getFileTypeByFile(new LightVirtualFile("a." + extension, ""));
  }
}
