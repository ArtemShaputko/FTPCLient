package communication;

public class Main {
    public static void main(String[] args) throws Exception {
        try(var manager = new CommunicationManager(12345)){
            manager.run();
        }
    }
}