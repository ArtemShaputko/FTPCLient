package client;

import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

public class MainContextManager {
    private Client client;
    private boolean toStop;
    private final int port;

    public MainContextManager(Client client, int port) {
        this.client = client;
        this.port = port;
    }

/*    public void start() throws IOException {
        String line;
        try (Terminal terminal = TerminalBuilder.builder()
                .system(true).build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .parser(new DefaultParser())
                    .history(new DefaultHistory())
                    .build();
            while (!toStop) {
                line = reader.readLine("> ");
                if(line.isEmpty()) {
                    continue;
                }
                var parsedLine = reader.getParser().parse(line, 0);
                handleMainContext(parsedLine.words(), terminal.writer());
            }
        }
    }*/
/*    private void handleMainContext(List<String> words, PrintWriter writer) {
        switch (words.getFirst().toLowerCase()) {
            case "exit" -> toStop = true;
            case "help" -> showMainHelp(writer);
            case "connect" -> connectToServer(words, writer);
            default -> writer.println("Unknown command");
        }
    }*/
    private void showMainHelp(PrintWriter writer) {
        writer.println("Available commands:");
        writer.println("connect <host> [port] - Connect to server");
        writer.println("exit                   - Exit program");
        writer.println("help                   - Show this help");
    }
/*    private void connectToServer(List<String> words, PrintWriter writer) {
        if (words.size() < 2) {
            writer.println("Usage: connect <host> [port]");
            return;
        }

        String ip = words.get(1);
        int port = words.size() > 2 ? Integer.parseInt(words.get(2)) : this.port;

        client = new Client(ip, port, writer);
        client.connect();
    }*/
}
