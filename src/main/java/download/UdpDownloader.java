package download;

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import org.jline.reader.LineReader;
import socket.Message;
import socket.ReliableUdpSocket;

import java.io.*;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UdpDownloader implements Downloader{
    private final ReliableUdpSocket socket;
    private final PrintWriter consoleWriter;
    private final LineReader consoleReader;
    private final InetAddress address;
    private final int port;

    public UdpDownloader(ReliableUdpSocket socket, PrintWriter consoleWriter, LineReader consoleReader, InetAddress address, int port) {
        this.socket = socket;
        this.consoleWriter = consoleWriter;
        this.consoleReader = consoleReader;
        this.address = address;
        this.port = port;
    }

    @Override
    public void downloadFile(String remotePath, String localPath, boolean resume) throws IOException {
        Path outputPath = Paths.get(localPath).toAbsolutePath();
        Files.createDirectories(outputPath.getParent());
        try (FileOutputStream fos = new FileOutputStream(outputPath.toFile(), resume);
             FileChannel channel = fos.getChannel()) {
            long existingSize = Files.exists(outputPath) ? Files.size(outputPath) : 0;
            if (resume) {
                writeLong(existingSize);
            }
            socket.send("SYN", address, port); // Синхронизация канала
            long extraSize = readLong();
            if (extraSize < 0) {
                consoleWriter.println("Невозможно получить длину файла: " + extraSize);
                return;
            }
            try (ProgressBar pb = new ProgressBarBuilder()
                    .setTaskName("Скачивание " + remotePath)
                    .setInitialMax(existingSize + extraSize)
                    .build()) {

                long startTime = System.nanoTime();
                pb.stepTo(existingSize);

                if (extraSize > 0) {
                    transferFileWithProgress(channel, extraSize, existingSize, pb);
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
            long sizeToReceive,
            long currentFileSize,
            ProgressBar pb
    ) throws IOException {
        Message buffer;
        long received = 0;
        while (received < sizeToReceive) {
            try {
                buffer = socket.receive();
            } catch (SocketTimeoutException e) {
                pb.pause();
                consoleWriter.println("\nМедленное соединение, желаете продолжить?");
                if ("y".equals(consoleReader.readLine("(y/n) "))) {
                    pb.resume();
                    continue;
                }
                throw new SocketException("Нет ответа от сервера");
            }
            channel.write(ByteBuffer.wrap(buffer.data()), currentFileSize + received);
            received += buffer.length();

            pb.stepBy(buffer.length()); // Обновление прогресса
        }
        consoleWriter.println("Начальный размер файла: " + currentFileSize + " байт");
        consoleWriter.println("Требовалось получить: " + sizeToReceive + " байт");
        consoleWriter.println("Получено: " + received + " байт");

    }

    @Override
    public void uploadFile(String localPath, boolean resume) throws IOException {

    }

    private long readLong() throws IOException {
        var bytes = socket.receive().data();
        return ByteBuffer.wrap(bytes)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong();
    }

    private void writeLong(long value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(value);
        socket.send(buffer.array(), address, port);
    }
}
