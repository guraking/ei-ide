package local.bootservers.ui;

import com.intellij.ui.ScreenUtil;

import java.awt.Dimension;
import java.awt.Rectangle;

/** 창 처음 크기를 화면 크기의 비율로 정한다. 그 뒤 크기는 DialogWrapper.getDimensionServiceKey 가 기억한다. */
public final class DialogSize {

    private DialogSize() {
    }

    /** 주 화면(작업 표시줄 뺀 영역)의 width · height 비율(0~1) 크기. */
    public static Dimension ofScreen(double width, double height) {
        Rectangle screen = ScreenUtil.getMainScreenBounds();
        return new Dimension((int) Math.round(screen.width * width), (int) Math.round(screen.height * height));
    }
}
