package socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class ReliableUdpSocket implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ReliableUdpSocket.class);
    private static final int BASE_RETRY_TIMEOUT_MS = 1000;
    private static final int WINDOW_SIZE = 5;
    private static final int MAX_SEQUENCE = Integer.MAX_VALUE;

    private final DatagramSocket socket;
    private final ScheduledExecutorService scheduler;
    private final Map<Integer, PacketInfo> pendingPackets = new ConcurrentHashMap<>();
    private final BlockingQueue<Message> receivedQueue = new LinkedBlockingQueue<>();
    private final TreeMap<Integer, Message> orderedBuffer = new TreeMap<>();
    private final ConcurrentSkipListSet<Integer> receivedAcks = new ConcurrentSkipListSet<>();
    private final BlockingQueue<SendTask> sendQueue = new LinkedBlockingQueue<>();
    private final AtomicInteger windowAvailable = new AtomicInteger(WINDOW_SIZE);

    private final Lock windowLock = new ReentrantLock();
    private final Condition windowNotFull = windowLock.newCondition();

    private int soTimeout = 0;
    private int packetSize;
    private final int payloadSize = packetSize - Packet.headerSize();
    private final AtomicInteger nextSeqNumber = new AtomicInteger(0);
    private final AtomicInteger lastAcked = new AtomicInteger(-1);
    private final AtomicInteger expectedSeqNumber = new AtomicInteger(0);


    public int getPayloadSize() {
        return payloadSize;
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

    private record Packet(boolean isAck, int sequenceNumber, byte[] data) {
        public static int headerSize() {
            return 2*Integer.BYTES + 1;
        }
    }

    private record SendTask(byte[] data, InetAddress address, int port) {}

    public ReliableUdpSocket(int port, int packetSize) throws SocketException {
        if (packetSize <= Packet.headerSize() || packetSize > 65507) {
            throw new IllegalArgumentException("Invalid packet size");
        }
        this.packetSize = packetSize;
        this.socket = new DatagramSocket(port);
        this.scheduler = Executors.newScheduledThreadPool(3);
        startReceiverThread();
        startRetryChecker();
        startSendWorker();
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
                        logger.error("Receive error", e);
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
        if(packet.sequenceNumber == expectedSeqNumber.get()) {
            sendAck(packet.sequenceNumber(), senderAddress, senderPort);
        }
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

    private void startSendWorker() {
        scheduler.execute(() -> {
            while (!scheduler.isShutdown()) {
                try {
                    SendTask task = sendQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (task == null) continue;

                    while (windowAvailable.get() <= 0) {
                        LockSupport.parkNanos(1_000_000);
                    }

                    int currentSeq = nextSeqNumber.getAndIncrement();
                    Packet packet = new Packet(false, currentSeq, task.data);
                    byte[] bytes = serialize(packet);

                    synchronized (pendingPackets) {
                        pendingPackets.put(currentSeq, new PacketInfo(bytes, task.address, task.port));
                        windowAvailable.decrementAndGet();
                    }

                    DatagramPacket dp = new DatagramPacket(bytes, bytes.length, task.address, task.port);
                    socket.send(dp);
                } catch (Exception e) {
                    logger.error("Send worker error", e);
                }
            }
        });
    }

    private void startRetryChecker() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            pendingPackets.forEach((seq, info) -> {
                long elapsed = now - info.lastSentTime;
                int timeout = BASE_RETRY_TIMEOUT_MS * (1 << info.retries);

                if (elapsed > timeout) {
                    resendPacket(seq, info);
                }
            });
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    private void resendPacket(int seqNumber, PacketInfo info) {
        try {
            DatagramPacket dp = new DatagramPacket(
                    info.data,
                    info.data.length,
                    info.address,
                    info.port
            );

            socket.send(dp);
            info.retries++;
            info.lastSentTime = System.currentTimeMillis();
            logger.debug("Resent packet [seq={}, retry={}]", seqNumber, info.retries);
        } catch (IOException e) {
            logger.error("Failed to resend packet [seq={}]: {}", seqNumber, e.getMessage());
        }
    }

    public void send(byte[] data, InetAddress address, int port) throws IOException {
        windowLock.lock();
        try {
            // Ждем, пока не появится место в окне
            while (windowAvailable.get() <= 0) {
                try {
                    windowNotFull.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Send interrupted", e);
                }
            }

            // Генерируем последовательный номер
            int currentSeq = nextSeqNumber.getAndIncrement();
            Packet packet = new Packet(false, currentSeq, data);
            byte[] bytes = serialize(packet);

            synchronized (pendingPackets) {
                pendingPackets.put(currentSeq, new PacketInfo(bytes, address, port));
                windowAvailable.decrementAndGet();
            }

            // Отправляем пакет
            DatagramPacket dp = new DatagramPacket(bytes, bytes.length, address, port);
            socket.send(dp);

        } finally {
            windowLock.unlock();
        }
    }

    private void handleAck(int ackNumber) {
        receivedAcks.add(ackNumber);
        int lastContinuous = receivedAcks.first();

        // Находим максимальный непрерывный подтвержденный номер
        for (int seq : receivedAcks) {
            if (seq != lastContinuous + 1) break;
            lastContinuous = seq;
        }

        windowLock.lock();
        try {
            synchronized (pendingPackets) {
                if (lastContinuous > lastAcked.get()) {
                    int delta = lastContinuous - lastAcked.get();

                    // Обновляем счетчики
                    lastAcked.set(lastContinuous);
                    int finalLastContinuous = lastContinuous;
                    pendingPackets.keySet().removeIf(seq -> seq <= finalLastContinuous);

                    // Корректируем окно отправки
                    int newWindow = Math.min(WINDOW_SIZE, windowAvailable.get() + delta);
                    windowAvailable.set(newWindow);

                    // Очищаем подтвержденные ACK
                    receivedAcks.headSet(lastContinuous, true).clear();
                }
            }
            windowNotFull.signalAll();
        } finally {
            windowLock.unlock();
        }
    }

    public Message receive(int timeout) throws SocketTimeoutException {
        try {
            Message msg = receivedQueue.poll(timeout, TimeUnit.MILLISECONDS);
            if (msg == null) throw new SocketTimeoutException("Receive timeout");
            return msg;
        } catch (InterruptedException e) {
            throw new SocketTimeoutException("Interrupted during receive");
        }
    }

    public Message receive() throws SocketTimeoutException {
        try {
            if (soTimeout > 0) return receive(soTimeout);
            return receivedQueue.take();
        } catch (InterruptedException e) {
            throw new SocketTimeoutException("Interrupted during receive");
        }
    }

    private void sendAck(int seqNumber, InetAddress senderAddress, int senderPort) throws IOException {
        Packet ack = new Packet(true, seqNumber, new byte[0]);
        byte[] bytes = serialize(ack);
        DatagramPacket dp = new DatagramPacket(bytes, bytes.length, senderAddress, senderPort);
        socket.send(dp);
    }

    private byte[] serialize(Packet packet) {
        ByteBuffer buffer = ByteBuffer.allocate(Packet.headerSize() + packet.data().length);
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
            return new Packet(isAck, sequenceNumber, data);
        } catch (BufferUnderflowException e) {
            throw new IOException("Malformed packet", e);
        }
    }

    private int incrementSequence(int seq) {
        return (seq == MAX_SEQUENCE) ? 0 : seq + 1;
    }

    public void setSoTimeout(int timeout) {
        this.soTimeout = Math.max(timeout, 0);
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
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}