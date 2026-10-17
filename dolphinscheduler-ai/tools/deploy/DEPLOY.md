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

# SeaTunnel AI 助手生产部署说明

适用版本：DolphinScheduler 3.4.2，定制分支 `dev-3.4.2-ai-seatunnel`。部署包 `ds-ai-deploy-3.4.2.tar.gz` 一次装好以下全部功能：

- AI 生成 / 修改 SeaTunnel 配置：三栏布局、校验与自动修复、对比确认、回撤 / 重做
- 聊天记录按任务保存、清空；只读查看节点时也显示三栏和历史对话
- Http 接口字段推断、查看接口响应原文
- **SeaTunnel 配置（连接管理）**：在安全中心维护 Kafka / MySQL / PostgreSQL / ClickHouse / HTTP / S3 连接，并按用户授权。在 AI 助手顶部选择来源和目标后，AI 会自动读取字段并做对齐。脚本中的密码以 `${stconn.<标识>.<字段>}` 引用，任务运行时由 master 注入。

## 一、改动范围

| 服务 | 改动 | 是否需要重启 |
|---|---|---|
| api-server | 42 个 jar（新增 25 个第三方 jar、升级 14 个 AWS SDK jar，以及 ai / api / dao 三个 jar）、ui、`application.yaml` 增加 `ai:` 配置、`common.properties` 增加密钥 | 是 |
| master-server | `dolphinscheduler-master / dao / task-executor-3.4.2.jar`（运行时注入连接密码）、`common.properties` 增加密钥 | 是 |
| worker-server | `dolphinscheduler-worker / task-executor-3.4.2.jar`（服务日志中隐藏注入的密码） | 是 |
| alert-server | 无 | 否 |
| 数据库 | 新增 3 张表，由 DBA 手动执行 SQL | — |

> master、worker 和 api-server 要一起升级。只升级 api-server 时，使用了 `${stconn...}` 引用的任务在旧 master 上运行会失败（密码不会被替换）。

## 二、部署包内容

| 文件 | 说明 |
|---|---|
| `install.sh` | 安装脚本：检查 → 备份 → 安装 → 校验，任一步失败自动还原。**不连接、不修改数据库** |
| `rollback.sh` | 回滚脚本：从备份还原 libs、ui、application.yaml |
| `api-server-libs/`、`master-server-libs/`、`worker-server-libs/` | 各服务的 jar |
| `remove-from-api-server-libs.txt` | api-server 中要删除的 14 个旧版 AWS SDK jar |
| `ui/dolphinscheduler-ui-3.4.2-ai.tar.gz` | 新前端（入口文件 `assets/index.aba08b18.js`） |
| `application-ai.yaml` | `ai:` 配置片段 |
| `ai_tables_mysql.sql` / `ai_tables_postgresql.sql` | 新建 3 张表（`CREATE TABLE IF NOT EXISTS`，不改已有表） |
| `ai_conversation_add_context_*.sql` | **只用于**之前已经建过旧版 `t_ds_ai_conversation` 的环境（例如测试环境），补 `context` 列 |
| `seatunnel-metadata-exporter/` | connector 元数据导出工具 |
| `SHA256SUMS` | 文件校验和，安装脚本会自动校验 |

## 三、前置条件

| 项 | 要求 |
|---|---|
| Java | api-server 使用 **JDK 17+**（需要 JDK，用 JRE 时会跳过引擎校验）；master / worker 的 Java 版本不变 |
| SeaTunnel | api-server 所在机器安装 SeaTunnel 2.3.13，用于引擎校验，可选 |
| Bedrock | api-server 所在 EC2 的实例角色能调用 Claude，见第五节 |
| 网络 | api-server 能访问 Bedrock（`bedrock-runtime.us-east-1.amazonaws.com:443`），以及要在 AI 助手中浏览的 Kafka、数据库、S3、HTTP 接口；worker 能访问作业实际要读写的地址 |
| 工具 | 服务器上有 `unzip`，用于检查 jar 版本冲突；没有时脚本给出提示，跳过这项检查 |

## 四、部署步骤

以 `DS_HOME=/opt/dolphinscheduler-3.4.2`、SeaTunnel 安装在 `/opt/seatunnel-2.3.13` 为例。api / master / worker 不在同一台机器时，**每台机器上都执行第 1、4、5、6 步**，用 `--components` 指定该机器上的服务。

**1. 上传、解压、预检查（不做任何修改）**

```bash
tar -xzf ds-ai-deploy-3.4.2.tar.gz && cd ds-ai-deploy
./install.sh /opt/dolphinscheduler-3.4.2 --seatunnel-home /opt/seatunnel-2.3.13 --dry-run
# 分机器部署时，例如 worker 机器：./install.sh /opt/dolphinscheduler-3.4.2 --components worker --dry-run
```

此时服务还在运行，“is running” 一项报错是正常的，停服后会通过。其余项应为 `[OK]` 或可接受的 `[WARN]`。

**2. 建表（DBA 手动执行，只需一次）**

在 DolphinScheduler 元数据库中执行 `ai_tables_mysql.sql` 或 `ai_tables_postgresql.sql`（二选一）。执行后确认：

```sql
SELECT COUNT(*) FROM t_ds_ai_conversation;
SELECT COUNT(*) FROM t_ds_seatunnel_connection;
SELECT COUNT(*) FROM t_ds_relation_seatunnel_connection_user;
```

如果之前装过测试版（`t_ds_ai_conversation` 已存在、但没有 `context` 列），再执行一次 `ai_conversation_add_context_*.sql`。

**3. 设置连接密钥（api-server 和 master 必须相同）**

连接的密码、证书用 AES 加密后存入数据库。api-server 加密，master 解密，所以两边要用同一个密钥。生成一个随机值：

```bash
openssl rand -base64 32
```

追加到 `api-server/conf/common.properties`，以及**每一台** master 的 `master-server/conf/common.properties`：

```properties
seatunnel.connection.encryption.key=<上面生成的值>
```

- 不设置也能运行，但只会用内置密钥做混淆，安全性低，脚本会给出 `[WARN]`。
- 设置后**不要再修改**，否则已保存的连接密码无法解密，需要在页面上重新填写。
- 同一台机器上 api-server 和 master 的密钥不一致时，脚本会报错。

**4. 停止服务**

```bash
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh stop api-server
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh stop master-server
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh stop worker-server
```

**5. 安装**

```bash
./install.sh /opt/dolphinscheduler-3.4.2 --seatunnel-home /opt/seatunnel-2.3.13
```

脚本会做这几件事：
1. 再次执行全部检查，有错误就不做任何修改。检查内容包括：
   - 包完整性、版本是否为 3.4.2、服务是否已停止；
   - JDK 17、所需的 jar、密钥；
   - **jar 版本冲突**：按 jar 内的 Maven 坐标查找手动放进去的其他版本 jar，例如别的版本的 langchain4j。发现冲突时报错，确认后加 `--remove-conflicts` 删除，删除的 jar 保留在备份中。
2. 备份各服务的 `libs`，以及 api-server 的 `ui` 和 `application.yaml`，备份到 `DS_HOME/ai-backup-<时间>/`。
3. api-server：删除 14 个旧版 AWS SDK jar，拷入新 jar，替换 ui，在 `application.yaml` 末尾追加 `ai:` 配置（已有 `ai:` 段时不改）。
4. master / worker：拷入新 jar，同名文件直接覆盖。
5. 校验装好的文件。任一步失败会自动还原。

`datasync-2.17.282.jar`、`netty-nio-client-2.17.282.jar` 会保留，这是正常的。

**6. 启动服务**

先启动 master 和 worker，再启动 api-server：

```bash
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh start master-server
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh start worker-server
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh start api-server
```

api-server 日志中应出现 `AI assistant uses AWS Bedrock in us-east-1`，并且没有 `NoSuchMethodError` 或 `ClassNotFoundException`。

## 五、Bedrock 权限（部署前由 AWS 管理员完成）

给 api-server 所在 EC2 的实例角色（例如 `<实例角色名>`，账号 `<账号ID>`）添加内联策略：

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

然后在 Bedrock 控制台（us-east-1）的 Model access 中开通 Claude Sonnet 5.5 和 Claude Haiku 5.5。验证：

```bash
aws bedrock-runtime converse --region us-east-1 --model-id us.anthropic.claude-sonnet-5-5 \
  --messages '[{"role":"user","content":[{"text":"Reply OK"}]}]'
```

## 六、部署后验证

用浏览器无痕窗口打开，避免缓存旧前端：

| # | 操作 | 预期 |
|---|---|---|
| 1 | 打开 SeaTunnel 节点 | 三栏：配置 / AI 对话 / 编辑器 |
| 2 | 在对话中描述一个同步需求 | 有进度和对比视图，校验结果为“通过”，可以接受 |
| 3 | 关闭弹窗再打开 | 显示历史对话 |
| 4 | 安全中心 → SeaTunnel配置 → 创建连接（例如 Kafka、PG），点“测试连接”。Kafka 集群按前缀授权消费者组时，填写“消费者组前缀” | 提示“连接成功” |
| 5 | 用户管理 → 授权 → SeaTunnel连接，授权给一个普通用户 | 该用户在 AI 助手顶部能看到“数据来源 / 写入目标” |
| 6 | 在 AI 助手顶部选 Kafka 集群 → topic，选 PG → 表（或勾选“自动新建表”） | 显示“字段 N 个”，点开能看到字段和数据样例 |
| 7 | 发送“把选中的 topic 同步到选中的表” | AI 不再询问地址和账号；脚本中的密码为 `"${stconn.<标识>.password}"`；字段按名称和类型对齐 |
| 7b | 不用选择栏，直接发送“把 <连接标识> 的 <topic> 同步到 <连接标识> 的 <表>” | 进度中显示“读取字段 …”，AI 自己取样，不要求提供样例；回复下方的“数据样例”中敏感字段已遮掩 |
| 8 | 保存并运行该任务 | 作业成功、数据写入；任务日志和 worker 服务日志中看不到真实密码 |
| 9 | 打开工作流实例查看该节点 | 三栏，只读，显示历史对话和选择结果 |

## 七、回滚

**方式一：完全回滚**，恢复到第一次执行 `install.sh` 之前的 libs、ui、application.yaml：

```bash
# 停止 api-server / master-server / worker-server 后执行
./rollback.sh /opt/dolphinscheduler-3.4.2
# 或指定备份：./rollback.sh /opt/dolphinscheduler-3.4.2 /opt/dolphinscheduler-3.4.2/ai-backup-20261017100000
# 再启动服务
```

注意：回滚后，脚本中使用了 `${stconn...}` 引用的 SeaTunnel 任务无法运行。需要先把这些任务改回明文或任务参数，再回滚。

**方式二：只关闭 AI**：把 `application.yaml` 中的 `ai.enabled` 改为 `false`，然后重启 api-server。页面恢复为两栏；已有任务中的 `${stconn...}` 引用仍由 master 正常注入。

新增的 3 张表回滚后可以保留，不影响 DolphinScheduler。

## 八、常见问题

| 现象 | 处理 |
|---|---|
| 预检查报 `package files do not match SHA256SUMS` | 部署包上传不完整，重新上传 |
| 预检查报 `other versions of the jars to install exist` | 环境里有手动放进去的其他版本 jar。核对后加 `--remove-conflicts` 重新执行 |
| 预检查报 `... differs between api-server and master-server` | 两边的 `seatunnel.connection.encryption.key` 不一致，改成相同的值 |
| 任务失败：`SeaTunnel connection 'xxx' not found` | 连接被删除，或者 master 连接的不是同一个数据库 |
| master 日志：`Failed to decrypt the SeaTunnel connection secrets` | master 和 api-server 的密钥不一致，或者保存连接后修改过密钥 |
| AI 助手顶部没有“数据来源 / 写入目标” | 当前用户没有被授权任何连接（管理员能看到全部） |
| 测试连接报 `... needs the AI assistant to be enabled` | Kafka / HTTP 连接的浏览依赖 AI 模块，需要 `ai.enabled: true` |
| Kafka 读不到样例或作业报 `GroupAuthorizationException` | 连接没有填“消费者组前缀”，或账号没有该前缀的消费者组权限。读样例使用 `<前缀>-dolphinscheduler-ai-sample`，作业使用 `<前缀>-ds-<任务code>` |
| 发消息报 `not authorized to perform: bedrock:...` | 按第五节加 IAM 权限，不需要重启 |
| 启动报 AWS SDK 相关的 `NoSuchMethodError` | libs 中残留了旧版 AWS SDK jar，执行回滚后重新安装 |
