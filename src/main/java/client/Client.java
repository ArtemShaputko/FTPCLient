package client;

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import org.jline.reader.LineReader;
import status.Status;
import util.Response;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

class Client {
    private final String serverIp;
    private final int port;
    private final PrintWriter consoleWriter;
    private final LineReader consoleReader;
    private Socket socket;
    private final AtomicBoolean isConnected = new AtomicBoolean(false);

    public static final int TIMEOUT = 60_000;
    public static final String HEARTBEAT_REQUEST = "PING";
    public static final String HEARTBEAT_RESPONSE = "PONG";
    public static final String ACCEPT_MESSAGE = Status.SUCCESS.code() + " ACCEPT";
    public static final String END_MESSAGE = Status.END.code() + " END";

    public Client(String ip, int port, PrintWriter consoleWriter, LineReader consoleReader) {
        this.serverIp = ip;
        this.port = port;
        this.consoleWriter = consoleWriter;
        this.consoleReader = consoleReader;
    }

    public void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(serverIp, port), TIMEOUT);
        socket.setSoTimeout(TIMEOUT);
        socket.setKeepAlive(true);
        isConnected.set(true);
    }

    public Response sendAndWait(String message) throws IOException {
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        out.println(message);

        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        StringBuilder responseBuilder = new StringBuilder();
        String line;
        try {
            while ((line = in.readLine()) != null) {
                if (END_MESSAGE.equals(line)) {
                    break;
                } else if (HEARTBEAT_REQUEST.equals(line)) {
                    out.println(HEARTBEAT_RESPONSE);
                } else if (!HEARTBEAT_RESPONSE.equals(line)) {
                    responseBuilder.append(line).append("\n");
                }
            }
        } catch (SocketTimeoutException e) {
            if (!responseBuilder.isEmpty()) {
                String responseMessage = responseBuilder.toString().trim();
                return new Response(Status.NO_END, responseMessage.split(" ", 2)[1]);
            }
            throw e;
        }
        return parseResponse(responseBuilder.toString().trim());
    }

    public static Response parseResponse(String message) {
        if (message.isEmpty()) {
            return new Response(Status.SUCCESS, "");
        }
        var responseArray = message.split(" ", 2);
        int code = Integer.parseInt(responseArray[0]);
        return new Response(Status.getStatusFromCode(code), responseArray[1]);
    }

    public String readLine() throws IOException {
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
        while (received < sizeToReceive && isConnected.get()) {
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

    public void uploadFile(String localPath, boolean resume) throws IOException {
        var dos = new DataOutputStream(socket.getOutputStream());
        var dis = new DataInputStream(socket.getInputStream());
        try (var input = new FileInputStream(localPath)) {
            int bytesRead;
            long total = 0;
            long skipped = 0;
            long currentFileSize = 0;
            dos.write(1); // Синхронизация канала
            if(resume) {
                currentFileSize = Long.reverseBytes(dis.readLong());
                skipped = input.skip(currentFileSize);
            }
            if( skipped == currentFileSize) {
                total =input.available();
            }
            dos.writeLong(Long.reverseBytes(total));
            dos.flush();
            ByteBuffer buffer = ByteBuffer.allocate(8192);

            try(ProgressBar pb = new ProgressBar("Передача " + localPath, total + currentFileSize)) {
                pb.stepTo(currentFileSize);
                while ((bytesRead = input.read(buffer.array())) != -1) {
                    int finalBytesRead = bytesRead;
                    CompletableFuture<Void> writeFuture = CompletableFuture.runAsync(() -> {
                        try {
                            dos.write(buffer.array(), 0, finalBytesRead);
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    });
                    try {
                        writeFuture.get(TIMEOUT, TimeUnit.MILLISECONDS);
                        pb.stepBy(bytesRead);
                    } catch (TimeoutException e) {
                        writeFuture.cancel(true);
                        throw new SocketException("Таймаут записи блока данных");
                    }
                }
            }
            consoleWriter.println("Начальный размер файла: " + currentFileSize + " байт");
            consoleWriter.println("Передано: " +  total + " байт");

        } catch (IOException | ExecutionException | InterruptedException  e){
            Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
            throw new CompletionException(cause);
        }finally {
            dos.flush();
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
