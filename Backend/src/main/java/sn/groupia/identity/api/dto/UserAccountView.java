package sn.groupia.identity.api.dto;
import java.time.Instant;
import java.util.UUID;
public record UserAccountView(UUID id, String phoneNumber, String email, String status, Instant createdAt) {}
