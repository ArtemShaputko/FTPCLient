package socket;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

public interface ProgramSocket extends AutoCloseable {
    void accept() throws IOException;
    void setSoTimeout(int timeout) throws SocketException;
    int getSoTimeout() throws SocketException;
    void connect(String host, int port) throws IOException;
    void connect(InetAddress address, int port) throws IOException;
    void send(String message) throws IOException;
    void send(byte[] message) throws IOException;
    void send(byte[] message, int length) throws IOException;
    Message receive() throws SocketTimeoutException, SocketException;
    void bind(int port) throws SocketException;
    InetAddress getRemoteAddress();
}

