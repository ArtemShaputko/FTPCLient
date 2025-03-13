package client;

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

class Client {
    private final String serverIp;
    private final int port;
    private final PrintWriter consoleWriter;
    private Socket socket;
    public static final int TIMEOUT = 60_000;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);

    private static final String HEARTBEAT_REQUEST = "PING";
    private static final String HEARTBEAT_RESPONSE = "PONG";

    public Client(String ip, int port, PrintWriter consoleWriter) {
        this.serverIp = ip;
        this.port = port;
        this.consoleWriter = consoleWriter;
    }

    public void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(serverIp, port), TIMEOUT);
        socket.setSoTimeout(TIMEOUT);
        isConnected.set(true);
    }

    public String sendAndWait(String message) {
        try {
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println(message);

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;

            while ((line = in.readLine()) != null) {
                if ("END".equals(line)) {
                    break;
                } else if (HEARTBEAT_REQUEST.equals(line)) {
                    out.println(HEARTBEAT_RESPONSE);
                } else if (!HEARTBEAT_RESPONSE.equals(line)) {
                    response.append(line).append("\n");
                }
            }

            return response.toString().trim();
        } catch (IOException e) {
            consoleWriter.println("Send error: " + e.getMessage());
            return null;
        }
    }

    public String readline() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        return in.readLine();
    }

    public void checkHeartbeat() {
        try {
            if (isConnected.get()) {
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                out.println(HEARTBEAT_REQUEST);
            }
        } catch (IOException e) {
            consoleWriter.println("Ошибка приёма сообщения: " + e.getMessage());
        }
    }

    public void downloadFile(String remotePath, String localPath, boolean resume) throws IOException {
        Path outputPath = Paths.get(localPath).toAbsolutePath();
        Files.createDirectories(outputPath.getParent());
        var is = socket.getInputStream();
        try (FileOutputStream fos = new FileOutputStream(outputPath.toFile(), resume);
                 FileChannel channel = fos.getChannel()) {

            byte[] fileSizeBytes = new byte[Integer.BYTES];
            if (is.read(fileSizeBytes) < Integer.BYTES) {
                System.out.println("Не получилось определить размер файла");
                return;
            }
            int fileSize = ByteBuffer.wrap(fileSizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
            long existingSize = Files.exists(outputPath) ? Files.size(outputPath) : 0;

            try (ProgressBar pb = new ProgressBarBuilder()
                    .setTaskName("Скачивание " + remotePath)
                    .setInitialMax(fileSize)
                    .build()) {

                long startTime = System.nanoTime();
                pb.stepTo(existingSize);

                if (existingSize < fileSize) {
                    transferFileWithProgress(channel, fileSize, existingSize, is, pb);
                }
                double duration = (System.nanoTime() - startTime) / 1e9;
                double speedMBs = (pb.getCurrent() / (1024.0 * 1024.0)) / duration;

                consoleWriter.printf(
                        "Файл %s скачан (%.2f MB, %.2f MB/s)\n",
                        remotePath,
                        pb.getCurrent() / (1024.0 * 1024.0),
                        speedMBs
                );
            }
        }
    }

    private void transferFileWithProgress(
            FileChannel channel,
            long fileSize,
            long offset,
            InputStream is,
            ProgressBar pb
    ) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long transferred = offset;

        pb.stepTo(offset); // Учет уже скачанной части

        while (transferred < fileSize && isConnected.get()) {
            int read = is.read(buffer.array());
            if (read == -1) break;

            buffer.limit(read);
            channel.write(buffer, transferred);
            buffer.clear();
            transferred += read;

            pb.stepBy(read); // Обновление прогресса
        }
    }

    public void closeConnection() {
        isConnected.set(false);
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            consoleWriter.println("Ошибка закрытия соединения: " + e.getMessage());
        }
    }
}
