package io.github.rocketbunny727.swiftmapper.transaction;

import io.github.rocketbunny727.swiftmapper.annotations.transactional.SwiftTransactional;
import io.github.rocketbunny727.swiftmapper.core.ConnectionManager;
import io.github.rocketbunny727.swiftmapper.core.Transaction;
import io.github.rocketbunny727.swiftmapper.exception.TransactionException;
import io.github.rocketbunny727.swiftmapper.utils.logger.SwiftLogger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;
import java.sql.SQLException;

@Aspect
@Order(0)
public class SwiftTransactionalAspect {

    private final ConnectionManager connectionManager;
    private final SwiftLogger log = SwiftLogger.getLogger(SwiftTransactionalAspect.class);

    public SwiftTransactionalAspect(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Around("@annotation(io.github.rocketbunny727.swiftmapper.annotations.transactional.SwiftTransactional)" +
            " || @within(io.github.rocketbunny727.swiftmapper.annotations.transactional.SwiftTransactional)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        SwiftTransactional annotation = resolveAnnotation(pjp);
        if (annotation == null) {
            return pjp.proceed();
        }

        Propagation propagation = annotation.propagation();
        boolean existingTx = SwiftTransactionContext.isActive();

        return switch (propagation) {
            case REQUIRED       -> existingTx ? joinExisting(pjp, annotation)
                    : runInNewTransaction(pjp, annotation);
            case REQUIRES_NEW   -> runInNewTransaction(pjp, annotation);
            case SUPPORTS       -> existingTx ? joinExisting(pjp, annotation)
                    : pjp.proceed();
            case MANDATORY      -> {
                if (!existingTx) throw new TransactionException(
                        "MANDATORY propagation: no active transaction on thread for " +
                                pjp.getSignature().toShortString());
                yield joinExisting(pjp, annotation);
            }
            case NOT_SUPPORTED  -> pjp.proceed();
            case NEVER          -> {
                if (existingTx) throw new TransactionException(
                        "NEVER propagation: transaction is active on thread for " +
                                pjp.getSignature().toShortString());
                yield pjp.proceed();
            }
        };
    }

    private Object runInNewTransaction(ProceedingJoinPoint pjp,
                                       SwiftTransactional annotation) throws Throwable {
        Transaction tx = new Transaction(connectionManager);
        try {
            tx.setIsolationLevel(annotation.isolation());
        } catch (SQLException e) {
            throw new TransactionException("Failed to set isolation level", e);
        }

        if (annotation.readOnly()) {
            return tx.executeReadOnly(conn -> {
                SwiftTransactionContext.bind(tx);
                try {
                    return pjp.proceed();
                } catch (Throwable t) {
                    throw new TransactionException("Method threw exception", t);
                } finally {
                    SwiftTransactionContext.unbind();
                }
            });
        }

        SwiftTransactionContext.bind(tx);
        try {
            tx.begin();
            log.debug("Transaction started for {}", pjp.getSignature().toShortString());

            Object result;
            try {
                result = pjp.proceed();
            } catch (Throwable t) {
                handleException(tx, t, annotation);
                throw t;
            }

            tx.commit();
            log.debug("Transaction committed for {}", pjp.getSignature().toShortString());
            return result;

        } catch (TransactionException te) {
            throw te;
        } catch (Throwable t) {
            throw t;
        } finally {
            SwiftTransactionContext.unbind();
        }
    }

    private Object joinExisting(ProceedingJoinPoint pjp,
                                SwiftTransactional annotation) throws Throwable {
        SwiftTransactionContext.incrementDepth();
        log.debug("Joining existing transaction for {}", pjp.getSignature().toShortString());
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            Transaction tx = SwiftTransactionContext.current();
            if (shouldRollback(t, annotation)) {
                try {
                    tx.rollback();
                    log.debug("Rolled back existing transaction due to {} in {}",
                            t.getClass().getSimpleName(), pjp.getSignature().toShortString());
                } catch (SQLException ex) {
                    log.error("Failed to rollback transaction", ex);
                }
                SwiftTransactionContext.unbind();
            }
            throw t;
        } finally {
            SwiftTransactionContext.decrementAndCheckRelease();
        }
    }

    private void handleException(Transaction tx, Throwable t,
                                 SwiftTransactional annotation) {
        if (!shouldRollback(t, annotation)) {
            log.debug("Exception {} is excluded from rollback, committing", t.getClass().getSimpleName());
            try {
                tx.commit();
            } catch (SQLException ex) {
                log.error("Failed to commit after non-rollback exception", ex);
            }
            return;
        }

        try {
            tx.rollback();
            log.debug("Transaction rolled back due to {}", t.getClass().getSimpleName());
        } catch (SQLException ex) {
            log.error("Failed to rollback transaction", ex);
        }
    }

    private boolean shouldRollback(Throwable t, SwiftTransactional annotation) {
        for (Class<? extends Throwable> noRollback : annotation.noRollbackFor()) {
            if (noRollback.isInstance(t)) {
                return false;
            }
        }

        for (Class<? extends Throwable> rollback : annotation.rollbackFor()) {
            if (rollback.isInstance(t)) {
                return true;
            }
        }

        return t instanceof RuntimeException || t instanceof Error;
    }

    private SwiftTransactional resolveAnnotation(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();

        SwiftTransactional ann = AnnotationUtils.findAnnotation(method, SwiftTransactional.class);
        if (ann != null) return ann;

        return AnnotationUtils.findAnnotation(pjp.getTarget().getClass(), SwiftTransactional.class);
    }
}