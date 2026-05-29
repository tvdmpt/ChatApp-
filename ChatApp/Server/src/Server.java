import java.io.*;
import java.net.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;

public class Server {

    private static final int PORT = 12345;
    private static final String[] CHANNELS = {"general", "tech", "random"};
    private static final int HISTORY_DAYS = 3;

    // username -> password (хранится в файле users.txt)
    private static final Map<String, String> users = new ConcurrentHashMap<>();

    // username -> ClientHandler (только онлайн)
    private static final Map<String, ClientHandler> onlineClients = new ConcurrentHashMap<>();

    // channel -> список участников
    private static final Map<String, Set<String>> channelMembers = new ConcurrentHashMap<>();

    private static final String DATA_DIR = "server_data";
    private static final String USERS_FILE = DATA_DIR + "/users.txt";

    public static void main(String[] args) throws IOException {
        // Создаём папку для данных
        new File(DATA_DIR).mkdirs();

        // Инициализируем каналы
        for (String ch : CHANNELS) {
            channelMembers.put(ch, Collections.synchronizedSet(new HashSet<>()));
        }

        // Загружаем пользователей из файла
        loadUsers();

        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("=== Сервер запущен на порту " + PORT + " ===");

        while (true) {
            Socket socket = serverSocket.accept();
            new ClientHandler(socket).start();
        }
    }

    // ─── Сохранение/загрузка пользователей ──────────────────────────────────

    private static synchronized void loadUsers() {
        File f = new File(USERS_FILE);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) users.put(parts[0], parts[1]);
            }
        } catch (IOException e) {
            System.err.println("Ошибка загрузки пользователей: " + e.getMessage());
        }
        System.out.println("Загружено пользователей: " + users.size());
    }

    private static synchronized void saveUsers() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(USERS_FILE))) {
            for (Map.Entry<String, String> entry : users.entrySet()) {
                pw.println(entry.getKey() + ":" + entry.getValue());
            }
        } catch (IOException e) {
            System.err.println("Ошибка сохранения пользователей: " + e.getMessage());
        }
    }

    // ─── Работа с историей сообщений ────────────────────────────────────────

    // Возвращает путь к лог-файлу для канала или личного чата
    private static String getLogPath(String chatName) {
        return DATA_DIR + "/" + chatName + ".log";
    }

    // Записывает сообщение в лог
    static synchronized void logMessage(String chatName, String from, String text) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String line = timestamp + "|" + from + "|" + text;
        try (PrintWriter pw = new PrintWriter(new FileWriter(getLogPath(chatName), true))) {
            pw.println(line);
        } catch (IOException e) {
            System.err.println("Ошибка записи лога: " + e.getMessage());
        }
    }

    // Читает историю за последние HISTORY_DAYS дней
    static List<String> loadHistory(String chatName) {
        List<String> result = new ArrayList<>();
        File f = new File(getLogPath(chatName));
        if (!f.exists()) return result;

        LocalDateTime cutoff = LocalDateTime.now().minusDays(HISTORY_DAYS);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\\|", 3);
                if (parts.length == 3) {
                    try {
                        LocalDateTime msgTime = LocalDateTime.parse(parts[0], fmt);
                        if (msgTime.isAfter(cutoff)) {
                            result.add("MSG " + parts[1] + " " + parts[2]);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (IOException e) {
            System.err.println("Ошибка чтения лога: " + e.getMessage());
        }
        return result;
    }

    // ─── Рассылка сообщений ──────────────────────────────────────────────────

    // Разослать сообщение всем участникам канала
    static void broadcastToChannel(String channel, String line, String excludeUser) {
        Set<String> members = channelMembers.get(channel);
        if (members == null) return;
        for (String username : members) {
            if (!username.equals(excludeUser)) {
                ClientHandler h = onlineClients.get(username);
                if (h != null) h.send(line);
            }
        }
    }

    // ─── Обработчик клиента ──────────────────────────────────────────────────

    static class ClientHandler extends Thread {
        private final Socket socket;
        private PrintWriter out;
        private BufferedReader in;

        private String username = null;   // null = не авторизован
        private String currentChannel = null;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        // Отправить строку клиенту
        void send(String line) {
            out.println(line);
        }

        @Override
        public void run() {
            try {
                out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                send("INFO Добро пожаловать! Используйте REGISTER или LOGIN");

                String line;
                while ((line = in.readLine()) != null) {
                    handleCommand(line.trim());
                }
            } catch (IOException e) {
                // клиент отключился
            } finally {
                disconnect();
            }
        }

        private void handleCommand(String line) {
            if (line.isEmpty()) return;

            // Разбиваем на команду и аргументы
            String[] parts = line.split(" ", 2);
            String cmd = parts[0].toUpperCase();
            String args = parts.length > 1 ? parts[1] : "";

            switch (cmd) {
                case "REGISTER": cmdRegister(args); break;
                case "LOGIN":    cmdLogin(args);    break;
                case "JOIN":     cmdJoin(args);     break;
                case "MSG":      cmdMsg(args);      break;
                case "DM":       cmdDm(args);       break;
                case "USERS":    cmdUsers();            break;
                case "HISTORY":  cmdHistory(args);     break;
                case "QUIT":     disconnect();          break;
                default:
                    send("ERR Неизвестная команда: " + cmd);
            }
        }

        // REGISTER <username> <password>
        private void cmdRegister(String args) {
            String[] p = args.split(" ", 2);
            if (p.length < 2) { send("ERR Формат: REGISTER <имя> <пароль>"); return; }
            String uname = p[0], pass = p[1];

            if (uname.contains(":") || uname.contains("|")) {
                send("ERR Имя не должно содержать : или |"); return;
            }
            if (users.containsKey(uname)) {
                send("ERR Пользователь уже существует"); return;
            }

            users.put(uname, pass);
            saveUsers();
            System.out.println("[SERVER] Зарегистрирован: " + uname);
            send("OK Зарегистрирован! Теперь выполните LOGIN " + uname + " " + pass);
        }

        // LOGIN <username> <password>
        private void cmdLogin(String args) {
            String[] p = args.split(" ", 2);
            if (p.length < 2) { send("ERR Формат: LOGIN <имя> <пароль>"); return; }
            String uname = p[0], pass = p[1];

            if (!users.containsKey(uname)) { send("ERR Пользователь не найден"); return; }
            if (!users.get(uname).equals(pass)) { send("ERR Неверный пароль"); return; }
            if (onlineClients.containsKey(uname)) { send("ERR Пользователь уже онлайн"); return; }

            username = uname;
            onlineClients.put(username, this);
            System.out.println("[SERVER] Вошёл: " + username);

            send("OK Добро пожаловать, " + username + "!");
            send("INFO Доступные каналы: general, tech, random");
            send("INFO Команды: JOIN <канал>  MSG <текст>  DM <кому> <текст>  USERS  QUIT");
        }

        // JOIN <channel>
        private void cmdJoin(String args) {
            if (!isAuthorized()) return;
            String channel = args.trim().toLowerCase();

            if (!channelMembers.containsKey(channel)) {
                send("ERR Нет такого канала. Доступны: general, tech, random"); return;
            }

            // Выходим из предыдущего канала
            if (currentChannel != null) {
                channelMembers.get(currentChannel).remove(username);
                broadcastToChannel(currentChannel,
                    "INFO " + username + " покинул канал #" + currentChannel, username);
            }

            currentChannel = channel;
            channelMembers.get(currentChannel).add(username);

            send("OK Вы вошли в #" + currentChannel);

            // Загружаем историю
            List<String> history = loadHistory("channel_" + currentChannel);
            if (!history.isEmpty()) {
                send("INFO === История за 3 дня (" + history.size() + " сообщений) ===");
                for (String msg : history) send(msg);
                send("INFO === Конец истории ===");
            }

            broadcastToChannel(currentChannel,
                "INFO " + username + " вошёл в канал #" + currentChannel, username);
            System.out.println("[SERVER] " + username + " -> #" + currentChannel);
        }

        // MSG <text>
        private void cmdMsg(String args) {
            if (!isAuthorized()) return;
            if (currentChannel == null) { send("ERR Сначала войдите в канал: JOIN <канал>"); return; }
            if (args.isEmpty()) { send("ERR Пустое сообщение"); return; }

            String logName = "channel_" + currentChannel;
            logMessage(logName, username, args);

            String line = "MSG " + username + " " + args;
            send(line); // отправляем себе тоже
            broadcastToChannel(currentChannel, line, username);

            System.out.println("[#" + currentChannel + "] " + username + ": " + args);
        }

        // DM <username> <text>
        private void cmdDm(String args) {
            if (!isAuthorized()) return;
            String[] p = args.split(" ", 2);
            if (p.length < 2) { send("ERR Формат: DM <имя> <текст>"); return; }
            String toUser = p[0], text = p[1];

            if (toUser.equals(username)) { send("ERR Нельзя писать самому себе"); return; }
            if (!users.containsKey(toUser)) { send("ERR Пользователь не найден"); return; }

            // Ключ для личного чата — сортированные имена через _
            String dmKey = "dm_" + (username.compareTo(toUser) < 0
                ? username + "_" + toUser
                : toUser + "_" + username);

            logMessage(dmKey, username, text);

            // Если получатель онлайн — отправляем
            ClientHandler recipient = onlineClients.get(toUser);
            if (recipient != null) {
                recipient.send("DM " + username + " " + text);
            }
            send("DM -> " + toUser + " " + text); // эхо отправителю

            System.out.println("[DM] " + username + " -> " + toUser + ": " + text);
        }

        // Специальная команда: открыть историю личного чата
        // Это вызывается при JOIN dm:<username>
        private void openDmHistory(String toUser) {
            if (!users.containsKey(toUser)) { send("ERR Пользователь не найден"); return; }

            String dmKey = "dm_" + (username.compareTo(toUser) < 0
                ? username + "_" + toUser
                : toUser + "_" + username);

            List<String> history = loadHistory(dmKey);
            if (history.isEmpty()) {
                send("INFO Нет истории переписки с " + toUser);
            } else {
                send("INFO === История с " + toUser + " за 3 дня ===");
                for (String msg : history) send(msg);
                send("INFO === Конец истории ===");
            }
        }

        // HISTORY <username> — история личного чата
        private void cmdHistory(String args) {
            if (!isAuthorized()) return;
            String toUser = args.trim();
            if (toUser.isEmpty()) { send("ERR Формат: HISTORY <имя>"); return; }
            openDmHistory(toUser);
        }

        // USERS
        private void cmdUsers() {
            if (!isAuthorized()) return;
            Set<String> online = onlineClients.keySet();
            send("INFO Онлайн (" + online.size() + "): " + String.join(", ", online));
        }

        private boolean isAuthorized() {
            if (username == null) {
                send("ERR Сначала выполните LOGIN или REGISTER");
                return false;
            }
            return true;
        }

        private void disconnect() {
            if (username != null) {
                onlineClients.remove(username);
                if (currentChannel != null) {
                    channelMembers.get(currentChannel).remove(username);
                    broadcastToChannel(currentChannel,
                        "INFO " + username + " отключился", username);
                }
                System.out.println("[SERVER] Отключился: " + username);
                username = null;
            }
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
