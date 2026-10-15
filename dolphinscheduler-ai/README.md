<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# SeaTunnel AI 配置助手

适用版本：DolphinScheduler 3.4.2（定制分支 `dev-3.4.2-ai-seatunnel`）+ SeaTunnel 2.3.13 + AWS Bedrock。

## 一、功能说明

在 SeaTunnel 任务节点的设置弹窗里，用自然语言描述数据接入需求，AI 生成可直接运行的 SeaTunnel 配置（HOCON），校验通过后以对比视图展示，**由用户确认后才写入编辑器**。

| 功能 | 说明 |
|---|---|
| 三栏布局 | 左：节点配置；中：AI 对话；右：脚本编辑器 / 修改对比。未启用 AI 时保持原来的两栏布局 |
| 生成与多轮修改 | 第一次描述需求生成完整配置，之后可以继续说"改成流式""并行度改成 4"等，AI 在当前脚本基础上修改 |
| 基于真实参数生成 | AI 写配置前会查询所用 connector 的参数（来自 SeaTunnel 2.3.13 导出的元数据），不靠模型记忆，减少编造参数 |
| 调用接口获取字段 | 配置 Http source 时，AI 会用你给出的接口地址实际调用一次，根据返回的 JSON / text 推断字段和类型，生成 `schema` 和 `content_field`，不靠猜（见 2.1） |
| 两层校验 + 自动修复 | 本地校验（语法、必填参数、条件参数、未知参数、连线等）+ 引擎校验（调用服务器上安装的 SeaTunnel 的校验 API）；不通过时 AI 带着错误自动修复，最多 3 轮 |
| 对比确认 | 右侧显示"原脚本 vs 建议脚本"的 diff 和校验结果；可直接在建议一侧修改后再接受；支持合并 / 并排视图 |
| 回撤 / 重做 | 每次接受都是一个检查点，可以"回撤到此前"恢复，也可以"重做" |
| 聊天记录保存 | 按任务保存到数据库，该任务的所有编辑者共享；重新打开节点能看到之前的对话并接着聊；可一键清空 |
| 凭据保护 | 发给 AI 之前把密码、密钥、token 替换成占位符，只在最终脚本里还原；对话和数据库里显示为 `******` |
| 权限与限流 | 需要项目的工作流编辑权限；每个用户每分钟最多 10 次请求（可配置） |

当前支持的 connector（内置元数据）：

- Source：Jdbc、Kafka、S3File、Http、Clickhouse、FakeSource
- Sink：Jdbc、Kafka、S3File、Http、Clickhouse、ClickhouseFile、Console
- Transform：SeaTunnel 内置的 20 个（Sql、FieldMapper、Filter、Replace、Split、JsonPath 等）

需要更多 connector 时，见"五、更新 connector 元数据"。

## 二、使用说明

1. 打开工作流定义，新建或编辑一个 **SeaTunnel** 任务节点，弹窗显示为三栏（工作流实例里的只读查看不显示 AI）。
2. 在中间对话框描述需求，例如：
   - "把 MySQL（10.0.0.5:3306，库 shop，用户 etl，密码是 xxx）的 orders 表全量同步到 ClickHouse 的 dw.orders"
   - "实时消费 Kafka 的 events 主题（JSON 格式），写入 ClickHouse"

   按 Enter 发送，Shift+Enter 换行；生成过程中可点"停止"。缺少必要信息时 AI 会先反问。
3. 回复下方会显示进度：查询参数 → 本地校验 → 引擎校验 →（必要时）自动修复。
4. 生成完成后右侧变为对比视图，顶部显示校验结果（鼠标悬停看具体问题）：
   - **接受**：写入编辑器（可先在右侧手动调整）；
   - **拒绝**：丢弃本次修改。

   有待确认的修改时，不能发送新消息。
5. 继续对话修改配置；每次接受的修改可以在对应消息上"回撤到此前"或"重做"。
6. 确认无误后，像平常一样点节点弹窗的"确定"并保存工作流。**AI 只修改编辑器内容，不会自动保存任务，也不会运行作业。**

关于凭据：
- 你在对话里给出的密码会写进最终脚本，但不会发送给 AI，对话里显示为 `******`。
- 你没给的密码，AI 会写成 `${mysql_password}` 这样的参数，并提示你在任务的"自定义参数"里定义（或直接在编辑器里填写）。

### 2.1 Http 接口字段推断

在需求里写出**完整的接口地址**，需要认证时一并给出，例如：

> 把 https://api.example.com/v1/orders 的数据同步到 MySQL 的 dw.orders，请求头 Authorization 用 Bearer token，token 是 xxx

AI 会先调用该接口（进度里显示“调用接口获取字段 GET https://api.example.com/v1/orders”），再根据返回结果写 `schema.fields`、`content_field`（记录在嵌套数组里时）或 `json_field`。

- **只调用你给出的地址**：只有在对话或当前脚本里出现过的 `host:端口` 才会被调用，AI 自己编造的地址、接口返回里出现的地址都不会调用。重定向只允许同一主机（如 http 跳到 https）。
- **禁止的地址**：本机回环地址（127.0.0.1、localhost）、链路本地地址（包括 `169.254.169.254` 云元数据服务）、组播地址，以及 `ai.http-sample.denied-hosts` 中配置的地址。内网私有地址允许调用。
- **发给 AI 的内容**：只有字段路径、类型、是否可为空、每个字段一个示例值（最长 50 字符；password、token、secret、key 等字段的值显示为 `******`），不发原始响应。
- **查看响应原文**：每次调用后，回复下方会出现“响应原文：GET … · HTTP 200 · 473 B”，点开可以看到接口返回的原文（JSON 自动格式化，最多显示前 20000 个字符）。原文只推送给当前浏览器，不发给 AI，也不保存到聊天记录。
- **凭据**：token 和密码一样会先替换成占位符，只在真正发请求时还原，AI 看不到真实值。参数里有未定义的 `${xxx}` 时不会发请求，AI 会请你给出具体值。
- **限制**：仅支持 GET / POST；超时 10 秒；响应只读取前 1MB；每次对话请求最多调用 3 次。
- **注意**：接口是从 **api-server** 调用的，作业实际在 **worker** 上运行，worker 也必须能访问该地址。

关于聊天记录：
- 重新打开节点时，顶部显示"以下为历史对话 · 最后更新 时间 by 用户"，下面是"本次对话"。用户消息显示发送人和时间。
- 历史消息只显示当时的结果（已接受 / 已拒绝 / 已回撤 / 已过期），不能回撤或重做。关闭弹窗时还没确认的修改会显示"已过期（未确认）"。
- 对话面板右上角"清空聊天记录"会删除该任务的全部对话（所有编辑者都看不到），需要二次确认，不影响编辑器里的脚本。

## 三、部署说明

### 3.1 前置条件

| 项 | 要求 |
|---|---|
| DolphinScheduler | 3.4.2，已部署本定制分支之前的版本（OIDC、SeaTunnel 布局等改动） |
| Java | api-server 运行在 **JDK 17**（需要 JDK 而不是 JRE，引擎校验要在首次使用时编译校验程序；如果是 JRE，引擎校验会被跳过，只做本地校验） |
| SeaTunnel | api-server 所在机器安装 SeaTunnel 2.3.13（用于引擎校验，未安装也能用，只是跳过引擎校验） |
| AWS Bedrock | us-east-1 已开通 Claude Sonnet 5.5 和 Claude Haiku 5.5；api-server 有调用权限（见 3.3） |
| 数据库 | 需要执行一条建表 SQL（MySQL 或 PostgreSQL） |

### 3.2 部署步骤（只改 api-server，master / worker / alert 不用动）

部署包 `ds-ai-full-deploy-3.4.2.tar.gz`（包含全部 AI 功能：配置生成、聊天记录、Http 接口字段推断；脚本和 SQL 也在仓库 `dolphinscheduler-ai/tools/deploy/` 下）：

| 内容 | 说明 |
|---|---|
| `api-server-libs/`（39 个 jar） | 新增 22 个第三方 jar（LangChain4j、Bedrock SDK、AWS CRT 等）+ 升级 14 个 AWS SDK jar（2.17.282 → 2.42.4）+ `dolphinscheduler-ai / api / dao-3.4.2.jar` |
| `remove-from-api-server-libs.txt` | 要删除的 14 个旧版 AWS SDK jar |
| `ui/` | 新前端 |
| `ai_conversation_mysql.sql` / `ai_conversation_postgresql.sql` | 聊天记录表 `t_ds_ai_conversation` |
| `install-api-server.sh` | 安装脚本：备份 libs 和 ui → 删除旧 jar → 拷入新 jar → 替换 ui |
| `application-ai.yaml` | 配置片段 |
| `seatunnel-metadata-exporter/` | connector 元数据导出工具（见“五”） |

**部署步骤：**

```bash
# 1. 建表（二选一，表已存在时会跳过）
mysql -h <host> -u <user> -p <database> < ds-ai-full-deploy/ai_conversation_mysql.sql
psql -h <host> -U <user> -d <database> -f ds-ai-full-deploy/ai_conversation_postgresql.sql

# 2. 停止 api-server
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh stop api-server

# 3. 安装 jar 和 ui
tar -xzf ds-ai-full-deploy-3.4.2.tar.gz && cd ds-ai-full-deploy
./install-api-server.sh /opt/dolphinscheduler-3.4.2

# 4. 配置：把 application-ai.yaml 的内容追加到 api-server/conf/application.yaml 顶层（与 security: 同级），
#    修改 ai.seatunnel.home 为实际的 SeaTunnel 安装目录

# 5. 给 api-server 所在 EC2 的实例角色加 Bedrock 权限（见 3.4）

# 6. 启动 api-server
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh start api-server
```

**已部署过 AI 助手主体（未含聊天记录和 Http 接口字段推断）时的升级：** 使用 `ds-ai-update-deploy-3.4.2.tar.gz`（只含 ai / api / dao 三个 jar、ui、建表 SQL 和 `install-ai-update.sh`，第三方 jar 不变）：

```bash
tar -xzf ds-ai-update-deploy-3.4.2.tar.gz && cd ds-ai-update-deploy
mysql -h <host> -u <user> -p <database> < ai_conversation_mysql.sql   # 或 psql ... -f ai_conversation_postgresql.sql
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh stop api-server
./install-ai-update.sh /opt/dolphinscheduler-3.4.2                    # 备份到 api-server/backup-<时间>
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh start api-server
```

注意：
- **旧版 AWS SDK jar 必须删除**（脚本已处理）。jar 文件名带版本号，同一个包的 2.17.282 和 2.42.4 同时在 classpath 上会冲突。`datasync-2.17.282.jar` 和 `netty-nio-client-2.17.282.jar` 是正常保留的。
- 前端读取的是 **`api-server/ui`**，不是 DolphinScheduler 根目录下的 `ui`。
- 替换后用新开的无痕窗口验证，避免浏览器缓存旧前端（入口文件应为 `assets/index.209cb49d.js`）。

**回滚：** 停止 api-server，用脚本输出的备份目录（`api-server/libs.bak-*`、`api-server/ui.bak-*`）恢复，再启动；或把 `ai.enabled` 改为 `false` 后重启（页面恢复原样，AI 相关组件都不会创建）。

### 3.3 配置项（`api-server/conf/application.yaml`）

```yaml
ai:
  enabled: true                      # 总开关，false 时页面与原来完全一致
  rate-limit-per-user-per-minute: 10 # 每个用户每分钟最多请求数，0 为不限制
  llm:
    region: us-east-1
    model: us.anthropic.claude-sonnet-5-5      # 生成配置用的主模型
    fast-model: us.anthropic.claude-haiku-5-5  # 压缩长对话用的快速模型
    access-key-id: ""                # 留空则用 EC2 实例角色或环境变量（推荐）
    secret-access-key: ""
    timeout: 120s
    max-tokens: 8192
  seatunnel:
    version: 2.3.13
    home: /opt/seatunnel-2.3.13      # 配置后启用引擎校验，留空则只做本地校验
    metadata-path: ""                # 自定义 connector 元数据文件，留空用内置的
    check-timeout: 60s
    max-fix-attempts: 3              # 校验不通过时自动修复的最大轮数
  http-sample:                       # 调用 Http 接口获取字段（见 2.1），以下均为默认值，可不配置
    enabled: true
    timeout: 10s
    max-response-bytes: 1048576      # 只读取响应的前 1MB
    max-calls-per-request: 3         # 每次对话请求最多调用接口的次数
    denied-hosts: []                 # 额外禁止调用的地址：域名（含子域名）、IP 或 CIDR，例如 [10.0.1.0/24, internal.corp]
```

### 3.4 AWS 权限

默认使用 api-server 所在 EC2 的**实例角色**调用 Bedrock。需要给该角色（例如 `<实例角色名>`）增加策略，账号 ID 换成实际值：

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"],
      "Resource": [
        "arn:aws:bedrock:us-east-1:<账号ID>:inference-profile/us.anthropic.claude-*",
        "arn:aws:bedrock:*::foundation-model/anthropic.claude-*"
      ]
    }
  ]
}
```

- `us.` 开头的是跨区域推理配置，请求可能落到 us-east-1 / us-east-2 / us-west-2，所以 foundation-model 的区域写 `*`。
- 同时确认该账号在 Bedrock 控制台的 Model access 里已开通对应 Claude 模型。
- 也可以改用 Bedrock API Key：在 api-server 启动环境里设置 `AWS_BEARER_TOKEN_BEDROCK`（例如写在 `api-server/conf/dolphinscheduler_env.sh`），重启生效。

在 api-server 机器上验证权限：

```bash
aws bedrock-runtime converse-stream --region us-east-1 \
  --model-id us.anthropic.claude-sonnet-5-5 \
  --messages '[{"role":"user","content":[{"text":"Reply OK"}]}]'
```

### 3.5 部署后检查

1. 打开 SeaTunnel 节点，显示三栏。
2. 点击示例需求发送，看到进度和右侧对比视图，校验结果为"通过"，点"接受"写入编辑器。
3. 再发一轮修改，试"回撤到此前""重做"。
4. 关闭弹窗再打开，能看到历史对话；换一个有编辑权限的账号打开同一任务，也能看到。
5. 保存节点并运行，作业正常执行。

## 四、实现概要

```
浏览器                               api-server                       dolphinscheduler-ai（Java 17）
三栏界面 ── POST /ai/seatunnel/chat ──▶ 权限 / 限流 / 线程池 ──────▶ SeatunnelConfigAgent
         ◀── SSE: status/delta/          SseEmitter                    ├ LangChain4j + Bedrock（流式、工具调用）
             proposal/done/error                                       ├ 工具：list_connectors / get_connector_info / validate_config / fetch_http_sample
聊天记录 ── GET/PUT/DELETE /ai/conversations ─▶ t_ds_ai_conversation   ├ HoconValidator（本地校验）
                                                                       ├ SeatunnelEngineChecker（独立 JVM 调 SeaTunnel 校验 API）
                                                                       ├ ConnectorMetadataRepository（2.3.13 元数据）
                                                                       └ CredentialRedactor（凭据脱敏）
```

| 接口 | 说明 |
|---|---|
| `GET /ai/status` | AI 是否启用、引擎校验是否可用 |
| `POST /ai/seatunnel/chat` | 对话，返回 SSE 事件流 |
| `GET /ai/conversations?projectCode=&taskCode=` | 读取任务的聊天记录 |
| `PUT /ai/conversations` | 保存聊天记录（整份覆盖） |
| `DELETE /ai/conversations?projectCode=&taskCode=` | 清空聊天记录 |

关键设计：
- **Agent 框架**：LangChain4j 1.22（只用模型调用、工具调用、流式、监听器），流程编排自研，借鉴 SeaTunnel dev 分支 `seatunnel-cli` 的思路。
- **依赖隔离**：AI 模块单独用 Java 17 编译；Bedrock 流式调用使用 AWS CRT HTTP 客户端，避开与 DolphinScheduler 的 Netty 冲突；沿用 DolphinScheduler 的 Jackson 2.13.4 和 slf4j 1.7。
- **引擎校验**：SeaTunnel 2.3.13 的 `seatunnel.sh --check` 实际不做任何校验，因此自写 `SeatunnelConfigChecker`，在独立进程中调用 SeaTunnel 的配置解析、插件发现和 `ConfigValidator`，SeaTunnel 的类不进入 api-server。
- **服务端无状态**：对话上下文由浏览器每次回传，api-server 可多实例部署；聊天记录另存数据库。

代码位置：
- 后端：`dolphinscheduler-ai/`、`dolphinscheduler-api/.../controller/AiAssistantController.java`、`.../service/impl/AiAssistantServiceImpl.java`、`dolphinscheduler-dao/.../AiConversation*`
- 前端：`dolphinscheduler-ui/src/components/ai-assistant/`、`.../task/components/node/seatunnel-ai/`、`detail.tsx`、`detail-modal.tsx`
- 提示词与元数据：`dolphinscheduler-ai/src/main/resources/seatunnel/`
- 工具与部署脚本：`dolphinscheduler-ai/tools/`

## 五、更新 connector 元数据

AI 只认元数据里的 connector。服务器上安装了更多 connector（例如 Doris、StarRocks、MySQL-CDC）后：

```bash
# 在装有 SeaTunnel 的机器上执行，输出该安装目录下全部 connector 的参数规则
dolphinscheduler-ai/tools/seatunnel-metadata-exporter/export.sh /opt/seatunnel-2.3.13 /opt/dolphinscheduler-3.4.2/api-server/conf/seatunnel-connector-metadata.json
```

然后配置 `ai.seatunnel.metadata-path: /opt/dolphinscheduler-3.4.2/api-server/conf/seatunnel-connector-metadata.json` 并重启 api-server。升级 SeaTunnel 版本后也要重新导出，并修改 `ai.seatunnel.version`。

## 六、常见问题

| 现象 | 原因与处理 |
|---|---|
| 发送消息报 `AuthenticationException ... not authorized to perform: bedrock:InvokeModelWithResponseStream` | api-server 使用的身份（日志里的 `assumed-role/...`）没有 Bedrock 权限，按 3.4 配置 |
| 校验结果显示"引擎校验：未执行" | 未配置 `ai.seatunnel.home`、路径下没有 `starter/seatunnel-starter.jar`，或 api-server 运行在 JRE 上；鼠标悬停可看原因 |
| AI 说某个 connector 未安装 | 该 connector 不在元数据里，按"五"重新导出 |
| 页面没有三栏 | `ai.enabled` 未开启；或是工作流实例的只读查看；或浏览器缓存了旧前端（用无痕窗口验证） |
| AI 说接口地址“不是用户给出的” | 对话里没有写出完整的 `http(s)://host:端口`，或 AI 拼出的地址和你写的不一致；请在对话中给出完整 URL |
| 调用接口报 `unknown host` / 超时 | api-server 无法访问该地址；接口地址需要 api-server 和 worker 都能访问 |
| 提示"AI 助手请求过于频繁" | 超过每分钟请求上限，调整 `rate-limit-per-user-per-minute` |
| 启动报 AWS SDK 相关 `NoSuchMethodError` | `api-server/libs` 里残留了 2.17.282 版本的 AWS SDK 核心 jar，按 `remove-from-api-server-libs.txt` 删除 |

## 七、已知限制

- 两个人同时编辑同一任务时，聊天记录以最后保存的为准。
- 复制或导入工作流后任务 code 会变，新任务看不到原来的聊天记录。
- 历史聊天记录不保存当时的建议脚本，重新打开后不能回撤或重做之前的轮次。
- 删除任务不会自动删除对应的聊天记录（数据量很小，可按需清理 `t_ds_ai_conversation`）。
- 本分支的 AI 模块需要 JDK 17 运行，不再支持 JDK 8。
