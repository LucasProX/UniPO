# UniPO database and MinIO reset stack

This package starts only MySQL and MinIO for UniPO.

It is intentionally destructive: every run of `reset-data.sh` removes the Compose volumes first, so MySQL data and MinIO objects are cleared before the containers start again.

## Usage

1. Copy `.env.example` to `.env`.
2. Change the passwords in `.env`.
3. Run:

```bash
chmod +x reset-data.sh
./reset-data.sh
```

## Backend settings

Point the app backend to this stack with values like:

```env
SPRING_DATASOURCE_URL=jdbc:mysql://YOUR_SERVER_IP:12306/biecuoguo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
SPRING_DATASOURCE_USERNAME=biecuoguo
SPRING_DATASOURCE_PASSWORD=your_mysql_password
MINIO_ENDPOINT=http://YOUR_SERVER_IP:24880
MINIO_PUBLIC_ENDPOINT=http://YOUR_SERVER_IP:24880
MINIO_ACCESS_KEY=your_minio_access_key
MINIO_SECRET_KEY=your_minio_secret_key
MINIO_BUCKET=campuspo
MINIO_PUBLIC_READ=false
```
