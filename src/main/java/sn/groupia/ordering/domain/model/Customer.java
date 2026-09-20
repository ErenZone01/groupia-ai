package sn.groupia.ordering.domain.model;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import sn.groupia.ordering.api.dto.CustomerContact;

@Entity @Table(name = "customers")
public class Customer {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID organizationId;
    @Column(nullable = false, length = 100) private String firstName;
    @Column(nullable = false, length = 100) private String lastName;
    @Column(nullable = false, length = 16) private String phoneE164;
    @Column(length = 254) private String email;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    @Version private Long version;
    protected Customer() {}
    public static Customer create(UUID organizationId, CustomerContact contact, Instant now) {
        var customer = new Customer();
        customer.id = UUID.randomUUID(); customer.organizationId = organizationId;
        customer.firstName = contact.firstName(); customer.lastName = contact.lastName();
        customer.phoneE164 = contact.phoneE164(); customer.email = contact.email();
        customer.createdAt = customer.updatedAt = now;
        return customer;
    }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
}
