package client;

import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import util.Context;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class CommunicationManager {
    private Context currentContext = Context.MAIN;
    private String prompt = "> ";
    private volatile boolean toStop = false;
    private Client client;
    private final int port;

    public CommunicationManager(int port) {
        this.port = port;
    }

    public void run() throws Exception {
        try (Terminal terminal = TerminalBuilder.builder()
                .system(true).build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(new DefaultParser())
                    .completer(new CommandCompleter())
                    .history(new DefaultHistory())
                    .build();
            while (!toStop) {
                try (var scheduler = Executors.newScheduledThreadPool(1)) {
                    if (client != null) {
                        scheduler.scheduleAtFixedRate(
                                () -> client.checkHeartbeat(),
                                0, Client.TIMEOUT/2, TimeUnit.MILLISECONDS
                        );
                    }

                    String line = reader.readLine(prompt);
                    scheduler.shutdown();
                    if (line == null) {
                        return;
                    }
                    if (!line.isEmpty()) {
                        ParsedLine parsed = reader.getParser().parse(line, 0);
                        processLine(line, parsed, terminal.writer());
                    }
                }
            }
        }
    }

    private void processLine(String line, ParsedLine parsedLine, PrintWriter writer) {
        List<String> words = parsedLine.words();
        if (words.isEmpty()) return;

        try {
            switch (currentContext) {
                case MAIN -> handleMainContext(words, writer);
                case SERVER -> handleServerContext(line, words, writer);
            }
        } catch (Exception e) {
            writer.println("Ошибка: " + e.getMessage());
        }
    }

    private void handleMainContext(List<String> words, PrintWriter writer) {
        switch (words.getFirst().toLowerCase()) {
            case "exit" -> toStop = true;
            case "help" -> showMainHelp(writer);
            case "connect" -> connectToServer(words, writer);
            default -> writer.println("Неизвестная команда");
        }
    }

    private void handleServerContext(String line, List<String> words, PrintWriter writer) {
        String response = client.sendAndWait(line);
        if (response != null && !response.isEmpty()) {
            writer.println(response);
        }

        switch (words.getFirst().toLowerCase()) {
            case "close" -> {
                client.closeConnection();
                currentContext = Context.MAIN;
                prompt = "> ";
            }
            case "download" -> handleDownload(words, writer);
            default -> {
            }
        }
    }

    private void connectToServer(List<String> words, PrintWriter writer) {
        if (words.size() < 2) {
            writer.println("Применение: connect <host> [port]");
            return;
        }

        String ip = words.get(1);
        int port = words.size() > 2 ? Integer.parseInt(words.get(2)) : this.port;
        try {
            client = new Client(ip, port, writer);
            client.connect();
            currentContext = Context.SERVER;
            prompt = ip + "> ";
        } catch (IOException e) {
            writer.println("Невозможно подключиться к серверу: " + e.getMessage());
        }
    }

    private void handleDownload(List<String> words, PrintWriter writer) {
        if (words.size() < 3) {
            writer.println("Применение: download <remote> <local> [continue]");
            return;
        }
        boolean resume = words.size() > 3 && "continue".equalsIgnoreCase(words.get(3));
        try {
            String line = client.readline();
            if ("Accept".equals(line)) {
                client.downloadFile(words.get(1), words.get(2), resume);
            } else {
                writer.println(line);
            }
        } catch (IOException e) {
            writer.println("Download failed: " + e.getMessage());
        }
    }

    private void showMainHelp(PrintWriter writer) {
        writer.println("Доступные команды:");
        writer.println("connect <host> [port] - Подключиться к серверу");
        writer.println("exit                  - Выйти из программы");
        writer.println("help                  - Окно помощи");
    }

    static class CommandCompleter implements Completer {
        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            List<String> words = line.words();
            if (words.isEmpty()) {
                candidates.addAll(List.of(
                        new Candidate("connect"),
                        new Candidate("exit"),
                        new Candidate("help")
                ));
            }
        }
    }
}
