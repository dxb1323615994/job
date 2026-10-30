package com.getjobs.application.controller;

import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Playwright管理控制器
 * 用于测试和管理Playwright实例
 */
@RestController
@RequestMapping("/api/playwright")
public class PlaywrightController {

    private final PlaywrightManager playwrightManager;

    public PlaywrightController(PlaywrightManager playwrightManager) {
        this.playwrightManager = playwrightManager;
    }

    /**
     * 获取Playwright状态信息
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("initialized", playwrightManager.isInitialized());
        status.put("cdpPort", playwrightManager.getCdpPort());
        status.put("hasBossPage", playwrightManager.getBossPage() != null);
        // 改用持久化上下文后没有独立的 Browser 对象，context 即代表浏览器本身
        status.put("hasBrowser", playwrightManager.getContext() != null);
        status.put("bossLoggedIn", playwrightManager.isLoggedIn("boss"));

        return ResponseEntity.ok(status);
    }

    /**
     * 指纹自检：确认当前浏览器有没有暴露自动化特征。
     * <p>
     * 期望值：
     * navigator.webdriver = false（真实 Chrome 就是 false，undefined 反而是老式 stealth 脚本的痕迹）；
     * userAgentData 必须存在且 platform/brands 与 UA 自洽；cdc_ 残留为空。
     */
    @GetMapping("/fingerprint")
    public ResponseEntity<Map<String, Object>> fingerprint() {
        try {
            // Playwright 调用必须走专用线程，不能直接在 http-nio 线程上调
            Object raw = playwrightManager.callOnPlaywright(() -> playwrightManager.getBossPage().evaluate("""
                    () => {
                        const uaData = navigator.userAgentData;
                        return {
                            'navigator.webdriver': String(navigator.webdriver),
                            'userAgent': navigator.userAgent,
                            'navigator.platform': navigator.platform,
                            'uaData.platform': uaData ? uaData.platform : '(缺失-异常)',
                            'uaData.brands': uaData && uaData.brands
                                ? uaData.brands.map(b => b.brand + ' ' + b.version).join(', ')
                                : '(缺失-异常)',
                            'cdc_残留': Object.keys(window).filter(k => k.startsWith('cdc_')).join(',') || '(无)',
                            'url': location.href
                        };
                    }"""));
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("fingerprint", raw);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 诊断：抓取 Boss 聊天页 DOM，用于开发"复聊"功能时确认选择器。
     * 结果写入工作区 auto/chat-dump.html，同时返回关键统计。
     */
    @GetMapping("/dump-chat")
    public ResponseEntity<Map<String, Object>> dumpChat(
            @RequestParam(value = "click", defaultValue = "1") int click) {
        try {
            Object raw = playwrightManager.callOnPlaywright(() -> {
                Page page = playwrightManager.getBossPage();
                page.navigate("https://www.zhipin.com/web/geek/chat",
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000));
                page.waitForTimeout(6000);

                // 点开第一个会话，才能看到右侧聊天框（输入区选中后才渲染）
                String clickedName = "";
                if (click > 0) {
                    com.microsoft.playwright.Locator items = page.locator("li[role='listitem'] .friend-content");
                    if (items.count() > 0) {
                        clickedName = items.first().locator(".name-text").innerText();
                        items.first().click();
                        page.waitForTimeout(3500);
                    }
                }

                String html = page.content();
                java.nio.file.Path out = java.nio.file.Paths
                        .get(System.getProperty("user.dir")).getParent()
                        .resolve("auto").resolve("chat-dump.html");
                java.nio.file.Files.createDirectories(out.getParent());
                java.nio.file.Files.writeString(out, html);

                Map<String, Object> info = new LinkedHashMap<>();
                info.put("url", page.url());
                info.put("clicked", clickedName);
                info.put("htmlLength", html.length());
                info.put("file", out.toString());
                info.put("deliveryCount", page.locator("i.message-status.status-delivery").count());
                info.put("readCount", page.locator("i.message-status.status-read").count());
                info.put("editableCount", page.locator("[contenteditable='true']").count());
                info.put("itemCount", page.locator("li[role='listitem'] .friend-content").count());
                return info;
            });
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("result", raw);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", String.valueOf(e.getMessage()));
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 诊断：走一遍真实投递的「点岗位卡片 → 点【立即沟通】」，把点击前后出现的所有弹层文本 dump 出来。
     * <p>
     * 用来定位「等待聊天页超时」的真实原因：日沟通上限？验证码？隐私协议？风控提示？
     * 注意：这一步会真的发出一次沟通请求（消耗 1 个配额），只在排查时用。
     */
    @GetMapping("/probe-greet")
    public ResponseEntity<Map<String, Object>> probeGreet(
            @RequestParam(defaultValue = "https://www.zhipin.com/web/geek/jobs?query=Agent&city=101020100") String listUrl,
            @RequestParam(defaultValue = "8") int waitSec) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Object raw = playwrightManager.callOnPlaywright(() -> {
                Map<String, Object> info = new LinkedHashMap<>();
                Page list = null;
                Page detail = null;
                try {
                    list = playwrightManager.getContext().newPage();
                    list.setDefaultTimeout(20_000);
                    list.navigate(listUrl, new Page.NavigateOptions()
                            .setTimeout(30_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    list.waitForTimeout(5_000);
                    Locator cards = list.locator(
                            "//ul[contains(@class,'rec-job-list')]//li[contains(@class,'job-card-box')]");
                    int n = cards.count();
                    info.put("cardCount", n);
                    if (n == 0) {
                        info.put("note", "岗位列表为空，可能未登录或被风控");
                        info.put("listProbe", list.evaluate(PROBE_JS));
                        return info;
                    }
                    String href = null;
                    Object jobProbe = list.evaluate("""
                            () => {
                              const li = document.querySelector("ul.rec-job-list li.job-card-box")
                                      || document.querySelector("li.job-card-box")
                                      || document.querySelector("li[class*=job-card]");
                              if (!li) return {href: null, html: null};
                              const a = li.querySelector('a');
                              return {
                                href: a ? a.getAttribute('href') : null,
                                html: li.outerHTML.slice(0, 900)
                              };
                            }""");
                    info.put("jobCard", jobProbe);
                    if (jobProbe instanceof Map<?, ?> m && m.get("href") != null) {
                        href = String.valueOf(m.get("href"));
                    }
                    info.put("jobHref", href);
                    if (href == null || href.isEmpty()) {
                        info.put("note", "卡片上没有 href");
                        return info;
                    }
                    String detailUrl = href.startsWith("http") ? href : "https://www.zhipin.com" + href;

                    detail = playwrightManager.getContext().newPage();
                    detail.setDefaultTimeout(20_000);
                    detail.navigate(detailUrl, new Page.NavigateOptions()
                            .setTimeout(30_000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                    detail.waitForTimeout(3_500);
                    info.put("beforeClick", detail.evaluate(PROBE_JS));

                    Locator btn = detail.locator("a.btn-startchat, a.op-btn-chat");
                    int bc = btn.count();
                    info.put("btnCount", bc);
                    if (bc == 0) {
                        info.put("note", "详情页没找到【立即沟通】按钮");
                        return info;
                    }
                    info.put("btnText", btn.first().textContent());
                    btn.first().click(new Locator.ClickOptions().setTimeout(8_000));
                    detail.waitForTimeout(waitSec * 1000L);
                    info.put("afterClick", detail.evaluate(PROBE_JS));
                    return info;
                } finally {
                    if (detail != null) {
                        try { detail.close(); } catch (Exception ignore) {
                        }
                    }
                    if (list != null) {
                        try { list.close(); } catch (Exception ignore) {
                        }
                    }
                }
            });
            out.put("success", true);
            out.put("result", raw);
        } catch (Exception e) {
            out.put("success", false);
            out.put("error", String.valueOf(e.getMessage()));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 探针脚本：把页面上所有可见的弹层/提示条文本抓出来，附上是否有聊天输入框。
     */
    private static final String PROBE_JS = """
            () => {
              const vis = el => {
                const r = el.getBoundingClientRect();
                const s = getComputedStyle(el);
                return r.width > 0 && r.height > 0 && s.display !== 'none'
                       && s.visibility !== 'hidden' && s.opacity !== '0';
              };
              const seen = new Set();
              const pops = [];
              document.querySelectorAll(
                '[class*=dialog],[class*=modal],[class*=popup],[class*=toast],[class*=tip],[class*=verify],[class*=captcha],[class*=limit]'
              ).forEach(el => {
                if (!vis(el)) return;
                const t = (el.innerText || '').trim().replace(/\\s+/g, ' ');
                if (t && t.length < 300 && !seen.has(t)) {
                  seen.add(t);
                  pops.push({cls: String(el.className).slice(0, 80), text: t});
                }
              });
              return {
                url: location.href,
                title: document.title,
                chatInput: document.querySelectorAll("div#chat-input, textarea.input-area").length,
                pops: pops.slice(0, 20),
                body: (document.body ? document.body.innerText : '').replace(/\\s+/g, ' ').slice(0, 1200)
              };
            }""";

    /**
     * 诊断：逐个点开前 N 个会话，读出每个会话的消息列表，用于排查"重复发送"。
     * 只读，不发消息。
     */
    @GetMapping("/scan-chats")
    public ResponseEntity<Map<String, Object>> scanChats(
            @RequestParam(value = "n", defaultValue = "10") int n) {
        try {
            Object raw = playwrightManager.callOnPlaywright(() -> {
                Page page = playwrightManager.getBossPage();
                page.navigate("https://www.zhipin.com/web/geek/chat",
                        new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000));
                page.waitForTimeout(6000);

                java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
                String msgJs = """
                        () => Array.from(document.querySelectorAll('.chat-message li.message-item')).map(li => ({
                            me: li.classList.contains('item-myself'),
                            text: (li.querySelector('.text-content') ? li.querySelector('.text-content').innerText : '').trim()
                        }))""";
                for (int i = 0; i < n; i++) {
                    try {
                        com.microsoft.playwright.Locator items = page.locator("li[role='listitem'] .friend-content");
                        if (i >= items.count()) break;
                        String name = items.nth(i).locator(".name-text").innerText().trim();
                        items.nth(i).click();
                        page.waitForTimeout(2200);
                        Object msgs = page.evaluate(msgJs);
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", name);
                        row.put("messages", msgs);
                        out.add(row);
                    } catch (Exception e) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("index", i);
                        row.put("error", String.valueOf(e.getMessage()));
                        out.add(row);
                    }
                }
                return out;
            });
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("result", raw);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", String.valueOf(e.getMessage()));
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 诊断：对比"常驻的 bossPage 标签页"和"新开的受控标签页"加载同一地址的表现。
     * <p>
     * 现象是 bossPage 一直转圈加载不完，但在同一个浏览器里手动新开标签页打开同样的地址却正常。
     * 同浏览器同 profile 同指纹，差别只可能在标签页本身，这里把这个变量隔离出来。
     */
    @GetMapping("/diagnose")
    public ResponseEntity<Map<String, Object>> diagnose(
            @RequestParam(defaultValue = "https://www.zhipin.com/web/geek/jobs?query=Java&city=101020100") String url) {
        Map<String, Object> out = new LinkedHashMap<>();
        String probeJs = """
                () => ({
                  readyState: document.readyState,
                  title: document.title,
                  url: location.href,
                  bodyLength: document.body ? document.body.innerHTML.length : 0,
                  jobCards: document.querySelectorAll('li.job-card-box, li.job-card-wrapper').length,
                  listContainers: document.querySelectorAll('ul.rec-job-list, ul.job-list-box, .job-list-box').length,
                  pending: performance.getEntriesByType('resource')
                             .filter(r => r.responseEnd === 0).map(r => r.name).slice(0, 8)
                })""";
        // 刻意不碰常驻的 bossPage：它一旦卡在"永久加载"，evaluate 会无限期阻塞（没有默认超时），
        // 整个诊断请求也会跟着挂死。这里只用全新的受控标签页做对照。
        String[] targets = {"https://www.zhipin.com", url};
        try {
            playwrightManager.callOnPlaywright(() -> {
                for (String target : targets) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    Page probe = null;
                    long start = System.currentTimeMillis();
                    try {
                        probe = playwrightManager.getContext().newPage();
                        probe.setDefaultTimeout(20_000);
                        probe.navigate(target, new Page.NavigateOptions()
                                .setTimeout(20_000)
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                        result.put("navigateMs", System.currentTimeMillis() - start);
                        probe.waitForTimeout(5_000);
                        result.put("currentUrl", probe.url());
                        result.put("probe", probe.evaluate(probeJs));
                    } catch (Exception e) {
                        result.put("elapsedMs", System.currentTimeMillis() - start);
                        result.put("error", e.getMessage() == null ? e.toString()
                                : e.getMessage().split("\n")[0]);
                    } finally {
                        if (probe != null) {
                            try {
                                probe.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    out.put(target, result);
                }
                return null;
            });
            out.put("success", true);
        } catch (Exception e) {
            out.put("success", false);
            out.put("error", e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 测试Boss导航功能
     */
    @GetMapping("/test-navigate")
    public ResponseEntity<Map<String, String>> testNavigate() {
        try {
            playwrightManager.getBossPage().navigate("https://www.zhipin.com");
            String title = playwrightManager.getBossPage().title();

            Map<String, String> result = new HashMap<>();
            result.put("success", "true");
            result.put("title", title);
            result.put("url", playwrightManager.getBossPage().url());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("success", "false");
            error.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(error);
        }
    }
}
