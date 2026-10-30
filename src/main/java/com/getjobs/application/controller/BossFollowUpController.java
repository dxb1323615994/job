package com.getjobs.application.controller;

import com.getjobs.worker.service.BossFollowUpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Boss 复聊控制器：按会话状态给未回复的 HR 追加消息。
 */
@Slf4j
@RestController
@RequestMapping("/api/boss/followup")
public class BossFollowUpController {

    private final BossFollowUpService service;

    public BossFollowUpController(BossFollowUpService service) {
        this.service = service;
    }

    /**
     * 启动复聊。
     *
     * @param dryRun     true=只扫描判断不发消息（首次验证用）
     * @param maxSends   本轮最多发送条数
     * @param maxMinutes 本轮最长运行分钟数
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start(
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
            @RequestParam(value = "maxSends", defaultValue = "200") int maxSends,
            @RequestParam(value = "maxMinutes", defaultValue = "150") int maxMinutes) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (service.isRunning()) {
            resp.put("success", false);
            resp.put("message", "复聊任务已在运行中");
            return ResponseEntity.badRequest().body(resp);
        }
        CompletableFuture.runAsync(() -> service.execute(dryRun, maxSends, maxMinutes));
        resp.put("success", true);
        resp.put("message", dryRun ? "复聊扫描（演练模式）已启动" : "复聊任务已启动");
        resp.put("dryRun", dryRun);
        resp.put("maxSends", maxSends);
        resp.put("maxMinutes", maxMinutes);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (!service.isRunning()) {
            resp.put("success", false);
            resp.put("message", "没有正在运行的复聊任务");
            return ResponseEntity.badRequest().body(resp);
        }
        service.stop();
        resp.put("success", true);
        resp.put("message", "已发送停止请求");
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> resp = new LinkedHashMap<>(service.status());
        resp.put("success", true);
        resp.put("rules", service.rules());
        return ResponseEntity.ok(resp);
    }
}
