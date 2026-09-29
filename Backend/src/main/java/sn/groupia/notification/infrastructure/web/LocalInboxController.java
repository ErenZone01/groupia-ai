package sn.groupia.notification.infrastructure.web;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import io.swagger.v3.oas.annotations.Hidden;
import sn.groupia.notification.infrastructure.LocalDeliveryGateway;
import sn.groupia.shared.api.ApiException;
@Hidden @RestController @Profile("customer-access-local & !prod & !production")
class LocalInboxController {
    private final LocalDeliveryGateway inbox;
    LocalInboxController(LocalDeliveryGateway inbox){this.inbox=inbox;}
    @GetMapping("/api/local/customer-access/inbox")
    ResponseEntity<List<LocalDeliveryGateway.Message>> read(HttpServletRequest request) {
        if(!List.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()))
            throw new ApiException(404,"RESOURCE_NOT_FOUND","Ressource introuvable.");
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").body(inbox.messages());
    }
}
