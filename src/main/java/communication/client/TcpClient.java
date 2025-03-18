package communication.client;

import download.Downloader;
import org.jline.reader.LineReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;

public class TcpClient extends Client {
    private Socket socket;
    private PrintWriter socketWriter;
    private BufferedReader socketReader;
    public TcpClient(String ip, int port, PrintWriter consoleWriter, LineReader consoleReader) {
        super(ip, port, consoleWriter, consoleReader);
    }

    @Override
    public void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(serverIp, serverPort), TIMEOUT);
        socket.setSoTimeout(TIMEOUT);
        socket.setKeepAlive(true);
        isConnected.set(true);
        socketWriter = new PrintWriter(socket.getOutputStream(), true);
        socketReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        downloader = new Downloader(socket, consoleWriter, consoleReader);
    }

    @Override
    public void writeMessage(String message) throws IOException {
        if (socketWriter != null) {
            socketWriter.println(message);
        }
    }
    @Override
    public String readLine() throws IOException {
        return socketReader.readLine();
    }
    @Override
    public void closeConnection() {
        isConnected.set(false);
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            consoleWriter.println("Ошибка закрытия соединения: " + e.getMessage());
        }
    }

    @Override
    public void close() throws Exception {
        closeConnection();
    }
}
