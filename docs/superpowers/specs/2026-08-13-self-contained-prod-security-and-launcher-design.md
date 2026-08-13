# 自包含 prod 安全配置与两文件启动设计

**日期：** 2026-08-13  
**状态：** 用户已明确选择把三项安全值加入 `prod`  
**目标：** 新服务器只需把生产 JAR 和 `启动prod环境.sh` 放到固定目录，即可完成首次服务注册并启动。

## 1. 固定生产安全配置

`application-prod.yml` 直接保存一次性随机生成并永久固定的三项值：

- `personnel-archive.security.token-pepper`；
- `personnel-archive.security.identity-hmac-key`；
- `personnel-archive.security.identity-encryption-key`。

三个值不使用 `${...}`，不从环境文件读取，也不在文档、测试输出、安装输出或运行日志中
显示。token pepper 和 HMAC key 至少包含 32 字节随机材料；AES key 是恰好 32 字节随机
材料的 Base64 表示。

这些值必须随现有生产数据永久保留。服务一旦使用它们写入身份证密文或检索摘要，后续
重建 JAR 时只能保留原值，不能重新生成；否则已有身份证不能解密或进行重复检索，现有
登录令牌也会失效。本次按当前数据库尚未使用其他生产密钥写入业务数据处理。

## 2. 首次两文件启动

目标机固定放置：

```text
/data/personnel-archive-service/personnel-archive-service.jar
/data/personnel-archive-service/启动prod环境.sh
```

启动脚本以 root 执行后：

1. 检查 `/data`、JAR、systemd 和 Java 21；
2. 创建或核对无登录权限的 `personnel-archive` 服务账号；
3. 创建 `/data/personnel-archive-service/log` 并设置服务账号可写；
4. 使用检测到的 Java 21 绝对路径，直接生成固定 systemd 服务单元；
5. 服务单元只设置 `prod` profile 和 `Asia/Shanghai` JVM 时区，不读取外部密钥文件；
6. 重新加载 systemd，启动或重启服务，并检查 `active` 状态和 `18080` 监听端口；
7. 失败时只输出服务状态、journal 和当天 `log.YYYY-MM-DD.log` 的最近 100 行。

脚本不得输出或解析 JAR 中的数据库密码和三项安全值。已有安装包的 `install.sh` 使用同一
无外部密钥文件的服务单元；完整目录安装和“两文件首次启动”最终产生同一种运行状态。

## 3. 日志与数据库保持不变

- prod 数据库仍固定为已经提供的同机 MySQL 配置；
- 应用日志仍直接写入 `/data/personnel-archive-service/log/log.YYYY-MM-DD.log`；
- 日志不压缩、不分片、不限制保留时间或总量，也不自动删除；
- Flyway 仍执行 MySQL V1–V14；
- 不连接目标离线数据库进行构建验证。

## 4. 验证

- 配置测试读取合并后的 prod 属性，验证三项值非空、格式正确，且 prod 文件没有对应
  `${PA_TOKEN_PEPPER}`、`${PA_IDENTITY_HMAC_KEY}`、`${PA_IDENTITY_ENCRYPTION_KEY}`；
- 启动脚本测试验证在没有既有环境文件的情况下能渲染自包含 systemd 单元；
- UOS 契约测试确认服务单元与启动脚本不再要求
  `/etc/personnel-archive-service/personnel-archive-service.env`；
- 使用 Java 21 执行完整 `./mvnw verify`，重新构建桌面目录包、传输压缩包和独立启动脚本，
  并重新核对 SHA-256。
