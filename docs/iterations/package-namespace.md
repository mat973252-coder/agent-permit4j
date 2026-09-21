# 包名迁移前置核验

核验日期：2026-09-22。状态：组织创建、Central 命名空间验证、Java 包名及 Maven 坐标迁移均完成；签名发布与真实试用待完成。

## 已确认事实

- 用户确认目前没有项目专用域名或 GitHub 组织，授权创建 `agentpermit4j` 组织；暂无独立接入试用者。
- 本机 `gh api user` 返回 `mat973252-coder`；仓库 origin 为 `mat973252-coder/agent-permit4j`。
- `gh api users/agentpermit4j` 返回 HTTP 404。这不是名称可注册或已归属本项目的证明。
- 后续创建已完成：[agentpermit4j](https://github.com/agentpermit4j) 为 Organization；成员 API 确认 `mat973252-coder` 为 `active/admin`。使用免费方案及用户授权的当前 GitHub 主邮箱，未邀请成员或转移仓库。
- 第一阶段：Java 前缀从 `io.github.mat973252.agentpermit` 迁至 `io.github.agentpermit4j`，暂保留旧 Maven groupId。后续在 Central 验证完成后已迁移 Maven 坐标，见下文。

## 执行顺序

1. 使用 `mat973252-coder` 创建免费的 `agentpermit4j` GitHub 组织，并核验该账号的 owner 身份。组织创建不包含转移现有仓库。
2. 归属确认后已选定 Java 包名前缀 `io.github.agentpermit4j`。Maven groupId 独立决策，组织创建不等于获得 Central 发布权限。
3. 同步迁移源码和测试目录、package/import、反射类名、Spring 自动配置、运行入口、脚本、示例和文档。注明源码及二进制不兼容，消费方需要更新引用并重新编译。
4. 检查旧前缀残留，允许迁移说明保留历史名称；运行全量 Maven Wrapper verify、候选构建及仓库外独立消费的在线/离线验收。
5. 真实试用者未确定时，试用记录保持待安排；不以自动化验收替代真人采用证据，不按猜测扩展 DTO 或代理支持。

## 发布权限边界

Sonatype 的 [命名空间说明](https://central.sonatype.org/register/namespace/) 指出，GitHub 登录自动分配针对个人用户名，组织名称不会自动注册。正式发布前仍需通过 Portal 的归属验证。

组织归属与 Central 命名空间均已核验；签名、上传凭据与制品公开发布仍未完成。

### Central 后续核验

- 用户明确同意 Sonatype 读取 GitHub 邮箱以登录。使用 `mat973252-coder` GitHub 登录后，Portal 的个人命名空间 `io.github.mat973252-coder` 已显示 Verified。
- 注册 `io.github.agentpermit4j`，按 Portal 指示在组织下创建公开空验证仓库，再提交 Verify Namespace。刷新后项目命名空间显示 `Org: Agentpermit4j Verified`。
- 在此证据基础上，Maven groupId 从 `io.github.mat973252` 迁至 `io.github.agentpermit4j`，同步父 POM、模块依赖、独立消费工程、候选收集路径和接入文档。上文保留先完成 Java 迁移、暂保留旧 groupId 的历史过程。
- 临时空验证仓库仍保留在组织下；未生成发布令牌、签名密钥、tag 或公开制品。命名空间 Verified 不能当作已发布。
- 新 groupId 下执行 `.\mvnw.cmd -B -ntp clean verify` 成功，214 项测试通过；重新执行独立消费脚本成功，在线及离线各 10 项测试通过、业务写入一次、41 个候选文件齐全。验证复用了第三方依赖下载缓存，SDK 以新坐标重新安装，消费源码复制到新的仓库外目录。

## 迁移验证结果

- 先迁移测试声明和 import，执行 `.\mvnw.cmd -B -ntp -pl agent-permit-core test`，确认因新包中的类型尚不存在而编译失败。
- 迁移生产代码及资源后，`.\mvnw.cmd -B -ntp clean verify` 通过原有 212 项测试。
- 增加 Spring 自动配置发现和 Redis Lua 资源加载两项回归；`.\mvnw.cmd -B -ntp verify` 通过 214 项测试。
- 执行 `.\scripts\verify-adoption.ps1 -MavenRepository <迁移前隔离仓库>`，复用第三方下载、重新 clean install SDK，并复制消费源码至新的仓库外目录；在线、离线各 10 项测试通过，输出 `writes=1 available=8 retrySame=true`，41 个未签名候选文件齐全。
- `git diff --check` 通过。旧前缀仅保留在迁移历史说明中。真实 Redis IT 本轮未运行；Lua 内容与 Redis 状态协议未改动。
- 迁移为源码和二进制不兼容变更，升级说明见 [CHANGELOG](../../CHANGELOG.md#breaking-java-namespace-migration)。当前更改尚未提交、推送或发布。
