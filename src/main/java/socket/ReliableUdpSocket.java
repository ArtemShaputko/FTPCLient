package socket;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;

public class ReliableUdpSocket implements AutoCloseable{
    private static final int TIMEOUT_MS = 1000;
    private static final int MAX_RETRIES = 5;
    private static final int WINDOW_SIZE = 5;

    private final DatagramSocket socket;
    private final ScheduledExecutorService scheduler;
    private final Map<Integer, PacketInfo> pendingPackets;
    private final BlockingQueue<Message> receivedQueue = new LinkedBlockingQueue<>();

    private int packetSize = 65507;
    private int nextSeqNumber = 0;
    private int lastAcked = -1;


    public int getPacketSize() {
        return packetSize;
    }

    public void setPacketSize(int packetSize) throws IOException{
        if(packetSize > 65507 || packetSize < 0) {
            throw new IOException("Packet size must be between 0 and 65507");
        }
        this.packetSize = packetSize;
    }

    // Структура для хранения информации о пакете
    private static class PacketInfo {
        byte[] data;
        int retries;
        long lastSentTime;
        InetAddress address;
        int port;

        PacketInfo(byte[] data, InetAddress address, int port) {
            this.data = data;
            this.retries = 0;
            this.lastSentTime = System.currentTimeMillis();
            this.address = address;
            this.port = port;
        }
    }

    // Структура пакета с последовательным номером и флагом ACK
    private static class Packet implements Serializable {
        int sequenceNumber;
        byte[] data;
        boolean isAck;
    }

    public ReliableUdpSocket(int port, int packetSize) throws SocketException {
        this.packetSize = packetSize;
        this.socket = new DatagramSocket(port);
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.pendingPackets = new ConcurrentHashMap<>();

        startReceiverThread();
        startRetryChecker();
    }

    public ReliableUdpSocket(int port) throws SocketException {
        this(port, 65507);
    }

    private void startReceiverThread() {
        scheduler.execute(() -> {
            byte[] buffer = new byte[packetSize];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            while (!scheduler.isShutdown()) {
                try {
                    socket.receive(packet);
                    Packet received = deserialize(
                            Arrays.copyOf(packet.getData(), packet.getLength()) // Фиксим длину
                    );

                    if (received.isAck) {
                        handleAck(received.sequenceNumber);
                    } else {
                        sendAck(received.sequenceNumber, packet.getAddress(), packet.getPort());

                        // Сохраняем только реальные данные и длину
                        receivedQueue.put(new Message(
                                Arrays.copyOf(received.data, received.data.length), // Копируем актуальные данные
                                packet.getAddress(),
                                packet.getPort(),
                                received.data.length
                        ));
                    }
                } catch (Exception e) {
                    if (!socket.isClosed()) {
                        System.out.println("Receive error "+ e);
                    }
                }
            }
        });
    }

    public Message receive(int timeout) throws InterruptedException {
        return receivedQueue.poll(timeout, TimeUnit.MILLISECONDS);
    }

    public Message receive() throws InterruptedException {
        return receivedQueue.take();
    }

    private void startRetryChecker() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();

            synchronized (pendingPackets) {
                Iterator<Map.Entry<Integer, PacketInfo>> it = pendingPackets.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, PacketInfo> entry = it.next();
                    PacketInfo info = entry.getValue();

                    if (now - info.lastSentTime > TIMEOUT_MS) {
                        if (info.retries >= MAX_RETRIES) {
                            it.remove();
                        } else {
                            resendPacket(entry.getKey(), info);
                        }
                    }
                }
            }
        }, TIMEOUT_MS, TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public void send(byte[] data, InetAddress address, int port) throws IOException {
        synchronized (pendingPackets) {
            while (nextSeqNumber - lastAcked > WINDOW_SIZE) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            Packet packet = new Packet();
            packet.sequenceNumber = nextSeqNumber;
            packet.data = data;
            packet.isAck = false;

            byte[] bytes = serialize(packet);
            DatagramPacket dp = new DatagramPacket(bytes, bytes.length, address, port);

            pendingPackets.put(nextSeqNumber, new PacketInfo(bytes, address, port));
            socket.send(dp);
            nextSeqNumber++;
        }
    }

    private void resendPacket(int seqNumber, PacketInfo info) {
        try {
            DatagramPacket dp = new DatagramPacket(
                    info.data,
                    info.data.length,
                    info.address,  // Используем сохраненный адрес
                    info.port      // Используем сохраненный порт
            );

            socket.send(dp);
            info.retries++;
            info.lastSentTime = System.currentTimeMillis();

        } catch (IOException e) {
            System.out.println("Failed to resend packet [seq=" + seqNumber+"]: " + e.getMessage());

            // При критической ошибке можно удалить пакет из очереди
            if (info.retries >= MAX_RETRIES) {
                synchronized (pendingPackets) {
                    pendingPackets.remove(seqNumber);
                }
            }
        }
    }

    private void handleAck(int ackNumber) {
        synchronized (pendingPackets) {
            if (ackNumber > lastAcked) {
                lastAcked = ackNumber;
                pendingPackets.keySet().removeIf(seq -> seq <= ackNumber);
            }
        }
    }

    private void sendAck(int seqNumber, InetAddress senderAddress, int senderPort) throws IOException {
        Packet ack = new Packet();
        ack.sequenceNumber = seqNumber;
        ack.isAck = true;

        byte[] bytes = serialize(ack);
        DatagramPacket dp = new DatagramPacket(bytes, bytes.length, senderAddress, senderPort);
        socket.send(dp);
    }

    private byte[] serialize(Packet packet) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(packet);
            return bos.toByteArray();
        }
    }

    private Packet deserialize(byte[] data) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return (Packet) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Deserialization error", e);
        }
    }

    public void send(String message, InetAddress address, int port) throws IOException {
        send(message, StandardCharsets.UTF_8.name(), address, port);
    }

    public void send(String message, String charsetName, InetAddress address, int port) throws IOException {
        try {
            byte[] data = message.getBytes(charsetName);
            send(data, address, port);
        } catch (UnsupportedEncodingException e) {
            throw new IOException("Unsupported charset: " + charsetName, e);
        }
    }
    @Override
    public void close() {
        scheduler.shutdown();
        socket.close();
    }
}