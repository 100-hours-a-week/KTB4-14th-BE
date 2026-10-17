-- ProxySQL이 백엔드로 접속할 앱 계정과 상태 확인용 monitor 계정
ALTER USER 'audigo'@'%' IDENTIFIED WITH mysql_native_password BY 'audigo';

CREATE USER IF NOT EXISTS 'monitor'@'%' IDENTIFIED WITH mysql_native_password BY 'monitor';
GRANT USAGE, REPLICATION CLIENT ON *.* TO 'monitor'@'%';
