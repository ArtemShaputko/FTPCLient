package communication.client;

import download.Downloader;
import org.jline.reader.LineReader;
import status.Status;
import util.Response;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class Client implements AutoCloseable {
    protected final String serverIp;
    protected int serverPort;

    protected final PrintWriter consoleWriter;
    protected final LineReader consoleReader;

    protected final AtomicBoolean isConnected = new AtomicBoolean(false);
    protected Downloader downloader;

    public static final int TIMEOUT = 60_000;
    public static final String HEARTBEAT_REQUEST = "PING";
    public static final String HEARTBEAT_RESPONSE = "PONG";
    public static final String ACCEPT_MESSAGE = Status.SUCCESS.code() + " ACCEPT";
    public static final String END_MESSAGE = Status.END.code() + " END";

    public Client(String serverIp, int serverPort, PrintWriter consoleWriter, LineReader consoleReader) {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.consoleWriter = consoleWriter;
        this.consoleReader = consoleReader;
    }

    public abstract void connect() throws IOException;

    public abstract void writeMessage(String message) throws IOException;

    public Response sendAndWait(String message) throws IOException {
        writeMessage(message);
        StringBuilder responseBuilder = new StringBuilder();
        String line;
        try {
            while ((line = readLine()) != null) {
                if (END_MESSAGE.equals(line)) {
                    break;
                } else if (HEARTBEAT_REQUEST.equals(line)) {
                    writeMessage(HEARTBEAT_RESPONSE);
                } else if (!HEARTBEAT_RESPONSE.equals(line)) {
                    responseBuilder.append(line).append("\n");
                }
            }
        } catch (SocketTimeoutException e) {
            if (!responseBuilder.isEmpty()) {
                String responseMessage = responseBuilder.toString().trim();
                return new Response(Status.NO_END, responseMessage);
            }
            throw e;
        }
        return parseResponse(responseBuilder.toString().trim());
    }

    public static Response parseResponse(String message) {
        if (message.isEmpty()) {
            return new Response(Status.SUCCESS, "");
        }
        var responseArray = message.split(" ", 2);
        int code = Integer.parseInt(responseArray[0]);
        return new Response(Status.getStatusFromCode(code), responseArray[1]);
    }

    public abstract String readLine() throws IOException;

    public void checkHeartbeat() {
        try {
            if (isConnected.get()) {
                writeMessage(HEARTBEAT_REQUEST);
            }
        } catch (IOException e) {
            consoleWriter.println("Ошибка приёма сообщения: " + e.getMessage());
        }
    }

    public void downloadFile(String remotePath, String localPath, boolean resume) throws IOException {
        downloader.downloadFile(remotePath, localPath, resume);
    }

    public void uploadFile(String localPath, boolean resume) throws IOException {
        downloader.uploadFile(localPath, resume);
    }

    public abstract void closeConnection() throws Exception;
}
