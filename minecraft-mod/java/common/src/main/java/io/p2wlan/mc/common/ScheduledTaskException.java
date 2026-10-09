package io.p2wlan.mc.common;

import java.io.IOException;

/** Local game-thread scheduling failures, never an IPC/network state. */
public final class ScheduledTaskException extends IOException {
    public enum Code { CAPACITY, CLOSED, SCOPE_CHANGED, TASK_EXPIRED, DISPATCH_REJECTED, INVALID_TASK, TASK_FAILED }
    private final Code code;
    public ScheduledTaskException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
