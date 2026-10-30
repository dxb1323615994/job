# get_jobs · 自动投递增强版

基于 [loks666/get_jobs](https://github.com/loks666/get_jobs) 改造的个人求职自动化工具：
多平台批量投递 + 工作日定时调度 + 自动复聊，四个平台共用一套配置，投递记录落在本地 SQLite。

> 本仓库是 fork 版本，在上游基础上补充了自动调度、额度熔断、自动复聊等能力。
> 原始项目与本修改版均遵循 **PolyForm Noncommercial 1.0.0**，禁止商业使用。

---

## 在上游基础上改了什么

| 能力 | 说明 |
|---|---|
| 工作日自动调度 | 09:30–11:30、14:00–17:00 自动投递与复聊，周六日不执行；`GETJOBS_AUTO_SCHEDULE=false` 可关 |
| Boss 日额度熔断 | 连续 8 个岗位打不开聊天页即判定额度耗尽，立即结束本轮，避免逐个白等 |
| 分轮投递 | 投一批 → 歇 18 分钟 → 再投一批；某轮 0 产出即判定额度真用完，直接收手 |
| Boss 自动复聊 | 按「已送达 / 已读未回」阶梯追问，最多 3 句后放弃；进度永久累计，重启不重发 |
| 页面自愈 | Page 引用失效（`Object doesn't exist: frame@...`）时自动重建 |
| 多城市 | 前端城市为单选，可通过配置项 `BOSS_EXTRA_CITIES` 追加额外城市 |
| 诊断接口 | `GET /api/playwright/probe-greet` 走真实点击流程，dump 弹层文本，用于排查投递失败原因 |

---

## 环境要求

- JDK 21
- Gradle 9.x（仓库自带 wrapper，无需单独装）
- Node.js（仅编译前端时需要；也可直接用已编译的 `dist`）
- 首次运行需联网下载 Playwright 驱动

## 快速开始

**1. 拉取代码**

```bash
git clone <本仓库地址>
cd get_jobs
```

**2. 准备数据库**

项目不会自动建表，**没有数据库文件无法启动**：

- 从上游 [release 页面](https://github.com/loks666/get_jobs/releases/latest) 下载 `getjobs.db.template`
- 重命名为 `getjobs.db`
- 放到 `db/getjobs.db`

这个空库只含表结构和下拉字典。首次启动时若四个平台配置为空，程序会从
`src/main/resources/defaults/page-config.json` 加载默认配置，**请按自己的真实情况修改后再投递**。

**3. 启动后端**

```bash
./gradlew bootRun
```

默认监听 9527。如果环境里已存在 `PORT` 变量，需要显式指定：

```bash
SERVER_PORT=9527 ./gradlew bootRun
```

**4. 打开管理页**

浏览器访问 `http://127.0.0.1:6866`，配置关键词、城市、薪资范围、打招呼语，保存后开始投递。

**5. 登录平台**

首次使用需扫码登录 Boss / 猎聘 / 智联 / 51job。登录态持久化在 `browser-data/`，
正常情况下每周扫一次即可。

---

## 自动调度时间表

| 时间 | 动作 |
|---|---|
| 09:30 | Boss 开投，目标 100 个（投满 / 熔断 / 11:20 到点，三者任一即停） |
| 停后 | 猎聘 → 智联 → 51job 轮流 |
| 11:30 | 全部停止 |
| 14:00 | Boss 再投，目标 50 个 |
| 14:45 或提前 | 猎聘 → 智联 → 51job 轮流 |
| 15:30 | Boss 复聊 |
| 17:00 | 收工 |

四个平台共用同一个浏览器实例，只能串行执行，不能并行。

---

## 平台说明

- **Boss 直聘**：日主动沟通上限约 150 次（按账号动态调整）。打招呼语请直接在
  Boss App「我的 → 设置 → 打招呼语」里设置，程序不会额外补发第二条。
- **猎聘**：需在 App 里设置打招呼语；仅支持微信扫码登录。
- **智联**：必须指定默认投递简历（在线 / 附件），否则投递失败。
- **51job**：限制较多，成功率一般，可作为补充。

---

## 数据与安全

- 仓库**不含**任何登录凭据、投递记录、API 密钥。
- `browser-data/`、`db/*.db`、`followup-state.json`、`*.log` 均已在 `.gitignore` 中排除。
- 所有数据只存在本机，不上传任何服务器。
- 自己使用时不要把数据库、Cookie、简历文件提交进版本库。

---

## 免责声明

本项目仅用于个人求职效率提升。请遵守各招聘平台的用户协议与访问频率限制，
因使用本工具导致的账号限制、封禁等后果由使用者自行承担。
禁止用于批量骚扰、虚假投递或任何商业用途。

---

## 来源与许可

- **上游项目**：[loks666/get_jobs](https://github.com/loks666/get_jobs)（原作者 loks666）
- **许可证**：PolyForm Noncommercial 1.0.0，详见 [LICENSE](LICENSE)。
  允许免费的非商业使用，**禁止任何形式的商业使用**，商业授权请联系原作者。
- 同步上游更新：

```bash
git remote add upstream https://github.com/loks666/get_jobs.git
git fetch upstream
git merge upstream/main
```
