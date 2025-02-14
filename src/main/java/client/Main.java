package client;

import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        String line;
        Scanner scanner = new Scanner(System.in);
        Client client = new Client();
        while (true) {
            System.out.print("Введите адрес сервера или `exit`:\n# ");
            line = scanner.nextLine();
            if (line.equalsIgnoreCase("exit")) {
                return;
            }
            client.connect(line, 12345);
        }
    }
}
