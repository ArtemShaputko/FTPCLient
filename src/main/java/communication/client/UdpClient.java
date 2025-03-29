package communication.client;

import download.UdpDownloader;
import org.jline.reader.LineReader;
import socket.ReliableUdpSocket;
import status.Status;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.SocketException;

public class UdpClient extends Client {
    private ReliableUdpSocket socket;
    public static final String CONNECT_REQUEST = Status.CONNECT.code() + " CONNECT";


    public UdpClient(String ip, int port, PrintWriter consoleWriter, LineReader consoleReader) {
        super(ip, port, consoleWriter, consoleReader);
    }

    @Override
    public void connect() throws IOException {
        socket = new ReliableUdpSocket(true);
        socket.setSoTimeout(Client.TIMEOUT);
        socket.send(CONNECT_REQUEST, InetAddress.getByName(serverIp), serverPort);
        var accept = socket.receive(30_000);
        if(!ACCEPT_MESSAGE.equals(accept.text())) {
            throw new SocketException("Ошибка на сервере: " + accept.text());
        }
        var message = socket.receive(30_000);
        this.serverPort = Integer.parseInt(message.text());
        connectSecondStep();
        isConnected.set(true);
        downloader = new UdpDownloader(socket,
                consoleWriter,
                consoleReader,
                InetAddress.getByName(serverIp),
                serverPort,
                65507 - 9,
                15_000);
    }

    private void connectSecondStep() throws IOException {
        socket.stopServices();
        socket.startServices();
        System.out.println(serverPort);
        socket.send(CONNECT_REQUEST, InetAddress.getByName(serverIp), serverPort);
        if(!ACCEPT_MESSAGE.equals(socket.receive(30_000).text())) {
            throw new SocketException("Ошибка на сервере");
        }
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
