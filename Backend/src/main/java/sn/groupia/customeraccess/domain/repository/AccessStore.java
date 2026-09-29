package sn.groupia.customeraccess.domain.repository;
import java.time.Instant;
import java.util.*;
import sn.groupia.customeraccess.domain.model.*;
public interface AccessStore {
    void lock(String key);
    Optional<OrderAccessLink> link(UUID id);
    Optional<OrderAccessLink> latestLink(UUID order);
    void insert(OrderAccessLink link);
    void revokeLinks(UUID order,Instant at);
    void touch(UUID id,Instant at);
    Optional<OtpChallenge> challenge(UUID id);
    Optional<OtpChallenge> latestChallenge(String phone);
    void insert(OtpChallenge challenge);
    void supersede(String phone,Instant now);
    void attempt(UUID id);
    void consume(UUID id,Instant now);
    Optional<CustomerSession> session(String hash);
    void insert(CustomerSession session,String hash,Instant now);
    void revokeSession(String hash,Instant now);
    int limit(String hash,Instant now,int seconds);
}
