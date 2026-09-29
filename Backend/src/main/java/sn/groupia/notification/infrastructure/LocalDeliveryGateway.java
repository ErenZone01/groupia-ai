package sn.groupia.notification.infrastructure;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import sn.groupia.notification.api.*;
/** Explicit local-only, bounded transient inbox. No secrets in logs or persistent events. */
@Component @Profile("customer-access-local & !prod & !production")
public class LocalDeliveryGateway implements DeliveryGateway {
    private final Map<UUID,Message> inbox=new LinkedHashMap<>();
    public record Message(UUID id,String type,String phone,String content,Instant createdAt) {
        @Override public String toString(){return "LocalMessage[redacted]";}
    }
    public synchronized void sendOtp(UUID id,String phone,String code){put(new Message(id,"OTP",phone,code,Instant.now()));}
    public synchronized void sendLink(UUID id,NotificationType type,String phone,String url){put(new Message(id,type.name(),phone,url,Instant.now()));}
    private void put(Message message){
        inbox.entrySet().removeIf(e->e.getValue().createdAt().isBefore(Instant.now().minusSeconds(900)));
        if(inbox.size()>=100)inbox.remove(inbox.keySet().iterator().next());
        inbox.putIfAbsent(message.id(),message);
    }
    public synchronized List<Message> messages(){inbox.entrySet().removeIf(e->e.getValue().createdAt().isBefore(Instant.now().minusSeconds(900)));return List.copyOf(inbox.values());}
}
