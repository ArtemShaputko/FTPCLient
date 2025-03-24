package status;

public enum Status {
    SUCCESS(200, "Successful"),
    END(300, "End of response"),
    ERROR(400, "Error"),
    UNKNOWN(-1, "Unknown status"),
    NO_END(-300, "No end of response"),
    CONNECT(100, "Request to connect");

    private final int code;
    private final String description;

    Status(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static Status getStatusFromCode(int code) {
        for (Status status : Status.values()) {
            if (status.code() == code) {
                return status;
            }
        }
        return UNKNOWN;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }

}
