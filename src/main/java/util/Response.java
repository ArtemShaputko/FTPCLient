package util;

import status.Status;

public record Response(
        Status status,
        String message
) {
}
