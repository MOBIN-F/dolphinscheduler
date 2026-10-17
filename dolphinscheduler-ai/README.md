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
| 三栏布局 | 左：节点配置；中：AI 对话；右：脚本编辑器 / 修改对比。只读查看（如工作流实例的“当前节点设置”）也是三栏，只显示该任务的历史对话，不能发消息、不能清空。未启用 AI 时保持原来的两栏布局 |
| 生成与多轮修改 | 第一次描述需求生成完整配置，之后可以继续说"改成流式""并行度改成 4"等，AI 在当前脚本基础上修改 |
| 基于真实参数生成 | AI 写配置前会查询所用 connector 的参数（来自 SeaTunnel 2.3.13 导出的元数据），不靠模型记忆，减少编造参数 |
| 调用接口获取字段 | 配置 Http source 时，AI 会用你给出的接口地址实际调用一次，根据返回的 JSON / text 推断字段和类型，生成 `schema` 和 `content_field`，不靠猜（见 2.1） |
| 两层校验 + 自动修复 | 本地校验（语法、必填参数、条件参数、未知参数、连线等）+ 引擎校验（调用服务器上安装的 SeaTunnel 的校验 API）；不通过时 AI 带着错误自动修复，最多 3 轮 |
| 对比确认 | 右侧显示"原脚本 vs 建议脚本"的 diff 和校验结果；可直接在建议一侧修改后再接受；支持合并 / 并排视图 |
| 回撤 / 重做 | 每次接受都是一个检查点，可以"回撤到此前"恢复，也可以"重做" |
| 聊天记录保存 | 按任务保存到数据库，该任务的所有编辑者共享；重新打开节点能看到之前的对话并接着聊；可一键清空 |
| 预置连接（SeaTunnel 配置） | 管理员在安全中心维护 Kafka / MySQL / PostgreSQL / ClickHouse / HTTP / S3 连接并按用户授权；AI 助手顶部选择来源和目标后，自动读取 Kafka 消息样例或表结构，AI 不再追问连接信息，并按字段对齐生成配置；脚本里的密码是 `${stconn.标识.字段}` 引用，运行时注入（见 2.2） |
| 凭据保护 | 发给 AI 之前把密码、密钥、token 替换成占位符，只在最终脚本里还原；对话和数据库里显示为 `******` |
| 权限与限流 | 需要项目的工作流编辑权限；每个用户每分钟最多 10 次请求（可配置） |

当前支持的 connector（内置元数据）：

- Source：Jdbc、Kafka、S3File、Http、Clickhouse、FakeSource
- Sink：Jdbc、Kafka、S3File、Http、Clickhouse、ClickhouseFile、Console
- Transform：SeaTunnel 内置的 20 个（Sql、FieldMapper、Filter、Replace、Split、JsonPath 等）

需要更多 connector 时，见"五、更新 connector 元数据"。

## 二、使用说明

1. 打开工作流定义，新建或编辑一个 **SeaTunnel** 任务节点，弹窗显示为三栏。在工作流实例里查看节点时同样是三栏，但只能查看历史对话。
2. 在中间对话框描述需求，例如：
   - "把 MySQL（10.0.0.5:3306，库 shop，用户 etl，密码是 xxx）的 orders 表全量同步到 ClickHouse 的 dw.orders"
   - "实时消费 Kafka 的 events 主题（JSON 格式），写入 ClickHouse"

   按 Enter 发送，Shift+Enter 换行；生成过程中可点"停止"。缺少必要信息时 AI 会先反问。
3. 回复上方会显示完整的调用过程，每一步都带结果和耗时，例如：“使用已选来源 kafka_dev / orders — 字段 7 个（来自选择栏）”“读取字段 … — 字段 7 个，其中敏感 2 个”“本地校验 — 通过，1 条警告”。失败的步骤显示 ✗ 和原因，警告显示 ⚠，点击步骤可以查看细节（字段列表、校验问题等）。生成结束后自动折叠成一行“N 个步骤 · 用时 …”，点击可以展开；重新打开节点后，历史消息中仍能看到当时的调用过程。
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

### 2.2 预置连接（SeaTunnel 配置）

**管理员：维护连接**

1. 安全中心 → **SeaTunnel配置** → 创建连接，选择类型并填写：

   | 类型 | 主要字段 | 可作为 |
   |---|---|---|
   | Kafka | bootstrap servers；**消费者组前缀**（集群按前缀授权消费者组时填写）；安全协议 PLAINTEXT / SASL_PLAINTEXT / SASL_SSL / SSL；SASL 机制、用户名、密码；SSL 的 CA 证书、客户端证书和私钥（PEM 文本，直接粘贴） | 来源、目标 |
   | MySQL / PostgreSQL | 主机、端口、数据库、用户名、密码、JDBC 参数 | 来源、目标 |
   | ClickHouse | 主机、HTTP 端口、默认数据库、用户名、密码 | 来源、目标 |
   | HTTP | 基础地址、认证方式（无 / Bearer / Basic）、公共请求头、常用接口路径 | 来源 |
   | S3 | 区域、Endpoint、Bucket、认证方式（实例角色 / AK·SK） | 目标 |

   - **连接标识**（如 `kafka_prod`）会出现在脚本里，创建后不能修改。
   - 密码、证书等敏感字段只能写入、不能读出：编辑时显示“已设置”，留空表示不修改。数据库中用 AES 加密保存（密钥见 DEPLOY.md 第四节第 3 步）。
   - 点“测试连接”，在保存前验证。
   - 被任务引用的连接不能删除。
   - **消费者组前缀**：集群只允许特定前缀的消费者组时填写，例如 `team-a`。填写后：
     - 作业的 `consumer.group` 固定为 `<前缀>-ds-<任务code>`（例如 `team-a-ds-123456789`）。按任务区分且不随任务改名而变化，所以改名后不会从头重新消费。不经过任务调用时，由 AI 在前缀后补一个后缀；
     - **不允许直接用前缀作为消费者组**，校验不通过会自动修复；
     - AI 读取样例时使用 `<前缀>-dolphinscheduler-ai-sample`。读取样例不提交 offset，不影响任何消费进度；
     - 需要在 Kafka 上给该账号授予这个前缀的消费者组 Read 权限（前缀匹配）。
2. 用户管理 → 授权 → **SeaTunnel连接**，把连接授权给需要的用户。管理员不需要授权，能使用全部连接。

**用户：在 AI 助手中选择**

1. 打开 SeaTunnel 节点，对话框顶部有“数据来源 / 写入目标”，点开选择：
   - 来源：选连接，再选 topic，或者选库 / schema 后再选表，可以多选；HTTP 选接口路径。
   - 目标：选连接，再选已有的表，或者勾选“**自动新建表**”并填新表名；S3 选目录和文件格式。
2. 选好后，会自动读取字段：Kafka 消费最近 5 条消息推断字段，数据库读取表结构，HTTP 调用一次接口。读完显示“字段 N 个”，点开可以查看字段和数据样例。**数据样例只显示在当前页面，不发给 AI，也不保存。**
3. 直接说需求，例如“把选中的 topic 同步到选中的表”。AI 不会再问地址和账号：
   - 来源和目标的字段按名称和类型对齐，名称不同时用 `Sql` / `FieldMapper` 转换，类型不同时用 `CAST`，没有对应的字段会在说明里列出；
   - 自动新建表时，以来源的结构为准，生成 `schema_save_mode = "CREATE_SCHEMA_WHEN_NOT_EXIST"`；
   - 脚本里的密码为 `"${stconn.kafka_prod.sasl_password}"` 这样的引用。编辑器和任务定义中都没有明文，任务运行时由 master 替换成真实值。worker 的服务日志会把它显示为 `******`。
4. 选择结果和聊天记录一起按任务保存。手动修改选择后，对话中会插入一条“来源已改为 …”的记录。
5. 不选择连接也照常可用，像以前一样在对话中描述连接信息即可。

**AI 主动读取字段（尽量不让用户提供样例）**

- **下拉框可以直接输入**：topic、库 / schema、表都能直接输入名称后回车。账号没有 List 权限、列表为空时也能用，读取样例只需要该 topic / 表本身的读权限。
- **只选了一部分，或者没用选择栏**：在对话里说“把 kafka_dev 的 orders 同步到 pg_dw 的 public.orders_sink”即可。AI 会自己查找你有权限的连接，消费样例、读取表结构，并使用 `${stconn...}` 引用。
- **对话里直接给出连接信息**：例如 Kafka 地址和账号，或数据库的主机、库、用户、密码。AI 用这些信息临时连接取样：
  - 密码在发给 AI 前就被替换成占位符，只在服务端连接时还原；
  - 只连接你在对话里写出的主机，本机、云元数据地址一律禁止；
  - 每次对话最多取样 6 次。
- **失败时说明原因**：只有全部尝试都失败，AI 才会请你补充，并说明具体原因，例如没有读权限、topic 里没有消息、安全协议应为 SASL_SSL、连接超时。

**样例中的个人信息**

- 手机号、邮箱、身份证号、银行卡号、IP、姓名、年龄、地址、生日等字段会被识别为敏感字段。识别方式有两种：按字段名，如 `phone`、`buyerPhone`、`id_card`、`age`；按值的格式，即使字段名叫 `contact` 也能识别出手机号。
- 发给 AI 的只有格式描述，例如“手机号”“年龄（值已隐藏）”，不发送任何真实字符。
- 页面上的数据样例部分遮掩显示，例如 `138****5678`、`张**`、`"age":"**"`。
- 作业实际同步的数据不受影响，脱敏只作用于给 AI 和页面看的样例。
- 需要追加关键字时，配置 `ai.sensitive.field-keywords`。

## 三、部署说明

### 3.1 前置条件

| 项 | 要求 |
|---|---|
| DolphinScheduler | 3.4.2，已部署本定制分支之前的版本（OIDC、SeaTunnel 布局等改动） |
| Java | api-server 运行在 **JDK 17**（需要 JDK 而不是 JRE，引擎校验要在首次使用时编译校验程序；如果是 JRE，引擎校验会被跳过，只做本地校验） |
| SeaTunnel | api-server 所在机器安装 SeaTunnel 2.3.13（用于引擎校验，未安装也能用，只是跳过引擎校验） |
| AWS Bedrock | us-east-1 已开通 Claude Sonnet 5.5 和 Claude Haiku 5.5；api-server 有调用权限（见 3.3） |
| 数据库 | 需要执行一条建表 SQL（MySQL 或 PostgreSQL） |

### 3.2 部署步骤

本次需要升级 **api-server、master-server、worker-server**，alert-server 不变。部署包 `ds-ai-deploy-3.4.2.tar.gz` 中的 `install.sh` 会依次做预检查、备份、安装和校验，任一步失败自动还原；`rollback.sh` 用于回滚。脚本不操作数据库，建表 SQL 由 DBA 手动执行。

完整步骤见 [`tools/deploy/DEPLOY.md`](tools/deploy/DEPLOY.md)，简要流程：

```bash
tar -xzf ds-ai-deploy-3.4.2.tar.gz && cd ds-ai-deploy
./install.sh /opt/dolphinscheduler-3.4.2 --seatunnel-home /opt/seatunnel-2.3.13 --dry-run   # 预检查
# DBA 手动执行 ai_tables_mysql.sql（或 PostgreSQL 版本）
# api-server 和每台 master 的 conf/common.properties 中设置相同的 seatunnel.connection.encryption.key
# 停止 api-server / master-server / worker-server
./install.sh /opt/dolphinscheduler-3.4.2 --seatunnel-home /opt/seatunnel-2.3.13
# 启动 master-server / worker-server / api-server
```

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
| `GET/POST/PUT/DELETE /seatunnel-connections` | 连接管理（管理员）；`GET /types` 表单定义，`POST /test` 测试，`POST /grant` 授权 |
| `GET /seatunnel-connections/{key}/objects?parent=` | 浏览 topic / 库 / 表 / 目录 / 接口 |
| `POST /seatunnel-connections/{key}/describe?object=` | 读取字段（含只给用户看的数据样例） |

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
| 页面没有三栏 | `ai.enabled` 未开启；或浏览器缓存了旧前端（用无痕窗口验证） |
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
