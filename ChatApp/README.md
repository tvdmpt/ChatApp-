# Терминальный мессенджер

**Запуск сервера**
```bash
cd Server/src && javac Server.java && java Server
```

**Запуск клиента**
```bash
cd Client/src && javac Client.java && java Client
```

---

**Команды**
```
/register <имя> <пароль>   — регистрация
/login <имя> <пароль>      — вход
/join general|tech|random  — выбрать канал
/dm <имя> <текст>          — личное сообщение
/history <имя>             — история личных сообщений
/users                     — кто онлайн
/quit                      — выход
просто текст               — сообщение в канал
```

---

**Данные хранятся в** `server_data/`
```
users.txt              — логины:пароли
channel_general.log    — история канала
dm_alice_bob.log       — личная переписка
```
История загружается за последние 3 дня.