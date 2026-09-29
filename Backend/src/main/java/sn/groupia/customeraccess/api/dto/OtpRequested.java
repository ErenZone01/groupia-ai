package sn.groupia.customeraccess.api.dto;
import java.util.UUID;
public record OtpRequested(UUID challengeId,String message,int expiresInSeconds,int resendAfterSeconds) {}
