package download;

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import org.jline.reader.LineReader;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.*;

public class TcpDownloader implements Downloader{
    Socket socket;
    PrintWriter consoleWriter;
    LineReader consoleReader;
    public TcpDownloader(Socket socket, PrintWriter consoleWriter, LineReader consoleReader) {
        this.socket = socket;
        this.consoleWriter = consoleWriter;
        this.consoleReader = consoleReader;
    }
    @Override
    public void downloadFile(String remotePath, String localPath, boolean resume) throws IOException {
        Path outputPath = Paths.get(localPath).toAbsolutePath();
        Files.createDirectories(outputPath.getParent());
        var dis = new DataInputStream(socket.getInputStream());
        var dos = new DataOutputStream(socket.getOutputStream());
        try (FileOutputStream fos = new FileOutputStream(outputPath.toFile(), resume);
             FileChannel channel = fos.getChannel()) {
            long existingSize = Files.exists(outputPath) ? Files.size(outputPath) : 0;
            if (resume) {
                dos.writeLong(Long.reverseBytes(existingSize));
            }
            dos.write(0); // Синхронизация канала
            long extraSize = Long.reverseBytes(dis.readLong());
            if (extraSize < 0) {
                consoleWriter.println("Невозможно получить длину файла");
                return;
            }
            try (ProgressBar pb = new ProgressBarBuilder()
                    .setTaskName("Скачивание " + remotePath)
                    .setInitialMax(existingSize + extraSize)
                    .build()) {

                long startTime = System.nanoTime();
                pb.stepTo(existingSize);

                if (extraSize > 0) {
                    transferFileWithProgress(channel, extraSize, existingSize, dis, pb);
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
            InputStream is,
            ProgressBar pb
    ) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long received = 0;
        int read;
        while (received < sizeToReceive) {
            try {
                read = is.read(buffer.array());
            } catch (SocketTimeoutException e) {
                pb.pause();
                consoleWriter.println("\nМедленное соединение, желаете продолжить?");
                if ("y".equals(consoleReader.readLine("(y/n) "))) {
                    pb.resume();
                    continue;
                }
                throw new SocketException("Нет ответа от сервера");
            }
            if (read == -1) break;

            buffer.limit(read);
            channel.write(buffer, currentFileSize + received);
            buffer.clear();
            received += read;

            pb.stepBy(read); // Обновление прогресса
        }
        consoleWriter.println("Начальный размер файла: " + currentFileSize + " байт");
        consoleWriter.println("Требовалось получить: " + sizeToReceive + " байт");
        consoleWriter.println("Получено: " + received + " байт");

    }

    @Override
    public void uploadFile(String localPath, boolean resume) throws IOException {
        var dos = new DataOutputStream(socket.getOutputStream());
        var dis = new DataInputStream(socket.getInputStream());
        try (var input = new FileInputStream(localPath)) {
            int bytesRead;
            long total = 0;
            long skipped = 0;
            long currentFileSize = 0;
            dos.write(1); // Синхронизация канала
            if (resume) {
                currentFileSize = Long.reverseBytes(dis.readLong());
                skipped = input.skip(currentFileSize);
            }
            if (skipped == currentFileSize) {
                total = input.available();
            }
            dos.writeLong(Long.reverseBytes(total));
            dos.flush();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            ExecutorService executor = Executors.newSingleThreadExecutor(); // Важно: только 1 поток!

            try (ProgressBar pb = new ProgressBar("Передача " + localPath, total + currentFileSize)) {
                pb.stepTo(currentFileSize);
                CompletableFuture<Void> currentOperation = CompletableFuture.completedFuture(null);

                while ((bytesRead = input.read(buffer.array())) != -1) {
                    byte[] dataCopy = Arrays.copyOf(buffer.array(), bytesRead); // Копируем данные
                    int finalBytesRead = bytesRead;

                    // Создаем новую операцию, привязанную к предыдущей
                    CompletableFuture<Void> newOperation = currentOperation.thenRunAsync(() -> {
                        try {
                            dos.write(dataCopy, 0, finalBytesRead);
                            pb.stepBy(finalBytesRead);
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    }, executor);

                    currentOperation = newOperation;

                    try {
                        newOperation.get(30_000, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        pb.pause();
                        consoleWriter.println("\nМедленное соединение, желаете продолжить?");
                        if ("y".equals(consoleReader.readLine("(y/n) "))) {
                            pb.resume();
                            continue;
                        }
                        executor.shutdownNow();
                        throw new SocketException("Таймаут записи блока данных");
                    }
                }
                currentOperation.get();
                consoleWriter.println("Начальный размер файла: " + currentFileSize + " байт");
                consoleWriter.println("Передано: " + total + " байт");

            } catch (IOException | ExecutionException | InterruptedException e) {
                Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                throw new CompletionException(cause);
            } finally {
                executor.shutdown();
                dos.flush();
            }
        }
    }
}
