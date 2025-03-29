package communication;

import communication.client.Client;
import communication.client.TcpClient;
import communication.client.UdpClient;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import status.Status;
import util.Command;
import util.Context;
import util.Response;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class CommunicationManager implements AutoCloseable{
    private Context currentContext = Context.MAIN;
    private String prompt = "> ";
    private volatile boolean toStop = false;
    private Client client;
    private final int initPort;
    private String serverAddress;
    private int port;
    private final static String fileDir = "trash/";
    private PrintWriter writer;
    private LineReader reader;
    private Command lastDownload = null;
    private Command lastUpload = null;

    public CommunicationManager(int port) {
        this.initPort = port;
    }

    public void run() throws Exception {
        try (Terminal terminal = TerminalBuilder.builder()
                .system(true).build()) {
            writer = terminal.writer();
            reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(new DefaultParser())
                    .completer(new CommandCompleter())
                    .history(new DefaultHistory())
                    .build();
            while (!toStop) {
                String line;
                try (var scheduler = Executors.newScheduledThreadPool(1)) {
                    if (client != null) {
                        scheduler.scheduleAtFixedRate(
                                () -> client.checkHeartbeat(),
                                Client.TIMEOUT / 2,
                                Client.TIMEOUT / 2,
                                TimeUnit.MILLISECONDS
                        );
                    }

                    line = reader.readLine(prompt);
                    scheduler.shutdown();
                }
                if (line == null) {
                    return;
                }
                if (!line.isEmpty()) {
                    ParsedLine parsed = reader.getParser().parse(line, 0);
                    processLine(line, parsed);
                }
            }
        }
    }

    private void processLine(String line, ParsedLine parsedLine) throws IOException {
        List<String> words = parsedLine.words();
        if (words.isEmpty()) return;

        try {
            switch (currentContext) {
                case MAIN -> handleMainContext(words);
                case SERVER -> handleServerContext(line, words);
            }
        } catch (NumberFormatException e) {
            writer.println("Ошибка обрабтки строки: " + e.getMessage());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            writer.println("Неизвестная ошибка: " + e.getMessage());
        }
    }

    private void handleMainContext(List<String> words) throws Exception {
        switch (words.getFirst().toLowerCase()) {
            case "exit" -> toStop = true;
            case "help" -> showMainHelp();
            case "connect" -> connectToServer(words);
            default -> writer.println("Неизвестная команда");
        }
    }

    private void handleServerContext(String line, List<String> words) throws Exception {
        switch (words.getFirst().toLowerCase()) {
            case "close" -> {
                try {
                    String response = sendCommand(line).message();
                    if (response != null && !response.isEmpty()) {
                        writer.println(response);
                    }
                } finally {
                    client.closeConnection();
                    currentContext = Context.MAIN;
                    prompt = "> ";
                }
            }
            case "download" -> handleDownload(line, words);
            case "upload" -> handleUpload(line, words);
            case "continue" -> handleContinue(words);
            default -> {
                String response = sendCommand(line).message();
                if (response != null && !response.isEmpty()) {
                    writer.println(response);
                }
            }
        }
    }

    private void handleContinue(List<String> words) throws IOException {
        if (words.size() < 2) {
            writer.println("Применение: continue <upload/download>");
            return;
        }
        if ("download".equalsIgnoreCase(words.get(1))) {
            if (lastDownload == null || !lastDownload.interrupted()) {
                writer.println("Нет информации о прерванных предыдущих загрузках");
                return;
            }
            String line = lastDownload.line() + " continue";
            handleDownload(line, lastDownload.words());
        } else if("upload".equalsIgnoreCase(words.get(1))) {
            if (lastUpload == null || !lastUpload.interrupted()) {
                writer.println("Нет информации о прерванных предыдущих выгрузках");
                return;
            }
            String line = lastUpload.line() + " continue";
            handleUpload(line, lastUpload.words());
        }
    }

    private void connectToServer(List<String> words) throws Exception {
        if (words.size() < 2) {
            writer.println("Применение: connect <host> [port] [UDP]");
            return;
        }

        String ip = words.get(1);
        boolean isUDP = words.getLast().equalsIgnoreCase("udp");
        int port = words.size() > 2 && !isUDP ? Integer.parseInt(words.get(2)) : this.initPort;
        try {
            if(isUDP) {
                client = new UdpClient(ip, port, writer, reader);
            } else {
                client = new TcpClient(ip, port, writer, reader);
            }

            client.connect();
            currentContext = Context.SERVER;
            prompt = ip + "> ";
            this.serverAddress = ip;
            this.port = port;
        } catch (Exception e) {
            writer.println("Невозможно подключиться к серверу: " + e.getMessage());
            if (client != null) {
                client.close();
            }
        }
    }

    private void handleDownload(String command, List<String> words) throws IOException {
        if (words.size() < 3) {
            writer.println("Применение: download <remote> <local> [continue]");
            return;
        }
        String response = sendCommand(command).message();
        String localFileName = fileDir + words.get(2);
        if (response != null && !response.isEmpty()) {
            writer.println(response);
        }
        boolean resume = words.size() > 3 && "continue".equalsIgnoreCase(words.get(3));
        try {
            String line = client.readLine();
            if (Client.ACCEPT_MESSAGE.equals(line)) {
                lastDownload = new Command(serverAddress, port, command, words);
                client.downloadFile(words.get(1), localFileName, resume);
            } else {
                writer.println(line);
            }
        } catch (SocketException e) {
            lastDownload.setInterrupted(true);
            writer.println("Не удалось загрузить файл: " + e.getMessage());
            try {
                client.closeConnection();
            } catch (Exception e1) {
                writer.println("Ошибка при закрытии: " + e1.getMessage());
            }
            currentContext = Context.MAIN;
            prompt = "> ";
        }
    }

    private void handleUpload(String command, List<String> words) {
        if (words.size() < 3) {
            writer.println("Применение: upload <remote> <local> [continue]");
            return;
        }
        String response = sendCommand(command).message();
        String localFileName = fileDir + words.get(1);
        if (response != null && !response.isEmpty()) {
            writer.println(response);
        }
        boolean resume = words.size() > 3 && "continue".equalsIgnoreCase(words.get(3));
        try {
            String line = client.readLine();
            if (Client.ACCEPT_MESSAGE.equals(line)) {
                lastUpload = new Command(serverAddress, port, command, words);
                client.uploadFile(localFileName, resume);
            } else {
                writer.println(line);
            }
        } catch (Exception e) {
            if (lastUpload != null) {
                lastUpload.setInterrupted(true);
            }
            writer.println("Не удалось выгрузить файл: " + e.getMessage());
            try {
                client.closeConnection();
            } catch (Exception e1) {
                writer.println("Ошибка при закрытии: " + e1.getMessage());
            }
            currentContext = Context.MAIN;
            prompt = "> ";
        }
    }

    private void showMainHelp() {
        writer.println("Доступные команды:");
        writer.println("\tconnect <host> [port] [UDP] - Подключиться к серверу");
        writer.println("\texit                  - Выйти из программы");
        writer.println("\thelp                  - Окно помощи");
    }

    @Override
    public void close() throws Exception {
        if(client!=null) {
            client.close();
        }
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

    private Response sendCommand(String command) {
        var response = new Response(Status.ERROR, "Неизвестная ошибка");
        var resume = true;
        while (resume) {
            try {
                response = client.sendAndWait(command);
                resume = false;
            } catch (SocketTimeoutException _) {
                resume = handleTimeout("Нет ответа от сервера, отправить команду снова?");
                if (!resume) {
                    break;
                }
            } catch (IOException e) {
                response = new Response(Status.ERROR, e.getMessage());
                break;
            }
        }
        return response;
    }

    private boolean handleTimeout(String message) {
        writer.println(message);
        return Objects.equals(reader.readLine("(y/n) "), "y");
    }

}
