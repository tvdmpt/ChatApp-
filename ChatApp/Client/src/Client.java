import java.io.*;
import java.net.*;
import java.util.Scanner;


public class Client {

    private static final String SERVER_ADDRESS = "localhost";
    private static final int    SERVER_PORT    = 12345;

    // Состояние клиента
    private static volatile boolean loggedIn = false;
    private static volatile String  currentChannel = null;

    public static void main(String[] args) {
        System.out.println("=== Терминальный мессенджер ===");
        System.out.println("Подключаемся к " + SERVER_ADDRESS + ":" + SERVER_PORT + "...");

        try (Socket socket = new Socket(SERVER_ADDRESS, SERVER_PORT)) {
            System.out.println("Подключено!\n");

            PrintWriter out = new PrintWriter(
                new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
            BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));

            // Поток для приёма сообщений от сервера
            Thread receiver = new Thread(() -> {
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        displayMessage(line);
                    }
                } catch (IOException e) {
                    System.out.println("\n[!] Соединение с сервером разорвано.");
                }
            });
            receiver.setDaemon(true);
            receiver.start();

            // Главный цикл ввода
            printHelp();
            Scanner scanner = new Scanner(System.in);

            while (scanner.hasNextLine()) {
                String input = scanner.nextLine().trim();
                if (input.isEmpty()) continue;

                // Обработка команд с /
                if (input.startsWith("/")) {
                    String[] parts = input.substring(1).split(" ", 3);
                    String cmd = parts[0].toLowerCase();

                    switch (cmd) {
                        case "register":
                            if (parts.length < 3) { help("REGISTER <имя> <пароль>"); break; }
                            out.println("REGISTER " + parts[1] + " " + parts[2]);
                            break;

                        case "login":
                            if (parts.length < 3) { help("LOGIN <имя> <пароль>"); break; }
                            out.println("LOGIN " + parts[1] + " " + parts[2]);
                            loggedIn = true;
                            break;

                        case "join":
                            if (parts.length < 2) { help("JOIN <канал>"); break; }
                            out.println("JOIN " + parts[1]);
                            currentChannel = parts[1];
                            break;

                        case "dm":
                            // /dm <кому> <текст>
                            String[] dmParts = input.substring(4).trim().split(" ", 2);
                            if (dmParts.length < 2) { help("DM <имя> <текст>"); break; }
                            out.println("DM " + dmParts[0] + " " + dmParts[1]);
                            break;

                        case "users":
                            out.println("USERS");
                            break;

                        case "history":
                            // /history <имя> — история личного чата
                            if (parts.length < 2) { help("HISTORY <имя>"); break; }
                            out.println("HISTORY " + parts[1]);
                            break;

                        case "quit":
                        case "exit":
                            out.println("QUIT");
                            System.out.println("До свидания!");
                            return;

                        case "help":
                            printHelp();
                            break;

                        default:
                            System.out.println("[!] Неизвестная команда. Введите /help");
                    }
                } else {
                    // Обычный текст → отправить в канал
                    if (!loggedIn) {
                        System.out.println("[!] Сначала войдите: /login <имя> <пароль>  или  /register <имя> <пароль>");
                    } else if (currentChannel == null) {
                        System.out.println("[!] Сначала выберите канал: /join general");
                    } else {
                        out.println("MSG " + input);
                    }
                }
            }

        } catch (ConnectException e) {
            System.out.println("[!] Не удалось подключиться. Убедитесь, что сервер запущен.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Красиво отображаем сообщения от сервера
    private static void displayMessage(String line) {
        if (line.startsWith("OK ")) {
            System.out.println("[✓] " + line.substring(3));
        } else if (line.startsWith("ERR ")) {
            System.out.println("[✗] " + line.substring(4));
        } else if (line.startsWith("INFO ")) {
            System.out.println("[*] " + line.substring(5));
        } else if (line.startsWith("MSG ")) {
            // MSG <от> <текст>
            String rest = line.substring(4);
            int space = rest.indexOf(' ');
            if (space > 0) {
                String from = rest.substring(0, space);
                String text = rest.substring(space + 1);
                System.out.println("[Чат] " + from + ": " + text);
            }
        } else if (line.startsWith("DM ")) {
            // DM <от> <текст>  или  DM -> <кому> <текст>
            String rest = line.substring(3);
            if (rest.startsWith("-> ")) {
                // это эхо отправителю
                String[] p = rest.substring(3).split(" ", 2);
                System.out.println("[ЛС -> " + p[0] + "] " + (p.length > 1 ? p[1] : ""));
            } else {
                int space = rest.indexOf(' ');
                if (space > 0) {
                    String from = rest.substring(0, space);
                    String text = rest.substring(space + 1);
                    System.out.println("[ЛС от " + from + "] " + text);
                }
            }
        } else {
            System.out.println(line);
        }
    }

    private static void printHelp() {
        System.out.println();
        System.out.println("┌─── Команды ──────────────────────────────────────┐");
        System.out.println("│ /register <имя> <пароль>  — регистрация          │");
        System.out.println("│ /login <имя> <пароль>     — вход                 │");
        System.out.println("│ /join <канал>             — войти в канал         │");
        System.out.println("│   Каналы: general, tech, random                  │");
        System.out.println("│ <текст>                   — сообщение в канал     │");
        System.out.println("│ /dm <имя> <текст>         — личное сообщение      │");
        System.out.println("│ /users                    — список онлайн         │");
        System.out.println("│ /quit                     — выход                 │");
        System.out.println("└──────────────────────────────────────────────────┘");
        System.out.println();
    }

    private static void help(String usage) {
        System.out.println("[!] Использование: /" + usage);
    }
}
