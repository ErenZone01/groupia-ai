package sn.groupia.identity.domain.repository;

import java.time.Duration;

public interface AuthLimitBuckets {
    boolean consume(String scope, String keyHash, int maximum, Duration window, int capacity);

    int cleanup();
}
