package com.audigo.global.migration.tracking;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

// 입장한 작업 1건. 작업이 끝나면 반드시 close 한다 (중복 close는 무시)
public final class WorkTicket implements AutoCloseable {

    private final long id;
    private final WorkType type;
    private final String description;
    private final Long parentId;
    private final Instant openedAt;
    private final String threadName;
    private final Consumer<WorkTicket> onClose;
    private final AtomicBoolean closed = new AtomicBoolean();

    public WorkTicket(
            long id,
            WorkType type,
            String description,
            Long parentId,
            Instant openedAt,
            Consumer<WorkTicket> onClose
    ) {
        this.id = id;
        this.type = Objects.requireNonNull(type, "type");
        this.description = description == null ? "" : description;
        this.parentId = parentId;
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.threadName = Thread.currentThread().getName();
        this.onClose = Objects.requireNonNull(onClose, "onClose");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            onClose.accept(this);
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    public long id() {
        return id;
    }

    public WorkType type() {
        return type;
    }

    public String description() {
        return description;
    }

    // 부모 티켓 id. 부모 없이 열린 티켓이면 null
    public Long parentId() {
        return parentId;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public String threadName() {
        return threadName;
    }

    @Override
    public String toString() {
        return "WorkTicket{id=" + id + ", type=" + type + ", description='" + description + "'}";
    }
}
