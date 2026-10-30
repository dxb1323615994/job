package com.getjobs.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Boss 复聊引擎。
 *
 * 规则（用户设定）：
 * 1）投递后状态为"送达"（对方未读）→ 复聊发：「不忙的话方便发份简历嘛」
 * 2）状态为"已读"但没回 → 发：「方便的话可以发份简历给您看看吗？」
 * 3）还不回 → 发：「你好在忙吗？」，之后放弃（单个HR最多3句）
 *
 * 聊天页 DOM（实测 2026-10-30）：
 *   li[role=listitem] > .friend-content
 *     .name-text                    HR 姓名
 *     .title-box span               公司 / 职位
 *     .last-msg i.message-status.status-delivery|status-read   最后一条是我们发的及状态
 *     .last-msg-text                最后一条消息文本
 *   输入区：#chat-input[contenteditable] + button.btn-send
 */
@Slf4j
@Service
public class BossFollowUpService {

    private static final String CHAT_URL = "https://www.zhipin.com/web/geek/chat";

    private static final String MSG_DELIVERED = "不忙的话方便发份简历嘛";
    private static final String MSG_READ_FIRST = "方便的话可以发份简历给您看看吗？";
    private static final String MSG_READ_SECOND = "你好在忙吗？";
    private static final int MAX_SENT_PER_HR = 3;

    private final PlaywrightManager playwrightManager;
    private final BossJobService bossJobService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path stateFile = Paths.get(System.getProperty("user.dir"), "followup-state.json");

    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile long runningSince;
    private volatile int sentThisRun;
    private volatile int scannedThisRun;
    private volatile int skippedThisRun;
    private volatile String currentAction = "";
    private volatile boolean dryRunMode;

    /** 当日每个 HR 已发送的复聊条数，key = 姓名|公司 */
    private Map<String, Integer> sentCounts = new HashMap<>();
    private String stateDate = LocalDate.now().toString();

    public BossFollowUpService(PlaywrightManager playwrightManager, BossJobService bossJobService) {
        this.playwrightManager = playwrightManager;
        this.bossJobService = bossJobService;
    }

    // ==================== 对外状态 ====================

    public boolean isRunning() {
        return running;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", running);
        m.put("dryRun", dryRunMode);
        m.put("scanned", scannedThisRun);
        m.put("sent", sentThisRun);
        m.put("skipped", skippedThisRun);
        m.put("currentAction", currentAction);
        m.put("runningSeconds", running ? (System.currentTimeMillis() - runningSince) / 1000 : 0);
        m.put("trackedHr", sentCounts.size());
        m.put("stateDate", stateDate);
        return m;
    }

    public void stop() {
        stopRequested = true;
        log.info("[复聊] 收到停止请求，当前轮结束后退出");
    }

    // ==================== 主流程 ====================

    /**
     * @param dryRun      true = 只扫描和判断，不真的发消息（用于验证选择器/规则）
     * @param maxSends    本轮最多发多少条
     * @param maxMinutes  本轮最长运行分钟数，超时自动停
     */
    public void execute(boolean dryRun, int maxSends, int maxMinutes) {
        if (running) {
            log.warn("[复聊] 已有复聊任务在运行，忽略本次启动");
            return;
        }
        if (bossJobService.isRunning()) {
            log.warn("[复聊] Boss投递正在运行，等投递结束后再复聊");
            return;
        }

        running = true;
        stopRequested = false;
        dryRunMode = dryRun;
        runningSince = System.currentTimeMillis();
        sentThisRun = 0;
        scannedThisRun = 0;
        skippedThisRun = 0;
        currentAction = "初始化";
        long deadline = runningSince + maxMinutes * 60_000L;

        try {
            loadState();
            playwrightManager.ensureReady();
            if (!playwrightManager.isLoggedIn("boss")) {
                log.warn("[复聊] Boss 未登录，跳过");
                return;
            }
            playwrightManager.pauseBossMonitoring();

            Page page = playwrightManager.getBossPageSafe();
            if (page == null) {
                log.warn("[复聊] Boss页面未初始化");
                return;
            }

            currentAction = "打开聊天页";
            page.navigate(CHAT_URL, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000));
            page.waitForTimeout(6000);
            dismissRiskDialog(page);

            log.info("[复聊] 开始扫描（dryRun={}, maxSends={}, maxMinutes={}）", dryRun, maxSends, maxMinutes);

            Set<String> processed = new HashSet<>();
            int idleRounds = 0;

            for (int round = 0; round < 60; round++) {
                if (stopRequested || System.currentTimeMillis() > deadline || sentThisRun >= maxSends) break;

                Locator items = page.locator("li[role='listitem'] .friend-content");
                int count = items.count();
                boolean actedThisRound = false;

                for (int i = 0; i < count; i++) {
                    if (stopRequested || System.currentTimeMillis() > deadline || sentThisRun >= maxSends) break;

                    String key;
                    String status;
                    try {
                        Locator item = page.locator("li[role='listitem'] .friend-content").nth(i);
                        if (item.count() == 0) continue;
                        key = readKey(item);
                        if (key.isEmpty() || processed.contains(key)) continue;
                        status = readStatus(item);
                    } catch (Exception e) {
                        continue;
                    }

                    processed.add(key);
                    scannedThisRun++;

                    if ("OTHER".equals(status)) {
                        // 最后一条不是我们发的（对方已回复），不在本次复聊范围
                        skippedThisRun++;
                        continue;
                    }

                    int stage = sentCounts.getOrDefault(key, 0);
                    String msg = decideMessage(status, stage);
                    if (msg == null) {
                        skippedThisRun++;
                        continue;
                    }

                    currentAction = key + " → " + msg;
                    if (dryRun) {
                        log.info("[复聊][dry] {}（状态={}, 阶段={}) → 将发送：{}", key, status, stage, msg);
                        sentThisRun++;
                        continue;
                    }

                    boolean ok = openAndSend(page, i, key, msg);
                    if (ok) {
                        sentCounts.put(key, stageAfter(status, stage));
                        saveState();
                        sentThisRun++;
                        actedThisRound = true;
                        log.info("[复聊] 已发送 {} → {}（阶段{}）", key, msg, stageAfter(status, stage));
                        page.waitForTimeout(1500);
                    } else {
                        log.warn("[复聊] 发送失败 {}", key);
                        skippedThisRun++;
                    }
                }

                // 虚拟列表：向下滚动加载更多会话
                if (!scrollList(page)) {
                    idleRounds++;
                    if (idleRounds >= 3) {
                        log.info("[复聊] 列表已到底，结束扫描");
                        break;
                    }
                } else {
                    idleRounds = 0;
                }
                page.waitForTimeout(1500);
            }

            log.info("[复聊] 本轮结束：扫描 {}，发送 {}，跳过 {}", scannedThisRun, sentThisRun, skippedThisRun);
        } catch (Exception e) {
            log.error("[复聊] 执行异常: {}", e.getMessage(), e);
        } finally {
            currentAction = "";
            running = false;
            playwrightManager.resumeBossMonitoring();
        }
    }

    /** 依据状态和当前阶段决定下一条该发什么；null 表示不发 */
    private String decideMessage(String status, int stage) {
        if (stage >= MAX_SENT_PER_HR) return null;
        if ("DELIVERED".equals(status)) {
            // 送达=未读：只在完全没跟进过时发第一句，未读会话不追发，避免刷屏
            return stage == 0 ? MSG_DELIVERED : null;
        }
        // READ：已读不回，按阶梯升级（每句最多发一次）
        if (stage == 0) return MSG_READ_FIRST;   // 直接已读 → 发第2句
        if (stage == 1) return MSG_READ_FIRST;   // 之前发的第1句现在被读了 → 发第2句
        if (stage == 2) return MSG_READ_SECOND;  // 第2句也读了 → 发第3句
        return null;
    }

    /** 发送后进入的新阶段：1=已发第1句，2=已发第2句，3=已发第3句 */
    private int stageAfter(String status, int stage) {
        if ("DELIVERED".equals(status)) return 1;
        return stage <= 1 ? 2 : 3;
    }

    private String readKey(Locator item) {
        try {
            String name = item.locator(".name-text").first().innerText().trim();
            String company = "";
            // .title-box 内的 span 依次是：name-box 外壳、name-text、公司、(i.vline)、职位
            // 取第 3 个 span（索引 2）才是公司名
            Locator spans = item.locator(".title-box span");
            int n = spans.count();
            for (int i = 2; i < n; i++) {
                String t = spans.nth(i).innerText().trim();
                if (!t.isEmpty() && !t.equals(name) && !"招聘者".equals(t)) {
                    company = t;
                    break;
                }
            }
            return (name + "|" + company).replace("\n", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** DELIVERED / READ / OTHER */
    private String readStatus(Locator item) {
        try {
            Locator badge = item.locator(".last-msg i.message-status");
            if (badge.count() == 0) return "OTHER";
            String cls = String.valueOf(badge.first().getAttribute("class"));
            if (cls.contains("status-read")) return "READ";
            if (cls.contains("status-delivery")) return "DELIVERED";
            return "OTHER";
        } catch (Exception e) {
            return "OTHER";
        }
    }

    private boolean openAndSend(Page page, int index, String key, String msg) {
        try {
            Locator item = page.locator("li[role='listitem'] .friend-content").nth(index);
            // 发送后列表可能重排，索引会漂移：用姓名+公司校验，不一致则按名字重新查找
            if (item.count() == 0 || !readKey(item).equals(key)) {
                String name = key.split("\\|")[0];
                item = page.locator("li[role='listitem'] .friend-content")
                        .filter(new Locator.FilterOptions().setHasText(name)).first();
            }
            if (item.count() == 0) return false;
            item.click();
            page.waitForTimeout(2500);
            dismissRiskDialog(page);

            Locator input = page.locator("#chat-input");
            if (input.count() == 0 || !input.isVisible()) {
                log.warn("[复聊] 未找到聊天输入框，跳过 {}", key);
                return false;
            }
            input.click();
            page.waitForTimeout(300);
            input.fill(msg);
            page.waitForTimeout(400);

            Locator sendBtn = page.locator("button.btn-send").first();
            String cls = sendBtn.count() > 0 ? String.valueOf(sendBtn.getAttribute("class")) : "";
            if (cls.contains("disabled")) {
                input.press("Enter");
            } else {
                sendBtn.click();
            }
            page.waitForTimeout(1200);

            // 发送后输入框应被清空，作为发送成功的粗略校验
            String left = input.innerText();
            return left == null || left.trim().isEmpty();
        } catch (Exception e) {
            log.warn("[复聊] 发送异常 {}: {}", key, e.getMessage());
            return false;
        }
    }

    /** 关闭"该Boss可能有一定安全风险"的隐私提示弹窗 */
    private void dismissRiskDialog(Page page) {
        try {
            Locator cancel = page.locator(".virtual-cancel");
            if (cancel.count() > 0 && cancel.first().isVisible()) {
                cancel.first().click();
                page.waitForTimeout(500);
                return;
            }
            Locator confirm = page.locator(".virtual-confirm");
            if (confirm.count() > 0 && confirm.first().isVisible()) {
                confirm.first().click();
                page.waitForTimeout(500);
            }
        } catch (Exception ignore) {
            // 弹窗不存在是正常情况
        }
    }

    /** 滚动会话列表；返回 false 表示已到底 */
    private boolean scrollList(Page page) {
        try {
            Object before = page.evaluate("""
                    () => {
                        const el = document.querySelector('.user-list .user-list-content')
                            || document.querySelector('.user-list');
                        if (!el) return -1;
                        const prev = el.scrollTop;
                        el.scrollTop = prev + 600;
                        return prev;
                    }""");
            page.waitForTimeout(800);
            Object after = page.evaluate("""
                    () => {
                        const el = document.querySelector('.user-list .user-list-content')
                            || document.querySelector('.user-list');
                        return el ? el.scrollTop : -1;
                    }""");
            double b = before instanceof Number ? ((Number) before).doubleValue() : -1;
            double a = after instanceof Number ? ((Number) after).doubleValue() : -1;
            return a > b;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 状态持久化 ====================

    private synchronized void loadState() {
        try {
            String today = LocalDate.now().toString();
            if (Files.exists(stateFile)) {
                Map<String, Object> raw = objectMapper.readValue(Files.readString(stateFile),
                        new TypeReference<Map<String, Object>>() {});
                // 阶段计数是"累计"的：一个HR最多3句、说完就放弃，不随日期清零，
                // 否则每天都会重新骚扰同一批人。
                Object counts = raw.get("counts");
                if (counts instanceof Map<?, ?> map) {
                    Map<String, Integer> loaded = new HashMap<>();
                    map.forEach((k, v) -> loaded.put(String.valueOf(k),
                            v instanceof Number ? ((Number) v).intValue() : 0));
                    sentCounts = loaded;
                }
            } else {
                sentCounts = new HashMap<>();
            }
            stateDate = today;
            log.info("[复聊] 载入今日进度：已跟踪 {} 个HR", sentCounts.size());
        } catch (Exception e) {
            log.warn("[复聊] 读取状态失败，重新开始计数: {}", e.getMessage());
            sentCounts = new HashMap<>();
            stateDate = LocalDate.now().toString();
        }
    }

    private synchronized void saveState() {
        try {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("date", LocalDate.now().toString());
            raw.put("counts", sentCounts);
            Files.writeString(stateFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(raw));
        } catch (Exception e) {
            log.warn("[复聊] 保存状态失败: {}", e.getMessage());
        }
    }

    /** 供调度器/接口查询：当前是否应该跳过（投递进行中） */
    public List<String> rules() {
        List<String> r = new ArrayList<>();
        r.add("送达 → " + MSG_DELIVERED);
        r.add("已读(第1次) → " + MSG_READ_FIRST);
        r.add("已读(第2次) → " + MSG_READ_SECOND);
        r.add("每个HR最多 " + MAX_SENT_PER_HR + " 条后放弃");
        return r;
    }
}
