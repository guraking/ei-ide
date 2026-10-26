package local.bootservers;

import com.intellij.diagnostic.VMOptions;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * PC 의 실제 메모리가 16 GB 이상이면 IDE 최대 메모리(-Xmx)를 4096 MB 로 올린다. 그 아래는 기본 2048 MB 그대로다.
 * 사용자 vmoptions(설정 폴더의 idea64.exe.vmoptions)에 쓰므로 다시 시작해야 적용된다.
 * IDE 설정마다 한 번만 한다. 그 뒤 Help → Change Memory Settings 로 직접 바꾼 값은 그대로 둔다.
 */
public final class MemoryDefault implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(MemoryDefault.class);
    /** 적용했다는 표시. 앱 수준 PropertiesComponent(other.xml)에 남는다. */
    private static final String APPLIED_KEY = "eiide.memory.heap4g.applied";
    private static final int HEAP_MB = 4096;
    /** 16 GB 를 단 PC 도 내장 그래픽 몫을 빼면 15.x GB 로 보이므로 15 GB 를 기준으로 한다. */
    private static final long MIN_RAM_BYTES = 15L << 30;

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        PropertiesComponent properties = PropertiesComponent.getInstance();
        if (properties.getBoolean(APPLIED_KEY)) return;
        properties.setValue(APPLIED_KEY, true);

        long ram = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        int heap = VMOptions.readOption(VMOptions.MemoryKind.HEAP, false);
        if (ram < MIN_RAM_BYTES || heap >= HEAP_MB || !VMOptions.canWriteOptions()) return;
        try {
            VMOptions.setOption(VMOptions.MemoryKind.HEAP, HEAP_MB);
        } catch (Exception e) {
            LOG.warn("최대 메모리 설정 실패", e);
            return;
        }
        NotificationGroupManager.getInstance().getNotificationGroup("IDE 메모리")
                .createNotification(BootServersBundle.message("memory.heap.raised", String.valueOf(HEAP_MB), Math.round(ram / (double) (1L << 30))), NotificationType.INFORMATION)
                .notify(null);
    }
}
