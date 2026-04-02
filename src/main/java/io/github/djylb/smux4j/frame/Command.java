package io.github.djylb.smux4j.frame;

/**
 * Protocol commands defined by smux.
 */
public enum Command {
    SYN(0),
    FIN(1),
    PSH(2),
    NOP(3),
    UPD(4);

    private final int code;

    Command(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static Command fromCode(int code) {
        for (Command command : values()) {
            if (command.code == code) {
                return command;
            }
        }
        throw new IllegalArgumentException("unknown command code: " + code);
    }
}
