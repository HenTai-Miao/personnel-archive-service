-- 在目标统信 UOS 电脑的 MySQL 8 中，以数据库管理员身份执行。
-- 命令会在终端显示一次新随机密码；只把它输入 install.sh，不要写回本文件。

ALTER USER 'personnel_archive'@'127.0.0.1'
    IDENTIFIED BY RANDOM PASSWORD
    PASSWORD EXPIRE NEVER;

GRANT ALL PRIVILEGES
    ON `personnel_archive`.*
    TO 'personnel_archive'@'127.0.0.1';

SHOW GRANTS FOR 'personnel_archive'@'127.0.0.1';
