package sn.groupia.customeraccess.application.service;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import sn.groupia.customeraccess.infrastructure.AccessSettings;
@Component
public class AccessCrypto {
    private final SecureRandom random=new SecureRandom();
    private final byte[] magic,pepper;
    private final boolean enabled;
    public AccessCrypto(AccessSettings settings) {
        enabled=settings.enabled();
        magic=enabled?key(settings.magicKeyBase64()):null;pepper=enabled?key(settings.otpPepperBase64()):null;
        if(enabled && MessageDigest.isEqual(magic,pepper))throw new IllegalStateException("Use distinct magic-link and OTP keys.");
    }
    private byte[] key(String value) {
        try {var key=Base64.getDecoder().decode(value);if(key.length<32)throw new IllegalArgumentException();return key;}
        catch(IllegalArgumentException ex){throw new IllegalStateException("Customer access secrets require at least 256 bits in Base64.");}
    }
    public String randomToken(){var bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    public String otp(){return String.format(Locale.ROOT,"%06d",random.nextInt(1000000));}
    public String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(GeneralSecurityException ex){throw new IllegalStateException("SHA-256 unavailable");}
    }
    public String otpHash(UUID challenge,String phone,String code){return HexFormat.of().formatHex(mac(pepper,"otp-v1:"+challenge+":"+phone+":"+code));}
    public String subject(String value){return HexFormat.of().formatHex(mac(pepper,"limit-v1:"+value));}
    public String link(UUID id){String head="v1."+id;return head+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(magic,"groupia-order-link:"+head));}
    public Optional<UUID> verifyLink(String token) {
        if(token==null || !token.matches("v1\\.[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}"))return Optional.empty();
        try{UUID id=UUID.fromString(token.substring(3,39));return equal(link(id),token)?Optional.of(id):Optional.empty();}
        catch(IllegalArgumentException ex){return Optional.empty();}
    }
    public boolean equal(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));}
    private byte[] mac(byte[] key,String value) {
        if(!enabled)throw new IllegalStateException("Customer access disabled");
        try{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));}
        catch(GeneralSecurityException ex){throw new IllegalStateException("HMAC unavailable");}
    }
}
