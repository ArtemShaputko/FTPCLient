package socket;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/*
public class TrustSocket implements ProgramSocket {
    private record Packet(int SN, int AN, boolean ACK, boolean SYN, boolean FIN, byte[] data) {
        boolean isControl() {
            return ACK || SYN || FIN;
        }
    }

    private record PacketMeta(Packet packet, DatagramPacket originalPacket) {}

    private static final int WINDOW_SIZE = 8;
    private static final int MAX_PACKET_SIZE = 1024;
    private static final int HEADER_SIZE = 9; // 4 (SN) + 4 (AN) + 1 (flags)
    private static final int DEFAULT_TIMEOUT = 5000;

    private final DatagramSocket socket;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ReentrantLock lock = new ReentrantLock();
    private final BlockingQueue<PacketMeta> inputQueue = new LinkedBlockingQueue<>();

    private InetAddress remoteAddress;
    private int remotePort;
    private volatile boolean connected = false;
    private volatile boolean bound = false;
    private volatile long lastActivity;
    private final AtomicInteger nextSeqNum = new AtomicInteger(0);
    private int windowBase = 0;
    private volatile int timeout = DEFAULT_TIMEOUT;
    private final ConcurrentNavigableMap<Integer, Packet> outputBuffer = new ConcurrentSkipListMap<>();

    public TrustSocket() throws SocketException {
        this(new DatagramSocket());
    }

    public TrustSocket(int port) throws SocketException {
        this(new DatagramSocket(port));
        bound = true;
    }

    private TrustSocket(DatagramSocket socket) {
        this.socket = socket;
        startReceiver();
        startSessionMonitor();
    }

    @Override
    public void bind(int port) throws SocketException {
        if (!bound) {
            socket.bind(new InetSocketAddress(port));
            bound = true;
        }
    }

    @Override
    public InetAddress getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public void accept() throws IOException {
        checkBound();
        try {
            long deadline = System.currentTimeMillis() + timeout;

            PacketMeta synMeta = receiveControlPacketMeta(
                    p -> p.SYN() && !p.ACK(),
                    deadline
            );

            DatagramPacket originalPacket = synMeta.originalPacket();
            remoteAddress = originalPacket.getAddress();
            remotePort = originalPacket.getPort();

            sendPacket(new Packet(0, synMeta.packet().SN() + 1, true, true, false, null));

            receiveControlPacketMeta(
                    Packet::ACK,
                    deadline - System.currentTimeMillis()
            );

            connected = true;
            lastActivity = System.currentTimeMillis();
        } catch (TimeoutException e) {
            throw new SocketTimeoutException("Accept timeout");
        }
    }

    @Override
    public void connect(String host, int port) throws IOException {
        connect(InetAddress.getByName(host), port);
    }

    @Override
    public void connect(InetAddress address, int port) throws IOException {
        try {
            remoteAddress = address;
            remotePort = port;
            long deadline = System.currentTimeMillis() + timeout;

            sendPacket(new Packet(
                    nextSeqNum.getAndIncrement(),
                    0,
                    false,
                    true,
                    false,
                    null
            ));

            System.out.println("SYN");

            PacketMeta synAckMeta = receiveControlPacketMeta(
                    p -> p.SYN() && p.ACK(),
                    deadline - System.currentTimeMillis()
            );

            System.out.println("SYN-ACK");

            sendPacket(new Packet(
                    synAckMeta.packet().AN(),
                    synAckMeta.packet().SN() + 1,
                    true,
                    false,
                    false,
                    null
            ));

            System.out.println("ACK");

            connected = true;
            lastActivity = System.currentTimeMillis();
        } catch (TimeoutException e) {
            throw new SocketTimeoutException("Connect timeout");
        }
    }

    @Override
    public void send(String message) throws IOException {
        send(message.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void send(byte[] data) throws IOException {
        send(data, data.length);
    }

    @Override
    public void send(byte[] data, int length) throws IOException {
        checkConnected();
        byte[] chunk = Arrays.copyOf(data, length);
        int seqNum = nextSeqNum.getAndIncrement();

        lock.lock();
        try {
            while (seqNum - windowBase >= WINDOW_SIZE) {
                if (!lock.newCondition().await(getSoTimeout(), TimeUnit.MILLISECONDS)) {
                    throw new SocketTimeoutException("Send window timeout");
                }
            }

            Packet packet = new Packet(seqNum, 0, false, false, false, chunk);
            outputBuffer.put(seqNum, packet);
            sendDatagram(packet);
            scheduleRetransmission(seqNum);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Send interrupted", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Message receive() throws SocketTimeoutException {
        try {
            PacketMeta meta = inputQueue.poll(timeout, TimeUnit.MILLISECONDS);
            if (meta == null) throw new SocketTimeoutException("Receive timeout");

            return createMessage(meta);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SocketTimeoutException("Receive interrupted");
        }
    }



    @Override
    public void setSoTimeout(int timeout) {
        this.timeout = timeout;
    }

    @Override
    public int getSoTimeout() throws SocketException {
        return timeout;
    }

    @Override
    public void close() throws Exception {
        if (connected) {
            sendPacket(new Packet(nextSeqNum.get(), 0, false, false, true, null));
        }
        scheduler.shutdown();
        if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
            scheduler.shutdownNow();
        }
        socket.close();
    }

    private void sendPacket(Packet packet) throws IOException {
        if (packet.isControl()) {
            sendDatagram(packet);
        } else {
            outputBuffer.put(packet.SN(), packet);
            sendDatagram(packet);
            scheduleRetransmission(packet.SN());
        }
    }

    private void sendDatagram(Packet packet) throws IOException {
        byte[] data = serializePacket(packet);
        DatagramPacket datagram = new DatagramPacket(
                data, data.length, remoteAddress, remotePort
        );
        socket.send(datagram);
    }

    private byte[] serializePacket(Packet packet) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + (packet.data() != null ? packet.data().length : 0));
        buf.putInt(packet.SN())
                .putInt(packet.AN())
                .put((byte) ((packet.ACK() ? 0x01 : 0) |
                        (packet.SYN() ? 0x02 : 0) |
                        (packet.FIN() ? 0x04 : 0)));

        if (packet.data() != null) {
            buf.put(packet.data());
        }
        return buf.array();
    }

    private Packet parsePacket(byte[] data) {
        ByteBuffer buf = ByteBuffer.wrap(data);
        int SN = buf.getInt();
        int AN = buf.getInt();
        byte flags = buf.get();
        return new Packet(
                SN,
                AN,
                (flags & 0x01) != 0,
                (flags & 0x02) != 0,
                (flags & 0x04) != 0,
                Arrays.copyOfRange(data, HEADER_SIZE, data.length)
        );
    }

    private void startReceiver() {
        scheduler.execute(() -> {
            byte[] buffer = new byte[MAX_PACKET_SIZE];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                    socket.receive(datagram); // Блокирующий вызов без таймаута

                    Packet packet = parsePacket(Arrays.copyOf(datagram.getData(), datagram.getLength()));
                    inputQueue.put(new PacketMeta(packet, datagram));

                    if (!packet.ACK() && !packet.SYN()) {
                        sendAck(packet.SN() + 1);
                    } else if (packet.ACK()) {
                        handleACK(packet);
                    }
                    lastActivity = System.currentTimeMillis();
                } catch (IOException | InterruptedException e) {
                    if (!socket.isClosed()) e.printStackTrace();
                }
            }
        });
    }

    private void handleACK(Packet packet) {
        outputBuffer.remove(packet.AN() - 1);
    }

    // Вспомогательные методы
    private Message createMessage(PacketMeta meta) {
        Packet packet = meta.packet();
        return new Message(
                packet.data(),
                meta.originalPacket().getAddress(),
                meta.originalPacket().getPort(),
                packet.data() != null ? packet.data().length : 0
        );
    }

    private void sendAck(int ackNum) {
        try {
            sendPacket(new Packet(0, ackNum, true, false, false, null));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void scheduleRetransmission(int seqNum) {
        scheduler.schedule(() -> {
            if (outputBuffer.containsKey(seqNum)) {
                try {
                    sendDatagram(outputBuffer.get(seqNum));
                    scheduleRetransmission(seqNum);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                this.thread.stop();
            }
        }, 1000, TimeUnit.MILLISECONDS);
    }

    private PacketMeta receiveControlPacketMeta(Predicate<Packet> condition, long remainingTime)
            throws TimeoutException {
        final long deadline = System.currentTimeMillis() + remainingTime;

        while (System.currentTimeMillis() < deadline) {
            PacketMeta meta = inputQueue.poll();
            if (meta != null && condition.test(meta.packet())) {
                return meta;
            }
            try {
                Thread.sleep(50); // Периодичность проверки
            } catch (InterruptedException e) {
                throw new TimeoutException("Operation interrupted");
            }
        }
        throw new TimeoutException();
    }

    private void startSessionMonitor() {
        scheduler.scheduleAtFixedRate(() -> {
            if (connected && System.currentTimeMillis() - lastActivity > timeout) {
                try {
                    close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, timeout, timeout, TimeUnit.MILLISECONDS);
    }

    private void checkBound() throws IOException {
        if (!bound) throw new IOException("Socket not bound");
    }

    private void checkConnected() throws IOException {
        if (!connected) throw new IOException("Not connected");
    }
}*/
