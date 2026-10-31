# 独立世界书：实现合同与验收状态

> 书库、编辑器、绑定与请求投影已实现；真机验收仍按本文末尾的完成标准逐项记录。世界书条目支持 ST 的 `selective`、互斥组（`group` / `group_override` / `group_weight` / `use_group_scoring`）、`delay`、`delay_until_recursion`、`ignoreBudget`、`triggers`、角色过滤、条目级 `scanDepth`，以及书级 `scan_depth` / `token_budget` / `recursive_scanning`。正则匹配与向量化仍未执行：字段保留并报告，不静默降级为普通关键词。

## 选择与激活语义

- 激活计算集中在 `WorldSelection`：`selective=false` 时只看主关键词，次级关键词不参与；互斥组按 `group` / `group_override` / `group_weight` / `use_group_scoring` 计分。
- `delay`、`delay_until_recursion`、`ignoreBudget`、`triggers`、角色过滤、条目级 `scanDepth`，以及书级 `scan_depth` / `token_budget` / `recursive_scanning` 都在这里执行。
- 关键词扫描覆盖最近 `scanDepth` 条 user + assistant 消息。
- 常驻条目有独立预算，占用 `{{world}}` 槽位。
- 重写与续写不推进 sticky / cooldown 时钟。
- 正则与向量化激活不执行：字段保留并报告，不静默降级成普通关键词。

## 数据与隐私

只处理用户明确创建、选择导入或绑定的世界书；不扫描其他应用、聊天记录或远程资源。新共享世界书、不可变内容修订、角色默认绑定、会话清单、请求计划和幂等回执计划存于现有 SQLCipher 数据库。旧卡与记忆仍在应用私有文件，不自动迁移、不宣称已加密。

管理工具读取的白名单正文会发送给当前模型，并进入已有工具结果历史；本地读取不等于离线。原始 JSON、未知扩展、账本、私有路径和其他会话内容不随管理结果外发。实际生成只发送本次解析且入选的设定。日志、进度、审批 audit reason 不记录书名、正文、关键词、宏结果或聊天；诊断只记录 ID、计数、版本、长度和摘要 hash。

不执行导入数据中的脚本，不下载附件，不增加模型服务或 Android 权限。导出由用户主动选择，外部 JSON 中原有的指令和扩展会恢复，交给其他前端前应自行检查。

## 共享、绑定与版本

书为独立资产，角色和会话保存引用，不复制正文。角色绑定只提供未来新会话的默认来源；新会话固定共享书修订，已有会话明确升级后才读取新版本。无共享清单的旧会话不自动继承后来新增的角色绑定。

会话禁用压过全部角色来源；不同来源引用同书只运行一次但保留完整来源。不同书的相同正文不合并。书级启用属于内容修订，条目启用、常驻与绑定启用各自独立。删除有引用（含禁用引用）的书必须拒绝；解绑、停用、删除不是同一操作。

只固定新增共享书，不冻结旧卡内 world。分支复制父会话当前共享清单并重新计时，不声称还原分支历史切点的世界状态。读取、无变化、只读解释不写修订、不抽概率、不展开写宏、不推进 sticky/cooldown。

## dsh 请求投影合同

官方 loop 请求仍从会话事件派生，冻结对象不修改，重建 invariant 不移除。应用在 `llm/stream` 入口读取精确 loop 身份，用异步调用域传至 pi-ai 适配边界；即使适配器因工具、附件或跨提供商 replay 创建副本，身份也不由可复制字段冒充。未标记的辅助请求显式清除调用域，不能因同会话或嵌套调用继承世界计划。

内部鉴权桥动作 `resolve_world_request` 携带完整 `dsh_session_id` 与真实用户 `trigger_message_id`。系统上下文、指令文件等 user-role 注入不是用户触发。宿主按已登记映射返回本逻辑请求的固定计划，不截会话字符串猜 ID。未知映射、过期触发和取消请求失败关闭；普通日常会话返回 inactive。此动作不注册成模型工具。

计划 `zoneu.world.request.v1` 包含 active；启用时包含 request_id、session_id、binding_revision 与 injections。条目包含 entry_id、position（before／after／depth）、depth（0–12）、role、content。before／after 仅接受 system，并要求 `character_anchor` 在已拼装系统提示中精确且唯一匹配角色块；没有精确锚点就拒绝，不能把整个设备操作契约前后冒充角色前后。深度从历史尾部按消息计数，完整工具 call／result 及相邻推理重放作为不可分单元；depth 0 在末尾。宿主与直连必须使用相同定义。

投影返回新副本，不改 Harness 消息、原 pi-ai 上下文或输入 payload；每次提供商请求由原始派生输入重建，不在旧投影上追加。网络重试与工具续步复用宿主计划。图片、推理、工具 schemas、鉴权、会话头与取消信号保持原有通道。

| 协议 | 精确表达边界 |
|---|---|
| OpenAI Chat Completions | 支持 wire 层 system／user／assistant 深度插入；保留工具配对 |
| OpenAI Responses | 支持 input message 角色与深度；保留 reasoning／function_call／function_call_output |
| Anthropic Messages | 角色前后可合入唯一系统角色块；深度 system 条目合入开头系统块（位置变平，不静默改写成 user） |
| 其他协议 | 有激活项时未验证即拒绝，不能宣称兼容全部模型 |

这些是投影合同，不是任意网关／模型均接受的承诺。具体不兼容应显示调整条目角色／位置或切换通道的入口，不应把所有 dsh 一概降为关键词前缀。提供商结束、首 token、UI 停止收集均不代表回答已经持久化；计时结算必须使用回答落盘证据及宿主幂等回执。

## 通用文件写入保护边界

计划以实际 fs provider 的 writeText／editText 为保护点，阻止通用 write／edit 绕过专用角色／世界书工具的 CAS 和审批。保护应用 roleplay、SQLCipher 数据库及 WAL／SHM、偏好、运行模块与配置。路径检查覆盖直接／相对路径、已有父目录、符号链接和硬链接疑义，拒绝无法安全解析的目标。普通 dsh-home 记忆文件仍可写。

这不是同 UID shell 沙箱：经审批的 `run_command` 仍可能修改这些数据，不能承诺绝对隔离，也不能建议模型用 shell 绕过拒绝。保护模块本身及 reload 配置同样应受保护，不能让通用写工具改配置后卸掉保护。

## 完成标准

实现后分别记录实际执行的 Node 最终 wire 测试、迁移／CAS／恢复测试、Kotlin 单元与质量门、profile parser 与打包、debug／AndroidTest APK 构建。真机由用户用合成书操作，不注入触摸；安装、停止应用另取本轮授权。不卸载、不清数据。构建成功不等于手机验收通过。
