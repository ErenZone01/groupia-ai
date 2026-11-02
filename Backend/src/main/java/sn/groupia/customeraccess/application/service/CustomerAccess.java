package sn.groupia.customeraccess.application.service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import sn.groupia.customeraccess.api.dto.*;
import sn.groupia.customeraccess.domain.model.*;
import sn.groupia.customeraccess.domain.repository.AccessStore;
import sn.groupia.identity.api.PhoneNumbers;
import sn.groupia.notification.api.*;
import sn.groupia.notification.api.event.LinkNotificationRequested;
import sn.groupia.ordering.api.CustomerOrderAccess;
import sn.groupia.ordering.api.dto.*;
import sn.groupia.shared.api.ApiException;

@Service
public class CustomerAccess implements NotificationContentSource {
    private final AccessStore store;
    private final AccessCrypto crypto;
    private final AccessPolicy policy;
    private final AccessRateLimits limits;
    private final PhoneNumbers phones;
    private final CustomerOrderAccess orders;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    CustomerAccess(
            AccessStore store,
            AccessCrypto crypto,
            AccessPolicy policy,
            AccessRateLimits limits,
            PhoneNumbers phones,
            CustomerOrderAccess orders,
            Clock clock,
            ApplicationEventPublisher events) {
        this.store = store;
        this.crypto = crypto;
        this.policy = policy;
        this.limits = limits;
        this.phones = phones;
        this.orders = orders;
        this.clock = clock;
        this.events = events;
    }

    public record OtpIssue(OtpRequested response, String phone, String code) {
        @Override
        public String toString() {
            return "OtpIssue[redacted]";
        }
    }

    public record SessionIssued(String token, CustomerSessionView view) {
        @Override
        public String toString() {
            return "SessionIssued[redacted]";
        }
    }

    @Transactional
    public OtpIssue request(String rawPhone, String ip) {
        policy.requireEnabled();
        limits.consume("otp-ip:" + ip, 3600, policy.settings.ipRequestsPerHour());
        String phone = phones.normalize(rawPhone);
        store.lock("phone:" + crypto.subject(phone));
        Instant now = now();
        if (store.latestChallenge(phone).filter(c -> now.isBefore(c.resendAfter())).isPresent())
            throw limited();
        limits.consume("otp-phone:" + phone, 3600, policy.settings.phoneRequestsPerHour());
        UUID id = UUID.randomUUID();
        String code = crypto.otp();
        store.supersede(phone, now);
        store.insert(
                new OtpChallenge(
                        id,
                        phone,
                        crypto.otpHash(id, phone, code),
                        now,
                        now.plusSeconds(policy.settings.otpSeconds()),
                        0,
                        null,
                        null,
                        now.plusSeconds(policy.settings.resendSeconds())));
        return new OtpIssue(
                new OtpRequested(
                        id,
                        "Si ce numéro peut recevoir un code, celui-ci a été envoyé.",
                        policy.settings.otpSeconds(),
                        policy.settings.resendSeconds()),
                phone,
                code);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public SessionIssued verify(UUID challengeId, String code, String oldToken, String ip) {
        policy.requireEnabled();
        limits.consume("verify-ip:" + ip, 900, policy.settings.ipVerificationsPerQuarterHour());
        var challenge = store.challenge(challengeId).orElseThrow(CustomerAccess::invalidOtp);
        if (!challenge.usable(now(), policy.settings.maxOtpAttempts())) throw invalidOtp();
        store.attempt(
                challenge.id()); // Failed guesses commit; simultaneous attempts lock the same row.
        String supplied = code != null && code.matches("[0-9]{6}") ? code : "invalid";
        if (!crypto.equal(
                challenge.codeHash(),
                crypto.otpHash(challenge.id(), challenge.phoneNormalized(), supplied)))
            throw invalidOtp();
        store.consume(challenge.id(), now());
        return issue(AccessScope.PHONE_VERIFIED, null, challenge.phoneNormalized(), null, oldToken);
    }

    @Transactional
    public SessionIssued exchange(String token, String oldToken, String ip) {
        policy.requireEnabled();
        limits.consume("magic-ip:" + ip, 900, policy.settings.ipVerificationsPerQuarterHour());
        UUID id = crypto.verifyLink(token).orElseThrow(CustomerAccess::invalidLink);
        var link = validLink(id);
        var order = orders.identity(link.organizationId(), link.orderId());
        store.touch(id, now());
        return issue(AccessScope.ORDER_ACCESS, id, null, order.orderNumber(), oldToken);
    }

    private SessionIssued issue(
            AccessScope scope, UUID link, String phone, String number, String oldToken) {
        revoke(oldToken);
        String raw = crypto.randomToken();
        Instant now = now();
        var session =
                new CustomerSession(
                        UUID.randomUUID(),
                        scope,
                        link,
                        phone,
                        now.plusSeconds(policy.settings.sessionSeconds()),
                        null);
        store.insert(session, crypto.hash(raw), now);
        return new SessionIssued(
                raw, new CustomerSessionView(scope.name(), number, session.expiresAt()));
    }

    /** Same verified, HttpOnly cookie session used for existing groupage recovery. */
    @Transactional(readOnly = true)
    public String verifiedPhone(String token) {
        return phoneSession(token).phoneNormalized();
    }

    public String verifiedPhoneForDirectRotation(String token, String ip) {
        policy.requireEnabled();
        var phone = phoneSession(token).phoneNormalized();
        limits.consume("direct-rotate-ip:" + ip, 3600, policy.settings.ipRequestsPerHour());
        limits.consume(
                "direct-rotate-phone:" + phone, 3600, policy.settings.phoneRequestsPerHour());
        return phone;
    }

    @Transactional(readOnly = true)
    public CustomerSessionView sessionView(String token) {
        var s = session(token);
        String number = null;
        if (s.linkId() != null) {
            var l = validLink(s.linkId());
            number = orders.identity(l.organizationId(), l.orderId()).orderNumber();
        }
        return new CustomerSessionView(s.scope().name(), number, s.expiresAt());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MyOrdersPage list(String token, int page, int size) {
        var s = phoneSession(token);
        return orders.findOrdersForVerifiedPhone(s.phoneNormalized(), page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OrderTrackingView track(String token, String number) {
        var s = session(token);
        var order = authorized(s, number);
        return orders.track(order.organizationId(), order.id());
    }

    @Transactional(readOnly = true)
    public CustomerOrderIdentity paymentIdentity(String token, String number) {
        return authorized(session(token), number);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CustomerOrderCapabilities capabilities(String token, String number) {
        var s = session(token);
        var order = authorized(s, number);
        return new CustomerOrderCapabilities(
                s.scope() == AccessScope.PHONE_VERIFIED
                        && List.of("CONFIRMED", "COMPLETED").contains(order.status())
                        && !archived(order));
    }

    @Transactional
    public void resend(String token, String number) {
        var s = phoneSession(token);
        var order = orders.forPhone(s.phoneNormalized(), number);
        limits.consume(
                "link-phone:" + s.phoneNormalized(), 3600, policy.settings.phoneRequestsPerHour());
        store.lock("order:" + order.id());
        rotate(order, NotificationType.ORDER_TRACKING_LINK);
    }

    @Transactional
    public void revokeOrderLink(String token, String number) {
        var s = phoneSession(token);
        var order = orders.forPhone(s.phoneNormalized(), number);
        store.lock("order:" + order.id());
        store.revokeLinks(order.id(), now());
    }

    @Transactional
    public void revoke(String token) {
        if (token != null && token.matches("[A-Za-z0-9_-]{43}"))
            store.revokeSession(crypto.hash(token), now());
    }

    @Transactional
    public void confirmed(UUID org, UUID orderId) {
        policy.requireEnabled();
        store.lock("order:" + orderId);
        var order = orders.identity(org, orderId);
        if (!List.of("CONFIRMED", "COMPLETED").contains(order.status())) return;
        if (store.latestLink(orderId).isPresent())
            return; // Redelivery must never undo a rotation or revocation.
        rotate(order, NotificationType.ORDER_CONFIRMED);
    }

    private void rotate(CustomerOrderIdentity order, NotificationType type) {
        if (!List.of("CONFIRMED", "COMPLETED").contains(order.status()))
            throw new ApiException(
                    409,
                    "ORDER_ACCESS_NOT_AVAILABLE",
                    "Le lien sera disponible après confirmation.");
        Instant now = now();
        Instant expiry =
                order.terminalAt() == null
                        ? null
                        : order.terminalAt().plus(Duration.ofDays(policy.settings.archiveDays()));
        if (expiry != null && !now.isBefore(expiry)) throw invalidLink();
        long generation = store.latestLink(order.id()).map(l -> l.generation() + 1).orElse(1L);
        store.revokeLinks(order.id(), now);
        var link =
                new OrderAccessLink(
                        UUID.randomUUID(),
                        order.organizationId(),
                        order.id(),
                        generation,
                        now,
                        null,
                        expiry,
                        null);
        store.insert(link);
        events.publishEvent(new LinkNotificationRequested(UUID.randomUUID(), now, link.id(), type));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LinkContent> link(UUID id) {
        policy.requireEnabled(); // Keep queued delivery retryable if access is temporarily
        // disabled.
        var value = store.link(id);
        if (value.isEmpty() || !value.get().activeAt(now())) return Optional.empty();
        var l = value.get();
        var order = orders.identity(l.organizationId(), l.orderId());
        if (archived(order)) return Optional.empty();
        return Optional.of(
                new LinkContent(
                        order.phoneNormalized(),
                        order.orderNumber(),
                        policy.settings.publicBaseUrl().replaceAll("/$", "")
                                + "/s/"
                                + crypto.link(id)));
    }

    private CustomerSession session(String token) {
        policy.requireEnabled();
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw sessionRequired();
        var s =
                store.session(crypto.hash(token))
                        .filter(value -> value.activeAt(now()))
                        .orElseThrow(CustomerAccess::sessionRequired);
        if (s.linkId() != null) validLink(s.linkId());
        return s;
    }

    private CustomerSession phoneSession(String token) {
        var s = session(token);
        if (s.scope() != AccessScope.PHONE_VERIFIED)
            throw new ApiException(
                    403,
                    "PHONE_VERIFICATION_REQUIRED",
                    "Vérifiez votre numéro pour retrouver toutes vos commandes.");
        return s;
    }

    private CustomerOrderIdentity authorized(CustomerSession s, String number) {
        if (s.scope() == AccessScope.PHONE_VERIFIED)
            return orders.forPhone(s.phoneNormalized(), number);
        var l = validLink(s.linkId());
        var o = orders.identity(l.organizationId(), l.orderId());
        if (!o.orderNumber().equals(number))
            throw new ApiException(404, "CUSTOMER_ORDER_NOT_FOUND", "Commande inaccessible.");
        return o;
    }

    private OrderAccessLink validLink(UUID id) {
        var l =
                store.link(id)
                        .filter(value -> value.activeAt(now()))
                        .orElseThrow(CustomerAccess::invalidLink);
        if (archived(orders.identity(l.organizationId(), l.orderId()))) throw invalidLink();
        return l;
    }

    private boolean archived(CustomerOrderIdentity order) {
        return order.terminalAt() != null
                && !now().isBefore(
                                order.terminalAt()
                                        .plus(Duration.ofDays(policy.settings.archiveDays())));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException limited() {
        return new ApiException(
                429, "CUSTOMER_ACCESS_RATE_LIMITED", "Patientez avant de réessayer.");
    }

    private static ApiException invalidOtp() {
        return new ApiException(400, "OTP_INVALID", "Code invalide ou expiré.");
    }

    private static ApiException invalidLink() {
        return new ApiException(
                401, "ORDER_ACCESS_LINK_INVALID", "Ce lien est invalide ou n’est plus disponible.");
    }

    private static ApiException sessionRequired() {
        return new ApiException(
                401, "CUSTOMER_SESSION_REQUIRED", "Retrouvez vos commandes pour continuer.");
    }
}
