package io.github.rocketbunny727.swiftmapper.transaction;

import io.github.rocketbunny727.swiftmapper.core.Transaction;

public final class SwiftTransactionContext {

    private static final ThreadLocal<TransactionHolder> HOLDER = new ThreadLocal<>();

    private SwiftTransactionContext() {}

    public static boolean isActive() {
        TransactionHolder holder = HOLDER.get();
        return holder != null && holder.transaction().isActive();
    }

    public static Transaction current() {
        TransactionHolder holder = HOLDER.get();
        if (holder == null) {
            throw new IllegalStateException(
                    "No active SwiftMapper transaction bound to the current thread. " +
                            "Ensure the method is called within a @SwiftTransactional context.");
        }
        return holder.transaction();
    }

    static void bind(Transaction transaction) {
        HOLDER.set(new TransactionHolder(transaction, 1));
    }

    static void incrementDepth() {
        TransactionHolder holder = HOLDER.get();
        if (holder != null) {
            HOLDER.set(new TransactionHolder(holder.transaction(), holder.depth() + 1));
        }
    }

    static boolean decrementAndCheckRelease() {
        TransactionHolder holder = HOLDER.get();
        if (holder == null) return true;

        int newDepth = holder.depth() - 1;
        if (newDepth <= 0) {
            HOLDER.remove();
            return true;
        }
        HOLDER.set(new TransactionHolder(holder.transaction(), newDepth));
        return false;
    }

    static void unbind() {
        HOLDER.remove();
    }

    private record TransactionHolder(Transaction transaction, int depth) {}
}