# Fork 说明

本仓库是 [loks666/get_jobs](https://github.com/loks666/get_jobs) 的修改版。

- **原作者**：loks666
- **原项目**：[https://github.com/loks666/get_jobs](https://github.com/loks666/get_jobs)
- **许可证**：PolyForm（详见仓库内 `LICENSE`，与本修改版一致）

---

## 本修改版新增的能力

### 1. 工作日全自动调度（`DeliveryScheduler`）

- 09:30–11:30、14:00–17:00 自动投递与复聊，周六周日不执行
- Boss 优先，剩余时间依次分配给猎聘 / 智联 / 51job
- 关闭方式：启动前设置环境变量 `GETJOBS_AUTO_SCHEDULE=false`

### 2. Boss 日额度熔断（`Boss` / `BossChatNavigator`）

连续 8 个岗位打不开聊天页，即判定当日沟通额度已耗尽，立即结束本轮投递，
避免额度用完后仍逐个岗位空等（每个白等 20 秒）。

### 3. 分轮投递

额度是分批放行的，因此改为「投一批 → 歇 18 分钟 → 再投一批」，
某一轮 0 产出即判定额度真用完，直接收手。

### 4. Boss 自动复聊（`BossFollowUpService`）

对已送达 / 已读未回的招聘者按阶梯追问，最多 3 句后放弃；
进度写入 `followup-state.json` 永久累计，不会因重启而重发同一句。

### 5. 页面自愈（`PlaywrightManager.getBossPageSafe()`）

投递反复开关详情页后，缓存的 Page 引用会失效（`Object doesn't exist: frame@...`）。
新增探活 + 重建逻辑，避免复聊 / 后续投递因此启动失败。

### 6. 多城市支持（`BossService`）

原版前端城市为单选，本版支持通过配置项 `BOSS_EXTRA_CITIES` 追加额外城市。

### 7. 诊断接口（`PlaywrightController`）

`GET /api/playwright/probe-greet` —— 走真实「点卡片 → 点立即沟通」流程，
dump 点击前后的所有可见弹层文本，用于排查投递失败的真实原因。

---

## 使用须知

1. **本仓库不含任何登录凭据**。`browser-data/`、`*.db`、`followup-state.json`、
   `*.log` 均已在 `.gitignore` 中排除，需自行登录各平台。
2. 请遵守各招聘平台的用户协议与 robots 规则，合理控制投递频率。
   因使用本工具导致的账号限制、封禁等后果由使用者自行承担。
3. 投递与复聊的内容由使用者自行负责，请勿用于批量骚扰、虚假投递等用途。
4. 本修改版仅为个人求职场景的效率工具，不提供任何形式的担保。

---

## 同步上游更新

```bash
git remote add upstream https://github.com/loks666/get_jobs.git   # 若尚未添加
git fetch upstream
git merge upstream/main
```
