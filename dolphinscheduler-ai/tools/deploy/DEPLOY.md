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

适用：DolphinScheduler 3.4.2，定制分支 `dev-3.4.2-ai-seatunnel`。部署包 `ds-ai-deploy-3.4.2.tar.gz` 包含以下全部功能，一次装好：

- AI 生成 / 修改 SeaTunnel 配置（三栏布局、校验与自动修复、对比确认、回撤 / 重做）
- 聊天记录按任务保存、清空聊天记录；只读查看节点（如工作流实例的“当前节点设置”）时也显示三栏和历史对话
- Http 接口字段推断、查看接口响应原文

**只改 api-server**。master / worker / alert 不用替换任何文件，也不用重启。

## 一、部署包内容

| 文件 | 说明 |
|---|---|
| `install.sh` | 安装脚本：检查 → 备份 → 删除旧 jar → 拷入新 jar → 替换 ui → 追加配置 → 校验；任一步失败自动还原。**不连接、不修改数据库** |
| `rollback.sh` | 回滚脚本：从备份还原 libs、ui、application.yaml |
| `api-server-libs/`（39 个 jar） | 22 个新增第三方 jar + 14 个升级的 AWS SDK jar（2.17.282 → 2.42.4）+ `dolphinscheduler-ai/api/dao-3.4.2.jar` |
| `remove-from-api-server-libs.txt` | 要删除的 14 个旧版 AWS SDK jar |
| `ui/dolphinscheduler-ui-3.4.2-ai.tar.gz` | 新前端（入口文件 `assets/index.7cf1cebb.js`） |
| `application-ai.yaml` | `ai:` 配置片段 |
| `ai_conversation_mysql.sql` / `ai_conversation_postgresql.sql` | 聊天记录表 `t_ds_ai_conversation` 的建表语句，由 DBA 手动执行 |
| `seatunnel-metadata-exporter/` | connector 元数据导出工具（安装了更多 connector 时用） |
| `SHA256SUMS` | 文件校验和，安装脚本会自动校验 |

## 二、前置条件

| 项 | 要求 | 检查方法 |
|---|---|---|
| Java | api-server 使用 **JDK 17+**（要 JDK，JRE 下引擎校验会被跳过） | 安装脚本自动检查 `bin/env/dolphinscheduler_env.sh` 中的 `JAVA_HOME` |
| SeaTunnel | api-server 机器上安装 SeaTunnel 2.3.13（用于引擎校验，没有也能用） | `ls <SEATUNNEL_HOME>/starter/seatunnel-starter.jar` |
| Bedrock 权限 | api-server 所在 EC2 的实例角色可以调用 Claude（见第四节） | 第四节的 `aws bedrock-runtime converse` 命令 |
| 数据库 | 有建表权限的账号 | — |
| 网络 | api-server 能访问 `bedrock-runtime.us-east-1.amazonaws.com:443` | 第四节的验证命令能返回即可 |

## 三、部署步骤

以 `DS_HOME=/opt/dolphinscheduler-3.4.2`、SeaTunnel 安装在 `/opt/seatunnel-2.3.13` 为例。

**1. 上传并解压**

```bash
tar -xzf ds-ai-deploy-3.4.2.tar.gz
cd ds-ai-deploy
```

**2. 预检查（不做任何修改）**

```bash
./install.sh /opt/dolphinscheduler-3.4.2 --seatunnel-home /opt/seatunnel-2.3.13 --dry-run
```

所有项为 `[OK]` 或可接受的 `[WARN]` 后再继续。此时 api-server 还在运行，“the api server is running” 这一项报错是正常的，停服后会通过。

**3. 建表（手动执行 SQL）**

部署脚本不执行任何 SQL。请按元数据库类型，用你们的数据库工具在 DolphinScheduler 元数据库中**手动执行**对应文件（只需一次，语句为 `CREATE TABLE IF NOT EXISTS`，表已存在会跳过）：

- MySQL：`ai_conversation_mysql.sql`
- PostgreSQL：`ai_conversation_postgresql.sql`

只新建 `t_ds_ai_conversation` 一张表，不修改已有表和数据。执行后确认表已存在：`SELECT COUNT(*) FROM t_ds_ai_conversation;`

**4. 停止 api-server**

```bash
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh stop api-server
```

**5. 安装**

```bash
./install.sh /opt/dolphinscheduler-3.4.2 --seatunnel-home /opt/seatunnel-2.3.13
```

脚本会：
1. 再次执行全部检查，有错误则不做任何修改；
2. 备份 `api-server/libs`、`api-server/ui`、`api-server/conf/application.yaml` 到 `api-server/ai-backup-<时间>/`；
3. 删除 14 个旧版 AWS SDK jar，拷入 39 个 jar（覆盖 `dolphinscheduler-api/dao-3.4.2.jar`）；
4. 替换 `api-server/ui`；
5. 在 `application.yaml` 末尾追加 `ai:` 配置（`ai.enabled: true`，`ai.seatunnel.home` 设为 `--seatunnel-home` 的值）；如果已有 `ai:` 段则不改；
6. 校验已安装的文件；任一步失败会自动还原备份。

`datasync-2.17.282.jar`、`netty-nio-client-2.17.282.jar` 会保留，属于正常情况。

**6. 检查配置**（可选）

打开 `api-server/conf/application.yaml`，确认末尾的 `ai:` 段，常用项：

```yaml
ai:
  enabled: true
  rate-limit-per-user-per-minute: 10   # 每个用户每分钟最多请求数
  llm:
    region: us-east-1
    model: us.anthropic.claude-sonnet-5-5
    fast-model: us.anthropic.claude-haiku-5-5
  seatunnel:
    home: "/opt/seatunnel-2.3.13"
  http-sample:
    denied-hosts: []                   # 不允许 AI 调用的内网地址，例如 [10.0.1.0/24]
```

**7. 启动 api-server**

```bash
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh start api-server
tail -f /opt/dolphinscheduler-3.4.2/api-server/logs/dolphinscheduler-api.log
```

日志中应出现 `AI assistant uses AWS Bedrock in us-east-1, model: us.anthropic.claude-sonnet-5-5`，并且没有 `NoSuchMethodError`、`ClassNotFoundException`。

## 四、Bedrock 权限（部署前由 AWS 管理员完成）

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

并在 Bedrock 控制台（us-east-1）→ Model access 中开通 Claude Sonnet 5.5 和 Claude Haiku 5.5。在 api-server 机器上验证：

```bash
aws bedrock-runtime converse --region us-east-1 --model-id us.anthropic.claude-sonnet-5-5 \
  --messages '[{"role":"user","content":[{"text":"Reply OK"}]}]'
```

返回 `OK` 即可。如果报 `not authorized to perform: bedrock:InvokeModelWithResponseStream`，说明策略还没加上或没生效。

## 五、部署后验证

用浏览器无痕窗口打开（避免缓存旧前端）：

| # | 操作 | 预期 |
|---|---|---|
| 1 | 打开工作流定义，新建 / 编辑一个 SeaTunnel 节点 | 弹窗为三栏：配置、AI 对话、编辑器 |
| 2 | 发送“把 MySQL（host:3306，库 shop，用户 etl）的 orders 表同步到 Console” | 显示查询参数、本地校验、引擎校验进度；右侧出现对比视图，校验“通过” |
| 3 | 点“接受” | 脚本写入编辑器 |
| 4 | 关闭弹窗再打开 | 显示“以下为历史对话”；“清空聊天记录”可用 |
| 5 | 发送带完整内网接口地址的需求，例如“把 http://<接口>/xxx 同步到 Console” | 进度出现“调用接口获取字段”，回复下方可展开“响应原文”，生成的 schema 与接口一致 |
| 6 | 打开该工作流的一个实例，查看这个 SeaTunnel 节点 | 同样是三栏，显示历史对话，输入框为“只读查看”，没有“清空聊天记录”按钮 |
| 7 | 保存节点并运行工作流 | 作业正常执行（与 AI 无关的原有功能不受影响） |

## 六、回滚

**方式一：完全回滚**（恢复部署前的 libs、ui、application.yaml）

```bash
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh stop api-server
./rollback.sh /opt/dolphinscheduler-3.4.2                 # 默认恢复到首次安装 AI 助手之前的状态（即使安装过多次）
# 或指定备份：./rollback.sh /opt/dolphinscheduler-3.4.2 /opt/dolphinscheduler-3.4.2/api-server/ai-backup-20261015160000
/opt/dolphinscheduler-3.4.2/bin/dolphinscheduler-daemon.sh start api-server
```

**方式二：只关闭 AI**：把 `application.yaml` 中 `ai.enabled` 改为 `false` 并重启 api-server，页面恢复为原来的两栏，不会创建任何 AI 组件。

`t_ds_ai_conversation` 表回滚后可以保留，不影响 DolphinScheduler。备份目录确认无需回滚后可以删除。

## 七、常见问题

| 现象 | 处理 |
|---|---|
| 预检查报 `package files do not match SHA256SUMS` | 部署包上传不完整，重新上传 |
| 预检查报 Java 版本低于 17 | 修改 `bin/env/dolphinscheduler_env.sh` 中 api-server 使用的 `JAVA_HOME` 为 JDK 17 |
| 启动报 AWS SDK 相关 `NoSuchMethodError` | `api-server/libs` 中仍有 2.17.282 的 AWS SDK 核心 jar，执行回滚后重新安装 |
| 发消息报 `not authorized to perform: bedrock:...` | 按第四节加 IAM 权限，无需重启 |
| 校验结果为“引擎校验：未执行” | `ai.seatunnel.home` 未配置或路径不对，或 api-server 运行在 JRE 上 |
| 打开历史对话报表不存在 | 未执行第三步建表 |
| 页面不是三栏 | `ai.enabled` 不是 `true`，或浏览器缓存了旧前端 |
