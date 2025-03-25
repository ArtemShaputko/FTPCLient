package socket;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public record Message(
        byte[] data,
        InetAddress address,
        int port,
        int length
) {
    // Дополнительный метод для удобства
    public String text() {
        return new String(data, 0, length, StandardCharsets.UTF_8);
    }
}
