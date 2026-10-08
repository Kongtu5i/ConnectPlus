package dev.connectplus.access;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The short critical section shared by list commits and binding commits: one
 * action at a time reads the current linkage and commits one list change, so a
 * console mutation and a link transaction can never interleave their
 * read-modify-write on the same list file.
 *
 * <p>Network waits, device-code logins and session leases must never happen
 * inside this lock — keep it short.</p>
 */
public final class AccessMutationCoordinator {

    private final ReentrantLock lock = new ReentrantLock();

    /** Runs the action while holding the shared commit lock. */
    public <T> T withCommitLock(final Callable<T> action) throws Exception {
        Objects.requireNonNull(action, "action");
        this.lock.lock();
        try {
            return action.call();
        } finally {
            this.lock.unlock();
        }
    }

    /** Runs the action while holding the shared commit lock (unchecked variant). */
    public void withCommitLock(final Runnable action) {
        Objects.requireNonNull(action, "action");
        this.lock.lock();
        try {
            action.run();
        } finally {
            this.lock.unlock();
        }
    }
}
