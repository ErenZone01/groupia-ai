package sn.groupia.ordering.infrastructure.persistence;
import java.util.*;
import java.sql.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.groupia.ordering.application.query.CustomerAccessReadModel;
import sn.groupia.ordering.api.dto.CustomerOrderIdentity;
import sn.groupia.shared.api.ApiException;
@Repository
class JdbcCustomerAccessOrders implements CustomerAccessReadModel {
    private final JdbcTemplate jdbc;
    JdbcCustomerAccessOrders(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String FROM=" from orders o join customers c on c.id=o.customer_id and c.organization_id=o.organization_id ";
    private static final String IDENTITY="select o.id,o.organization_id,o.order_number,c.phone_e164,o.status,coalesce(o.picked_up_at,o.completed_at,o.cancelled_at,case when o.status='EXPIRED' then o.payment_deadline end) terminal_at";
    public CustomerOrderIdentity identity(UUID org,UUID id){return one("o.organization_id=? and o.id=?",org,id);}
    public CustomerOrderIdentity forPhone(String phone,String number){return one("c.phone_e164=? and o.order_number=?",phone,number);}
    private CustomerOrderIdentity one(String where,Object...args){
        return jdbc.query(IDENTITY+FROM+"where "+where,(r,n)->new CustomerOrderIdentity(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getString(3),r.getString(4),r.getString(5),r.getTimestamp(6)==null?null:r.getTimestamp(6).toInstant()),args)
            .stream().findFirst().orElseThrow(()->new ApiException(404,"CUSTOMER_ORDER_NOT_FOUND","Commande inaccessible."));
    }
    public long count(String phone){return jdbc.queryForObject("select count(*)"+FROM+"where c.phone_e164=?",Long.class,phone);}
    public List<Row> list(String phone,int offset,int limit){
        return jdbc.query("select o.campaign_id,o.order_number,o.placed_at,o.status,o.fulfillment_status,o.initial_amount_due,o.currency"+FROM+
            "where c.phone_e164=? order by o.placed_at desc,o.id limit ? offset ?",(r,n)->new Row(r.getObject(1,UUID.class),
            r.getString(2),r.getTimestamp(3).toInstant(),r.getString(4),r.getString(5),r.getBigDecimal(6),r.getString(7)),phone,limit,offset);
    }
}
