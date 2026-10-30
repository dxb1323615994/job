package com.getjobs.application.config;

import com.getjobs.worker.service.BossFollowUpService;
import com.getjobs.worker.service.BossJobService;
import com.getjobs.worker.service.Job51JobService;
import com.getjobs.worker.service.JobPlatformService;
import com.getjobs.worker.service.LiepinJobService;
import com.getjobs.worker.service.ZhilianJobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 自动投递 / 复聊 调度（仅工作日）。
 *
 * 策略：Boss 优先投满配额，其他平台吃剩下的时间。
 *
 *   09:30  早场开始，Boss 分批投满 100 个（最多 4 轮，轮间歇 18 分钟），最晚 11:20
 *          剩余时间依次给：猎聘 → 智联 → 51job
 *   11:30  兜底全停
 *
 *   14:00  下午场开始，Boss 分批再投 50 个（最多 3 轮），最晚 14:45
 *   14:45  猎聘 / 智联 / 51job 轮流，每家 15 分钟
 *   15:30  开始复聊
 *   17:00  停止复聊
 *
 * Boss 的每日沟通额度（150）是分批放行的：投出约 25 个就会被卡住一段时间。
 * 所以投递不是一口气跑到底，而是「投一批 → 歇 18 分钟 → 再投一批」；
 * 如果一轮下来 0 产出，说明当天额度真的用完了，立刻停手不再空转。
 *
 * 四个平台共用同一个浏览器实例，必须串行，不能并行。
 * 关闭自动调度：启动服务前设置环境变量 GETJOBS_AUTO_SCHEDULE=false
 */
@Slf4j
@Component
public class DeliveryScheduler {

    /** 上午 Boss 目标数 */
    private static final int BOSS_MORNING_TARGET = 100;
    /** 下午 Boss 目标数 */
    private static final int BOSS_AFTERNOON_TARGET = 50;
    /** 上午 / 下午 Boss 最多尝试轮数（1 轮 = 启动 → 投到停 → 休息） */
    private static final int MAX_BOSS_ROUNDS_MORNING = 4;
    private static final int MAX_BOSS_ROUNDS_AFTERNOON = 3;
    /**
     * Boss 两轮之间的休息分钟数。
     * <p>
     * 2026-10-30 实测：连续投出约 25 个之后，后面每个岗位都会卡在「等待聊天页超时」，
     * 歇 45 分钟后再试就恢复。Boss 的每日额度是分批放行的，硬撞只会空转。
     */
    private static final int BOSS_ROUND_REST_MINUTES = 18;
    /** 下午其他平台每家给多少分钟 */
    private static final int AFTERNOON_OTHER_MINUTES = 15;
    /** 轮询间隔（毫秒） */
    private static final long POLL_MS = 15_000L;

    private final Map<String, JobPlatformService> platforms = new LinkedHashMap<>();
    private final BossFollowUpService followUpService;
    private final JdbcTemplate jdbc;

    public DeliveryScheduler(BossJobService bossService,
                             LiepinJobService liepinService,
                             ZhilianJobService zhilianService,
                             Job51JobService job51Service,
                             BossFollowUpService followUpService,
                             JdbcTemplate jdbcTemplate) {
        this.platforms.put("boss", bossService);
        this.platforms.put("liepin", liepinService);
        this.platforms.put("zhilian", zhilianService);
        this.platforms.put("51job", job51Service);
        this.followUpService = followUpService;
        this.jdbc = jdbcTemplate;
    }

    private boolean disabled() {
        String v = System.getenv("GETJOBS_AUTO_SCHEDULE");
        return v != null && v.trim().equalsIgnoreCase("false");
    }

    // ---------------- 上午场 ----------------

    @Scheduled(cron = "0 30 9 * * MON-FRI")
    public void morningFlow() {
        if (disabled()) return;
        log.info("[调度] ===== 上午场启动 =====");
        new Thread(this::runMorning, "sched-morning").start();
    }

    private void runMorning() {
        int startCount = bossDeliveredToday();
        int target = startCount + BOSS_MORNING_TARGET;
        LocalTime cutoff = LocalTime.of(11, 20);
        log.info("[调度] 上午目标：Boss 再投 {} 个（今日累计 {} → 目标 {}）",
                BOSS_MORNING_TARGET, startCount, target);

        // Boss 的每日沟通额度是分批放行的：投出一批（约 25 个）之后会被卡住一段时间，
        // 撞一次就放弃会白扔掉剩下的额度，所以这里分多轮跑，每轮之间歇一会儿。
        int lastCount = startCount;
        int rounds = 0;
        while (rounds < MAX_BOSS_ROUNDS_MORNING
                && LocalTime.now().isBefore(cutoff)
                && !followUpService.isRunning()) {
            rounds++;
            if (!start("boss", "上午主投#" + rounds)) break;
            waitFor(() -> bossDeliveredToday() >= target
                            || LocalTime.now().isAfter(cutoff)
                            || !platforms.get("boss").isRunning(),
                    "Boss 上午#" + rounds, 120);
            int now = bossDeliveredToday();
            log.info("[调度] Boss 上午第 {} 轮结束，本轮产出 {} 个，今日累计 {} 个",
                    rounds, now - lastCount, now);
            stop("boss");

            if (now >= target) {
                log.info("[调度] Boss 上午已达标，累计 {} 个", now - startCount);
                break;
            }
            if (now == lastCount) {
                // 一轮下来一个都没投出去 —— 日额度是真用完了，别再空转
                log.info("[调度] Boss 本轮 0 产出，判定今日沟通额度已耗尽，结束上午投递");
                break;
            }
            lastCount = now;

            int left = secondsUntil(cutoff);
            if (left < (BOSS_ROUND_REST_MINUTES + 3) * 60) {
                log.info("[调度] 距上午收工仅剩 {} 秒，不再重试", left);
                break;
            }
            log.info("[调度] 未达目标（{} / {}），休息 {} 分钟后开始第 {} 轮",
                    now, target, BOSS_ROUND_REST_MINUTES, rounds + 1);
            sleep(BOSS_ROUND_REST_MINUTES * 60_000L);
        }

        // 剩余时间给其他平台
        String[] others = {"liepin", "zhilian", "51job"};
        for (String p : others) {
            if (LocalTime.now().isAfter(LocalTime.of(11, 25))) {
                log.info("[调度] 已过 11:25，跳过 {}", p);
                break;
            }
            if (followUpService.isRunning()) break;
            if (!start(p, "上午副投")) continue;
            waitUntil(LocalTime.of(11, 30), 12);
            stop(p);
        }

        stopAll();
        log.info("[调度] ===== 上午场结束 =====");
    }

    // ---------------- 下午场 ----------------

    @Scheduled(cron = "0 0 14 * * MON-FRI")
    public void afternoonFlow() {
        if (disabled()) return;
        log.info("[调度] ===== 下午场启动 =====");
        new Thread(this::runAfternoon, "sched-afternoon").start();
    }

    private void runAfternoon() {
        int startCount = bossDeliveredToday();
        int target = startCount + BOSS_AFTERNOON_TARGET;
        LocalTime cutoff = LocalTime.of(14, 45);
        log.info("[调度] 下午目标：Boss 再投 {} 个（今日累计 {} → 目标 {}）",
                BOSS_AFTERNOON_TARGET, startCount, target);

        int lastCount = startCount;
        int rounds = 0;
        while (rounds < MAX_BOSS_ROUNDS_AFTERNOON
                && LocalTime.now().isBefore(cutoff)
                && !followUpService.isRunning()) {
            rounds++;
            if (!start("boss", "下午主投#" + rounds)) break;
            waitFor(() -> bossDeliveredToday() >= target
                            || LocalTime.now().isAfter(cutoff)
                            || !platforms.get("boss").isRunning(),
                    "Boss 下午#" + rounds, 120);
            int now = bossDeliveredToday();
            log.info("[调度] Boss 下午第 {} 轮结束，本轮产出 {} 个，今日累计 {} 个",
                    rounds, now - lastCount, now);
            stop("boss");

            if (now >= target) {
                log.info("[调度] Boss 下午已达标，累计 {} 个", now - startCount);
                break;
            }
            if (now == lastCount) {
                log.info("[调度] Boss 本轮 0 产出，判定今日沟通额度已耗尽，结束下午投递");
                break;
            }
            lastCount = now;

            int left = secondsUntil(cutoff);
            if (left < (BOSS_ROUND_REST_MINUTES + 3) * 60) {
                log.info("[调度] 距 14:45 仅剩 {} 秒，不再重试", left);
                break;
            }
            log.info("[调度] 未达目标（{} / {}），休息 {} 分钟后开始第 {} 轮",
                    now, target, BOSS_ROUND_REST_MINUTES, rounds + 1);
            sleep(BOSS_ROUND_REST_MINUTES * 60_000L);
        }

        // 第二步：其他平台轮流，每家 15 分钟，15:30 前收手
        String[] others = {"liepin", "zhilian", "51job"};
        for (String p : others) {
            if (LocalTime.now().isAfter(LocalTime.of(15, 25))) {
                log.info("[调度] 已过 15:25，跳过 {}", p);
                break;
            }
            if (followUpService.isRunning()) break;
            if (!start(p, "下午副投")) continue;
            waitUntil(LocalTime.of(15, 28), AFTERNOON_OTHER_MINUTES);
            stop(p);
        }

        stopAll();

        // 第三步：等到 15:30 复聊
        int waitSec = secondsUntil(LocalTime.of(15, 30));
        if (waitSec > 0) {
            log.info("[调度] 距复聊还有 {} 秒，等待中", waitSec);
            sleep(waitSec * 1000L);
        }
        sleep(60_000L); // 等投递线程退出、页面释放
        startFollowUp();
    }

    private void startFollowUp() {
        if (followUpService.isRunning()) {
            log.info("[调度] 复聊已在运行，跳过");
            return;
        }
        log.info("[调度] 开始复聊（到 17:00 结束）");
        new Thread(() -> {
            try {
                // dryRun=false, 最多 200 条, 最多 90 分钟（15:30 → 17:00）
                followUpService.execute(false, 200, 90);
            } catch (Exception e) {
                log.error("[调度] 复聊异常: {}", e.getMessage(), e);
            }
        }, "followup-scheduler").start();
    }

    // ---------------- 兜底 ----------------

    @Scheduled(cron = "0 30 11 * * MON-FRI")
    public void forceStopMorning() {
        if (disabled()) return;
        log.info("[调度] 11:30 兜底：停止所有投递");
        stopAll();
    }

    @Scheduled(cron = "0 0 17 * * MON-FRI")
    public void stopFollowUp() {
        if (disabled()) return;
        log.info("[调度] 17:00 停止复聊");
        if (followUpService.isRunning()) {
            followUpService.stop();
        }
    }

    // ---------------- 内部实现 ----------------

    /** 今日已成功投递的岗位数（查 boss_data 表，只统计今天创建的记录） */
    private int bossDeliveredToday() {
        try {
            Integer n = jdbc.queryForObject(
                    "select count(*) from boss_data where delivery_status = '已投递' "
                            + "and substr(created_at, 1, 10) = ?",
                    Integer.class, LocalDate.now().toString());
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.warn("[调度] 查询今日投递数失败: {}", e.getMessage());
            return 0;
        }
    }

    private boolean start(String key, String label) {
        JobPlatformService target = platforms.get(key);
        if (target == null) {
            log.warn("[调度] 未知平台 {}，跳过", key);
            return false;
        }
        if (target.isRunning()) {
            log.info("[调度][{}] {} 已在投递，跳过启动", label, key);
            return true;
        }
        if (followUpService.isRunning()) {
            log.info("[调度][{}] 复聊正在运行，暂不启动 {} 投递", label, key);
            return false;
        }

        // 让其他平台先退场，等页面释放
        platforms.forEach((k, svc) -> {
            if (k.equals(key)) return;
            try {
                if (svc.isRunning()) {
                    log.info("[调度] 切换平台，停止 {}", k);
                    svc.stopDelivery();
                }
            } catch (Exception e) {
                log.warn("[调度] 停止 {} 失败: {}", k, e.getMessage());
            }
        });

        int waited = 0;
        while (othersRunning(key) && waited < 60) {
            sleep(1000);
            waited++;
        }

        log.info("[调度][{}] 开始 {} 投递", label, key);
        new Thread(() -> {
            try {
                target.executeDelivery(msg ->
                        log.info("[调度][{}][{}] {}", label, msg.getPlatform(), msg.getMessage()));
                log.info("[调度][{}] {} 投递线程结束", label, key);
            } catch (Exception e) {
                log.error("[调度][{}] {} 投递异常: {}", label, key, e.getMessage(), e);
            }
        }, "delivery-" + key).start();
        return true;
    }

    private void stop(String key) {
        JobPlatformService svc = platforms.get(key);
        if (svc == null) return;
        try {
            if (svc.isRunning()) {
                log.info("[调度] 停止 {} 投递", key);
                svc.stopDelivery();
            }
        } catch (Exception e) {
            log.warn("[调度] 停止 {} 失败: {}", key, e.getMessage());
        }
    }

    private void stopAll() {
        platforms.keySet().forEach(this::stop);
    }

    private boolean othersRunning(String except) {
        return platforms.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(except) && e.getValue().isRunning());
    }

    private void waitFor(BooleanSupplier cond, String label, int maxMinutes) {
        long deadline = System.currentTimeMillis() + maxMinutes * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            sleep(POLL_MS);
        }
        log.info("[调度] {} 等待超时（{} 分钟）", label, maxMinutes);
    }

    /** 跑到指定时刻（最长时间兜底） */
    private void waitUntil(LocalTime until, int maxMinutes) {
        long deadline = System.currentTimeMillis() + maxMinutes * 60_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!LocalTime.now().isBefore(until)) return;
            sleep(5000);
        }
    }

    /** 距离目标时刻还有多少秒，已过点返回 0 */
    private int secondsUntil(LocalTime target) {
        int now = LocalTime.now().toSecondOfDay();
        int t = target.toSecondOfDay();
        return Math.max(0, t - now);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
