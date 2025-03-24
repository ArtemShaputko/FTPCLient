package communication.client;

import org.jline.reader.LineReader;
import status.Status;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class UdpClient extends Client {
    private DatagramSocket socket;
    int bufferSize;
    public static final String CONNECT_REQUEST =  Status.CONNECT.code() + " CONNECT";


    public UdpClient(String ip, int port, PrintWriter consoleWriter, LineReader consoleReader, int bufferSize) {
        super(ip, port, consoleWriter, consoleReader);
        this.bufferSize = bufferSize;
    }

    @Override
    public void connect() throws IOException {
        socket = new DatagramSocket(11111);
        socket.setSoTimeout(TIMEOUT);
        establishConnection();
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
        var packet = (message + "\n").getBytes(StandardCharsets.UTF_8);
        var datagram = new DatagramPacket(packet, packet.length, InetAddress.getByName(serverIp), serverPort);
        socket.send(datagram);
    }

    @Override
    public String readLine() throws IOException {
        var buffer = new byte[bufferSize];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        var str = new String(
                packet.getData(),
                0,
                packet.getLength(),
                StandardCharsets.UTF_8
        );
        return str;
    }

    @Override
    public void closeConnection() {

    }
}
