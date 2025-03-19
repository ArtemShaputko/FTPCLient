package communication.client;

import download.UdpDownloader;
import org.jline.reader.LineReader;
import socket.ReliableUdpSocket;
import status.Status;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.*;

public class UdpClient extends Client {
    private ReliableUdpSocket socket;
    int bufferSize;
    public static final String CONNECT_REQUEST = Status.CONNECT.code() + " CONNECT";


    public UdpClient(String ip, int port, PrintWriter consoleWriter, LineReader consoleReader, int bufferSize) {
        super(ip, port, consoleWriter, consoleReader);
        this.bufferSize = bufferSize;
    }

    @Override
    public void connect() throws IOException {
        socket = new ReliableUdpSocket(11111, bufferSize);
        socket.setSoTimeout(Client.TIMEOUT);
        socket.send(CONNECT_REQUEST, InetAddress.getByName(serverIp), serverPort);
        isConnected.set(true);
        downloader = new UdpDownloader(socket,consoleWriter,consoleReader,InetAddress.getByName(serverIp), serverPort);
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
    public void close() throws Exception {
        closeConnection();
    }
}
