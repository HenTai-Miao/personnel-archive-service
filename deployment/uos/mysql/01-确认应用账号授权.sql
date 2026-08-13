-- 在目标统信 UOS 电脑的 MySQL 8 中，以数据库管理员身份执行。
-- 本文件只确认库级授权，不修改 personnel_archive@127.0.0.1 的既有密码。

GRANT ALL PRIVILEGES
    ON `personnel_archive`.*
    TO 'personnel_archive'@'127.0.0.1';

SHOW GRANTS FOR 'personnel_archive'@'127.0.0.1';
