package socket;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.*;

/*
public class TrustfulSocket implements ProgramSocket{
    private final BlockingQueue<PacketMeta> inputBuffer;
    BlockingQueue<Packet> outputBuffer;

    private static final int WINDOW_SIZE = 8;
    private static final int MAX_PACKET_SIZE = 1024;
    private static final int HEADER_SIZE = 9; // 4 (SN) + 4 (AN) + 1 (flags)
    private static final int DEFAULT_TIMEOUT = 5000;

    private InetAddress remoteAddress;
    private int remotePort;
    private volatile boolean connected = false;
    private volatile boolean bound = false;

    private final DatagramSocket socket;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public TrustfulSocket() throws SocketException {
        this(new DatagramSocket());
        bound = true;
        startReceiver();
    }

    public TrustfulSocket(DatagramSocket socket) {
        this.socket = socket;
        this.inputBuffer = new LinkedBlockingQueue<>();
        this.outputBuffer = new LinkedBlockingQueue<>(WINDOW_SIZE);
    }

    @Override
    public void accept() throws IOException {

    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {

    }

    @Override
    public int getSoTimeout() throws SocketException {
        return 0;
    }

    @Override
    public void connect(String host, int port) throws IOException {

    }

    @Override
    public void connect(InetAddress address, int port) throws IOException {

    }

    @Override
    public void send(String message) throws IOException {

    }

    @Override
    public void send(byte[] message) throws IOException {

    }

    @Override
    public void send(byte[] message, int length) throws IOException {

    }

    @Override
    public Message receive() throws SocketTimeoutException, SocketException {
        return null;
    }

    @Override
    public void bind(int port) throws SocketException {

    }

    @Override
    public InetAddress getRemoteAddress() {
        return null;
    }

    @Override
    public void close() throws Exception {

    }

    private void startReceiver() {
        scheduler.execute(() -> {
            byte[] buffer = new byte[MAX_PACKET_SIZE];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                    socket.receive(datagram); // Блокирующий вызов без таймаута

                    Packet packet = Packet.parse(Arrays.copyOf(datagram.getData(), datagram.getLength()));
                    inputBuffer.put(new PacketMeta(packet, datagram));

                    if (!packet.ACK() && !packet.SYN()) {
                        sendAck(packet.SN() + 1);
                    } else if (packet.ACK()) {
                        handleACK(packet);
                    }
                } catch (IOException | InterruptedException e) {
                    if (!socket.isClosed()) e.printStackTrace();
                }
            }
        });
    }

    private void handleACK(Packet packet) {
        outputBuffer.remove(packet);
    }

    private void sendAck(int ackNum) {
        try {
            sendPacket(new Packet(0, ackNum, true, false, false, null));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void sendPacket(Packet packet) throws IOException {
        if (packet.isControl()) {
            sendDatagram(packet);
        } else {
            outputBuffer.put(packet);
            sendDatagram(packet);
            scheduleRetransmission(packet.SN());
        }
    }

    private record Packet(int SN, int AN, boolean ACK, boolean SYN, boolean FIN, byte[] data) {
        boolean isControl() {
            return ACK || SYN || FIN;
        }
        public byte[] serialize() {
            ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + (data != null ? data.length : 0));
            buf.putInt(SN)
                    .putInt(AN)
                    .put((byte) ((ACK ? 0x01 : 0) |
                            (SYN ? 0x02 : 0) |
                            (FIN ? 0x04 : 0)));

            if (data != null) {
                buf.put(data);
            }
            return buf.array();
        }
        public static Packet parse(byte[] data) {
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
    }

    private record PacketMeta(Packet packet, DatagramPacket originalPacket) {}
}
*/
