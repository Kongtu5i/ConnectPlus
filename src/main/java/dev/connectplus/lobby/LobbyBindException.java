package dev.connectplus.lobby;

public class LobbyBindException extends RuntimeException {

    private final int port;

    public LobbyBindException(final int port, final Throwable cause) {
        super("Failed to bind lobby server to port " + port, cause);
        this.port = port;
    }

    public int getPort() {
        return this.port;
    }
}
