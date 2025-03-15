package util;

import java.util.List;

public class Command {
    private final String serverIp;
    private final int port;
    private final String line;
    private final List<String> words;
    private boolean interrupted;

    public Command(String serverIp, int port, String line, List<String> words) {
        this.serverIp = serverIp;
        this.port = port;
        this.line = line;
        this.words = words;
    }

    public boolean interrupted() {
        return interrupted;
    }

    public String line() {
        return line;
    }

    public List<String> words() {
        return words;
    }

    public int port() {
        return port;
    }

    public String serverIp() {
        return serverIp;
    }

    public void setInterrupted(boolean interrupted) {
        this.interrupted = interrupted;
    }
}
