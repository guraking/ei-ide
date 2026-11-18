package local.bootservers;

import com.intellij.openapi.application.ApplicationNamesInfo;

import java.util.Set;

/**
 * 이 플러그인이 Moa IDE 설치본 안에서 도는지 가린다. IntelliJ 에 「디스크에서 설치」로 넣은 플러그인 판에서는 거짓이다.
 * 제품 이름은 kit/customize.py brand 가 ApplicationInfo.xml 의 fullname 에 넣는 값이다(옛 이름 PREVIOUS_NAMES 포함).
 */
final class MoaProduct {
    private static final Set<String> NAMES = Set.of("Moa IDE", "EI IDE");

    private MoaProduct() {
    }

    static boolean isMoa() {
        return NAMES.contains(ApplicationNamesInfo.getInstance().getFullProductName());
    }
}
