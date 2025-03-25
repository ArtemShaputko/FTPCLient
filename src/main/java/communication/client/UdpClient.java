package communication.client;

import org.jline.reader.LineReader;
import socket.ReliableUdpSocket;
import status.Status;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class UdpClient extends Client {
    private ReliableUdpSocket socket;
    int bufferSize;
    public static final String CONNECT_REQUEST =  Status.CONNECT.code() + " CONNECT";


    public UdpClient(String ip, int port, PrintWriter consoleWriter, LineReader consoleReader, int bufferSize) {
        super(ip, port, consoleWriter, consoleReader);
        this.bufferSize = bufferSize;
    }

    @Override
    public void connect() throws IOException {
        socket = new ReliableUdpSocket(11111, bufferSize);
        establishConnection();
        socket.send("", InetAddress.getByName(serverIp), serverPort);
        isConnected.set(true);
    }

    private void establishConnection() throws IOException {
        int numTry = 0;
        while (numTry < 3) {
            try {
                do {
                    writeMessage(CONNECT_REQUEST);
                    numTry++;
                } while(!Client.ACCEPT_MESSAGE.equals(readLine()));
                readLine();
                writeMessage(Client.ACCEPT_MESSAGE);
                break;
            } catch (SocketTimeoutException e) {
                consoleWriter.println("Нет ответа от сервера, подключаюсь ещё раз");
                numTry++;
            }
        }
        if(numTry > 2) {
            throw new SocketException("Нет ответа от сервера");
        }
    }

    @Override
    public void writeMessage(String message) throws IOException {
        socket.send(message, InetAddress.getByName(serverIp), serverPort);
    }

    @Override
    public String readLine() throws IOException {
        try {
            return socket.receive().text();
        } catch (InterruptedException e) {
            throw new SocketTimeoutException(e.getMessage());
        }
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
