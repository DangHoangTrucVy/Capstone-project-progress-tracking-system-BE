package com.capstone.tracking.common;

import com.capstone.tracking.common.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Row locks that also re-read the row.
 * <ul>
 *   <li>Only the entity's own row is locked, with a single-table {@code select ... for update / for share}. Locking
 *       through JPA would also lock the rows of its eager associations (the scope of an enrollment...), and two
 *       transactions holding the same scope shared would then deadlock on it.</li>
 *   <li>The entity is then refreshed: a query hands back an entity already loaded in this transaction without reading
 *       it again, so a check made after the lock could otherwise see a state another transaction has since changed
 *       (an application withdrawn while the Leader accepts it).</li>
 * </ul>
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class Locks {

    private static final Map<Class<?>, String> TABLES = new ConcurrentHashMap<>();

    @PersistenceContext
    private EntityManager em;

    private volatile Boolean sharedLockSupported;

    /** Exclusive lock (FOR UPDATE) on the row, with its current state. */
    public <T> T write(Class<T> type, Object id) {
        return lock(type, id, "for update");
    }

    /** Shared lock (FOR SHARE) on the row, with its current state; an exclusive one where FOR SHARE does not exist (H2). */
    public <T> T read(Class<T> type, Object id) {
        return lock(type, id, sharedLockSupported() ? "for share" : "for update");
    }

    private <T> T lock(Class<T> type, Object id, String clause) {
        String table = TABLES.computeIfAbsent(type, t -> t.getAnnotation(Table.class).name());
        int rows = em.createNativeQuery("select 1 from " + table + " where id = :id " + clause)
                .setParameter("id", id)
                .getResultList().size();
        if (rows == 0) {
            throw ResourceNotFoundException.of(type.getSimpleName(), id);
        }
        T entity = em.find(type, id);
        em.refresh(entity);
        return entity;
    }

    private boolean sharedLockSupported() {
        if (sharedLockSupported == null) {
            String product = em.unwrap(Session.class).doReturningWork(c -> c.getMetaData().getDatabaseProductName());
            sharedLockSupported = product.toLowerCase().contains("postgres");
        }
        return sharedLockSupported;
    }
}
