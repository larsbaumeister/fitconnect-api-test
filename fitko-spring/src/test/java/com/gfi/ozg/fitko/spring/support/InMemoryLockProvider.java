package com.gfi.ozg.fitko.spring.support;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-JVM {@link LockProvider} for tests - ShedLock ships no official
 * in-memory provider. A lock name can be held by at most one caller at a
 * time; {@code lockAtLeastFor}/{@code lockAtMostFor} are ignored (tests drive
 * timing explicitly).
 */
public class InMemoryLockProvider implements LockProvider {

    private final Set<String> held = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
        String name = lockConfiguration.getName();
        if (!held.add(name)) {
            return Optional.empty();
        }
        return Optional.of(() -> held.remove(name));
    }

    public boolean isHeld(String name) {
        return held.contains(name);
    }
}
