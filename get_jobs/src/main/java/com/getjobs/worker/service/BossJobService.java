package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Boss直聘任务服务
 * 管理Boss平台的投递任务执行和状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BossJobService implements JobPlatformService {
    private static final String PLATFORM = "boss";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Boss> bossProvider;

    // 任务运行状态
    private volatile boolean isRunning = false;
    // 停止标志
    private volatile boolean shouldStop = false;
    // 本次任务的开始时间（毫秒），用于判断任务是不是卡死了
    private volatile long runningSince = 0L;

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        // Legacy route does not enforce the internship policy and is permanently disabled.
        progressCallback.accept(JobProgressMessage.error(PLATFORM, "旧投递入口已关闭，请使用实习工作台"));
    }

    @Override
    public void stopDelivery() {
        if (!isRunning) {
            return;
        }
        log.info("收到停止Boss投递任务的请求");
        shouldStop = true;

        // shouldStop 只在投递流程的检查点生效；如果任务卡在某个 Playwright 调用里，
        // 它永远看不到这个标志，isRunning 就会一直是 true，把"开始投递"永久堵死。
        // 这里给它一段时间自行退出，超时就强制把状态复位，至少让界面能重新发起任务。
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + FORCE_RESET_GRACE_MS;
            while (isRunning && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (isRunning) {
                log.warn("Boss投递任务在 {} 秒内未响应停止指令，强制复位运行状态（后台可能仍有残留操作）",
                        FORCE_RESET_GRACE_MS / 1000);
                isRunning = false;
                runningSince = 0L;
                try {
                    playwrightManager.resumeBossMonitoring();
                } catch (Exception ignored) {}
            }
        }, "boss-stop-watchdog").start();
    }

    /** 停止指令发出后，等待任务自行退出的宽限时间 */
    private static final long FORCE_RESET_GRACE_MS = 15_000L;

    /** 任务已经跑了多久（毫秒）；没在跑返回 0 */
    public long runningForMillis() {
        long since = runningSince;
        return since == 0L ? 0L : System.currentTimeMillis() - since;
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 检查是否应该停止
     * 供Boss.java调用
     */
    public boolean shouldStop() {
        return shouldStop;
    }

    
}
