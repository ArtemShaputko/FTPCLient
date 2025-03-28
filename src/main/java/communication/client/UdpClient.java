package communication.client;

import download.UdpDownloader;
import org.jline.reader.LineReader;
import socket.ReliableUdpSocket;
import status.Status;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;

public class UdpClient extends Client {
    private ReliableUdpSocket socket;
    public static final String CONNECT_REQUEST = Status.CONNECT.code() + " CONNECT";


    public UdpClient(String ip, int port, PrintWriter consoleWriter, LineReader consoleReader) {
        super(ip, port, consoleWriter, consoleReader);
    }

    @Override
    public void connect() throws IOException {
        socket = new ReliableUdpSocket();
        socket.setSoTimeout(Client.TIMEOUT);
        socket.send(CONNECT_REQUEST, InetAddress.getByName(serverIp), serverPort);
        isConnected.set(true);
        downloader = new UdpDownloader(socket,
                consoleWriter,
                consoleReader,
                InetAddress.getByName(serverIp),
                serverPort,
                65507 - 9,
                180_000);
    }

    @Override
    public void writeMessage(String message) throws IOException {
        socket.send(message, InetAddress.getByName(serverIp), serverPort);
    }

    @Override
    public String readLine() throws IOException {
        return socket.receive().text();
    }

    @Override
    public void closeConnection() {
        isConnected.set(false);
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public void close() {
        closeConnection();
    }
}
