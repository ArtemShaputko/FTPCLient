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
import java.util.Arrays;

public class UdpDownloader implements Downloader {
    private final ReliableUdpSocket socket;
    private final PrintWriter consoleWriter;
    private final LineReader consoleReader;
    private final InetAddress address;
    private final int bufferSize;
    private final int sendTimeout;
    private final int port;

    public UdpDownloader(ReliableUdpSocket socket, PrintWriter consoleWriter, LineReader consoleReader, InetAddress address, int port, int bufferSize, int sendTimeout) {
        this.socket = socket;
        this.consoleWriter = consoleWriter;
        this.consoleReader = consoleReader;
        this.address = address;
        this.bufferSize = bufferSize;
        this.port = port;
        this.sendTimeout = sendTimeout;
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
        try (var input = new FileInputStream(localPath)) {
            int bytesRead;
            long total = 0;
            long skipped = 0;
            long currentFileSize = 0;
            socket.send("SYN", address, port);
            if (resume) {
                currentFileSize = readLong();
                skipped = input.skip(currentFileSize);
            }
            if (skipped == currentFileSize) {
                total = input.available();
            }
            writeLong(total);
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            boolean toSend;

            try (ProgressBar pb = new ProgressBar("Передача " + localPath, total + currentFileSize)) {
                pb.stepTo(currentFileSize);
                while ((bytesRead = input.read(buffer.array())) != -1) {
                    do {
                        toSend = false;
                        try {
                            socket.send(Arrays.copyOf(buffer.array(), bytesRead), address, port, sendTimeout);
                            pb.stepBy(bytesRead);
                        } catch (SocketTimeoutException e) {
                            pb.pause();
                            consoleWriter.println("\nМедленное соединение, желаете продолжить?");
                            if ("y".equals(consoleReader.readLine("(y/n) "))) {
                                pb.resume();
                                toSend = true;
                                continue;
                            }
                            throw new SocketException("Нет ответа от сервера");
                        }
                    } while (toSend);
                }
            }
            consoleWriter.println("Начальный размер файла: " + currentFileSize + " байт");
            consoleWriter.println("Передано: " + total + " байт");

        }
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
