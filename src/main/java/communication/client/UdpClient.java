package communication.client;

import org.jline.reader.LineReader;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class UdpClient extends Client{
    private DatagramSocket socket;
    private final byte[] buffer;
    public UdpClient(String ip, int port, PrintWriter consoleWriter, LineReader consoleReader, int bufferSize) {
        super(ip, port, consoleWriter, consoleReader);
        buffer = new byte[bufferSize];
    }

    @Override
    public void connect() throws IOException {
        socket = new DatagramSocket(11111);
        socket.setSoTimeout(TIMEOUT);
        var datagram = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(serverIp), serverPort);
        socket.send(datagram);
        isConnected.set(true);
    }

    @Override
    public void writeMessage(String message) throws IOException {
        var datagram = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(serverIp), serverPort);
        socket.send(datagram);
    }

    @Override
    public String readLine() throws IOException {
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);

        return new String(
                packet.getData(),
                0,
                packet.getLength(),
                StandardCharsets.UTF_8
        );
    }

    @Override
    public void closeConnection() {

    }
}
