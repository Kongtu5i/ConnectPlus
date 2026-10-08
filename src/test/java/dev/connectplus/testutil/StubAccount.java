package dev.connectplus.testutil;

import dev.connectplus.accounts.CPAccount;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** No network/token refresh; tracks whether disabled UI reveals account metadata. */
public class StubAccount implements CPAccount {
    public final AtomicInteger displayReads = new AtomicInteger();

    @Override
    public String displayName() {
        displayReads.incrementAndGet();
        return "PrivateAccount";
    }

    @Override
    public UUID uuid() { return new UUID(1, 2); }

    @Override
    public boolean isExpired() { return false; }

    @Override
    public String toJson() { return "{\"test\":true}"; }
}
