package util;

public enum Context {
    MAIN("main>"),
    SERVER("");

    private final String prompt;

    Context(String prompt) {
        this.prompt = prompt;
    }

    public String prompt() {
        return prompt;
    }
}
