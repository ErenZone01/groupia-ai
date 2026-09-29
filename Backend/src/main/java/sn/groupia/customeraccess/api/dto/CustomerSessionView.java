package sn.groupia.customeraccess.api.dto;
import java.time.Instant;
public record CustomerSessionView(String scope,String orderNumber,Instant expiresAt) {}
