package client;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

public class Client {
    private int port;
    private String serverIp;
    private String lastServer;
    private String lastFile;
    public static final int timeout = 30 * 1000;
    public static final String PING_MESSAGE = "\1\2\3\4";

    public Client() {
    }

    public Client(String ip, int port) {
        this.serverIp = ip;
        this.port = port;
    }

    public void connect(String ip, int port) {
        lastServer = serverIp;
        this.serverIp = ip;
        this.port = port;
        connect();
    }

    public void connect() {
        try (Socket socket = new Socket(serverIp, port)) {
            socket.setKeepAlive(true);
            socket.setSoTimeout(timeout);
            communicate(socket);
        } catch (SocketTimeoutException e) {
            System.out.println("Время ожидания вышло");
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    private void communicate(Socket socket) throws IOException {
        OutputStream output = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        long startTime = System.currentTimeMillis();
        try (BufferedReader socketReader = new BufferedReader(new InputStreamReader(input));
             BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));
             PrintWriter writer = new PrintWriter(output, true);) {
            System.out.print("$ ");
            while (true) {
                if (socketReader.ready()) {
                    startTime = System.currentTimeMillis();
                    System.out.println(socketReader.readLine());
                    System.out.print("$ ");
                }
                if (consoleReader.ready()) {
                    String line = consoleReader.readLine();
                    writer.println(line);
                    String[] args = line.split(" ");
                    switch (args[0]) {
                        case "close":
                            return;
                        case "download":
                            boolean cont = args.length > 3 && args[3].equalsIgnoreCase("continue");
                            if (args.length < 3) {
                                System.out.println("Saving to " + args[1]);
                                downloadFile(input, output, args[1], cont);
                            } else {
                                downloadFile(input, output, args[2], cont);
                            }
                    }
                }
                if (System.currentTimeMillis() - startTime > timeout) {
                    writer.println(PING_MESSAGE);
                    char[] pingMessage = new char[PING_MESSAGE.length() + 2];
                    if (socketReader.read(pingMessage) == -1) {
                        System.out.println("Соединение прервано");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Превышено время ожидания, автоматическое отключение");
        }
    }

    private void downloadFile(InputStream is, OutputStream os, String fileName, boolean cont) throws IOException {
        lastFile = fileName;
        File file = new File(fileName);

        byte[] fileSizeBytes = new byte[Integer.BYTES];
        long start = System.nanoTime();
        if (is.read(fileSizeBytes) < Integer.BYTES) {
            System.out.println("Can`t determine file size");
        }
        int fileSize = ByteBuffer.wrap(fileSizeBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (fileSize == 0) {
            file.delete();
            return;
        }

        try (FileOutputStream fos = new FileOutputStream(file);
             FileChannel channel = fos.getChannel()) {
            os.write(1);


            byte[] buffer = new byte[8192];
            int bytesRead, totalRead = 0;
            do {
                bytesRead = is.read(buffer);
                totalRead += bytesRead;
                fos.write(buffer, 0, bytesRead);
            } while (bytesRead != -1 && totalRead < fileSize);
            long end = System.nanoTime();
            fos.flush();
            channel.force(true);
            System.out.println("Файл " + fileName + " успешно получен");
            System.out.println("Средняя скорость скачивания: " + (totalRead / 1000) / ((end - start) / 1e9) + " кб/с");
            System.out.print("$ ");
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
