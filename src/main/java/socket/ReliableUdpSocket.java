package socket;

import java.io.*;
import java.net.*;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ReliableUdpSocket implements AutoCloseable {
    private static final int RETRY_TIMEOUT_MS = 1000;
    private static final int MAX_RETRIES = 5;
    private static final int WINDOW_SIZE = 5;
    private static final int MAX_SEQUENCE = Integer.MAX_VALUE;

    private final DatagramSocket socket;
    private final ScheduledExecutorService scheduler;
    private final Map<Integer, PacketInfo> pendingPackets = new ConcurrentHashMap<>();
    private final BlockingQueue<Message> receivedQueue = new LinkedBlockingQueue<>();
    private final TreeMap<Integer, Message> orderedBuffer = new TreeMap<>();
    private final AtomicInteger expectedSeqNumber = new AtomicInteger(0);

    private int soTimeout = 0;
    private int packetSize = 65507;
    private final AtomicInteger nextSeqNumber = new AtomicInteger(0);
    private final AtomicInteger lastAcked = new AtomicInteger(-1);

    public void setSoTimeout(int soTimeout) {
        if (soTimeout > 0) {
            this.soTimeout = soTimeout;
        }
    }

    private static class PacketInfo {
        final byte[] data;
        final InetAddress address;
        final int port;
        int retries;
        long lastSentTime;

        PacketInfo(byte[] data, InetAddress address, int port) {
            this.data = data;
            this.address = address;
            this.port = port;
            this.retries = 0;
            this.lastSentTime = System.currentTimeMillis();
        }
    }

    private record Packet(int sequenceNumber, byte[] data, boolean isAck) {}

    public ReliableUdpSocket(int port, int packetSize) throws SocketException {
        if (packetSize < 1 || packetSize > 65507) {
            throw new IllegalArgumentException("Invalid packet size");
        }
        this.packetSize = packetSize;
        this.socket = new DatagramSocket(port);
        this.scheduler = Executors.newScheduledThreadPool(2);
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
                    processPacket(packet);
                } catch (Exception e) {
                    if (!socket.isClosed()) {
                        System.out.println("Receive error " + e);
                    }
                }
            }
        });
    }

    private void processPacket(DatagramPacket udpPacket) throws IOException {
        Packet packet = deserialize(Arrays.copyOf(udpPacket.getData(), udpPacket.getLength()));

        if (packet.isAck()) {
            handleAck(packet.sequenceNumber());
        } else {
            handleDataPacket(packet, udpPacket.getAddress(), udpPacket.getPort());
        }
    }

    private void handleDataPacket(Packet packet, InetAddress senderAddress, int senderPort) throws IOException {
        sendAck(packet.sequenceNumber(), senderAddress, senderPort);
        bufferAndOrderPackets(packet, senderAddress, senderPort);
    }

    private synchronized void bufferAndOrderPackets(Packet packet, InetAddress address, int port) {
        int seq = packet.sequenceNumber();
        byte[] data = Arrays.copyOf(packet.data(), packet.data().length);

        orderedBuffer.put(seq, new Message(data, address, port, data.length));

        while (!orderedBuffer.isEmpty()) {
            int firstKey = orderedBuffer.firstKey();
            if (firstKey == expectedSeqNumber.get()) {
                Message msg = orderedBuffer.remove(firstKey);
                receivedQueue.add(msg);
                expectedSeqNumber.set(incrementSequence(firstKey));
            } else if (firstKey > expectedSeqNumber.get()) {
                break;
            } else {
                orderedBuffer.remove(firstKey);
            }
        }
    }

    private int incrementSequence(int seq) {
        return (seq == MAX_SEQUENCE) ? 0 : seq + 1;
    }

    public Message receive(int timeout) throws SocketTimeoutException {
        try {
            var receive = receivedQueue.poll(timeout, TimeUnit.MILLISECONDS);
            if (receive == null) {
                throw new SocketTimeoutException("Socket timeout");
            }
            return receive;
        } catch (InterruptedException e) {
            throw new SocketTimeoutException("Socket timeout " + e.getMessage());
        }
    }

    public Message receive() throws SocketTimeoutException {
        try {
            if(soTimeout > 0) {
                return receive(soTimeout);
            }
            return receivedQueue.take();
        } catch (InterruptedException e) {
            throw new SocketTimeoutException("Socket timeout");
        }
    }

    private void startRetryChecker() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();

            synchronized (pendingPackets) {
                Iterator<Map.Entry<Integer, PacketInfo>> it = pendingPackets.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<Integer, PacketInfo> entry = it.next();
                    PacketInfo info = entry.getValue();

                    if (now - info.lastSentTime > RETRY_TIMEOUT_MS) {
                        if (info.retries >= MAX_RETRIES) {
                            it.remove();
                        } else {
                            resendPacket(entry.getKey(), info);
                        }
                    }
                }
            }
        }, RETRY_TIMEOUT_MS, RETRY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public void send(byte[] data, InetAddress address, int port) throws IOException {
        synchronized (pendingPackets) {
            while (nextSeqNumber.get() - lastAcked.get() > WINDOW_SIZE) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SocketTimeoutException("Interrupted during send " + e.getMessage());
                }
            }
            int currentSeq = nextSeqNumber.get();
            try {
                Packet packet = new Packet(currentSeq, data, false);
                byte[] bytes = serialize(packet);
                DatagramPacket dp = new DatagramPacket(bytes, bytes.length, address, port);

                pendingPackets.put(currentSeq, new PacketInfo(bytes, address, port));
                socket.send(dp);
                nextSeqNumber.set(incrementSequence(currentSeq));
            } catch (IOException e) {
                pendingPackets.remove(currentSeq); // Удаляем неудавшийся пакет
                throw e;
            }
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
            System.out.println("Failed to resend packet [seq="+ seqNumber+"]: "+ e.getMessage());

            if (info.retries >= MAX_RETRIES) {
                synchronized (pendingPackets) {
                    pendingPackets.remove(seqNumber);
                }
            }
        }
    }

    private void handleAck(int ackNumber) {
        synchronized (pendingPackets) {
            if (ackNumber > lastAcked.get()) {
                lastAcked.set(ackNumber);
                pendingPackets.keySet().removeIf(seq -> seq <= ackNumber);
            }
        }
    }

    private void sendAck(int seqNumber, InetAddress senderAddress, int senderPort) throws IOException {
        // Создаем пакет с пустым data
        Packet ack = new Packet(seqNumber, new byte[0], true);
        byte[] bytes = serialize(ack);
        DatagramPacket dp = new DatagramPacket(bytes, bytes.length, senderAddress, senderPort);
        socket.send(dp);
    }

    private byte[] serialize(Packet packet) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(13 + packet.data().length);
        buffer.putInt(packet.sequenceNumber());
        buffer.put(packet.isAck() ? (byte)1 : (byte)0);
        buffer.putInt(packet.data().length);
        buffer.put(packet.data());
        return buffer.array();
    }

    private Packet deserialize(byte[] rawData) throws IOException {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(rawData);
            int sequenceNumber = buffer.getInt();
            boolean isAck = buffer.get() == 1;
            int dataLength = buffer.getInt();

            if (dataLength < 0 || dataLength > buffer.remaining()) {
                throw new IOException("Invalid packet length");
            }

            byte[] data = new byte[dataLength];
            buffer.get(data);
            return new Packet(sequenceNumber, data, isAck);
        } catch (BufferUnderflowException e) {
            throw new IOException("Malformed packet", e);
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