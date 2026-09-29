package sn.groupia.customeraccess.infrastructure.persistence;
import java.time.Instant;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sn.groupia.customeraccess.domain.repository.AccessStore;
import sn.groupia.customeraccess.domain.model.*;
@Repository
class JdbcAccessStore implements AccessStore {
    private final JdbcTemplate jdbc;
    JdbcAccessStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void lock(String key){jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",(r,n)->0,"customer-access:"+key);}
    private OrderAccessLink linkRow(ResultSet r,int n)throws SQLException{return new OrderAccessLink(r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),r.getObject("order_id",UUID.class),r.getLong("generation"),at(r,"created_at"),at(r,"revoked_at"),at(r,"expires_at"),at(r,"last_used_at"));}
    public Optional<OrderAccessLink> link(UUID id){return jdbc.query("select * from order_access_links where id=?",this::linkRow,id).stream().findFirst();}
    public Optional<OrderAccessLink> latestLink(UUID order){return jdbc.query("select * from order_access_links where order_id=? order by generation desc limit 1",this::linkRow,order).stream().findFirst();}
    public void insert(OrderAccessLink l){jdbc.update("insert into order_access_links(id,organization_id,order_id,generation,created_at,expires_at) values(?,?,?,?,?,?)",l.id(),l.organizationId(),l.orderId(),l.generation(),ts(l.createdAt()),ts(l.expiresAt()));}
    public void revokeLinks(UUID order,Instant at){jdbc.update("update order_access_links set revoked_at=? where order_id=? and revoked_at is null",ts(at),order);}
    public void touch(UUID id,Instant at){jdbc.update("update order_access_links set last_used_at=? where id=?",ts(at),id);}
    private OtpChallenge otpRow(ResultSet r,int n)throws SQLException{return new OtpChallenge(r.getObject("id",UUID.class),r.getString("phone_normalized"),r.getString("code_hash"),at(r,"created_at"),at(r,"expires_at"),r.getInt("attempt_count"),at(r,"consumed_at"),at(r,"revoked_at"),at(r,"resend_after"));}
    public Optional<OtpChallenge> challenge(UUID id){return jdbc.query("select * from otp_challenges where id=? for update",this::otpRow,id).stream().findFirst();}
    public Optional<OtpChallenge> latestChallenge(String phone){return jdbc.query("select * from otp_challenges where phone_normalized=? order by created_at desc,id desc limit 1",this::otpRow,phone).stream().findFirst();}
    public void insert(OtpChallenge c){jdbc.update("insert into otp_challenges(id,phone_normalized,purpose,code_hash,created_at,expires_at,resend_after) values(?,?,'RECOVER_ORDERS',?,?,?,?)",c.id(),c.phoneNormalized(),c.codeHash(),ts(c.createdAt()),ts(c.expiresAt()),ts(c.resendAfter()));}
    public void supersede(String phone,Instant now){jdbc.update("update otp_challenges set revoked_at=? where phone_normalized=? and consumed_at is null and revoked_at is null",ts(now),phone);}
    public void attempt(UUID id){jdbc.update("update otp_challenges set attempt_count=attempt_count+1 where id=?",id);}
    public void consume(UUID id,Instant now){jdbc.update("update otp_challenges set consumed_at=? where id=?",ts(now),id);}
    public Optional<CustomerSession> session(String hash){
        return jdbc.query("select * from customer_access_sessions where token_hash=?",(r,n)->new CustomerSession(r.getObject("id",UUID.class),
            AccessScope.valueOf(r.getString("scope")),r.getObject("link_id",UUID.class),r.getString("phone_normalized"),at(r,"expires_at"),at(r,"revoked_at")),hash).stream().findFirst();
    }
    public void insert(CustomerSession s,String hash,Instant now){jdbc.update("insert into customer_access_sessions(id,token_hash,scope,link_id,phone_normalized,created_at,expires_at) values(?,?,?,?,?,?,?)",s.id(),hash,s.scope().name(),s.linkId(),s.phoneNormalized(),ts(now),ts(s.expiresAt()));}
    public void revokeSession(String hash,Instant now){jdbc.update("update customer_access_sessions set revoked_at=? where token_hash=? and revoked_at is null",ts(now),hash);}
    public int limit(String hash,Instant now,int seconds) {
        return jdbc.queryForObject("""
            insert into customer_access_limits(subject_hash,window_start,request_count) values(?,?,1)
            on conflict(subject_hash) do update set
              request_count=case when customer_access_limits.window_start<=? then 1 else least(customer_access_limits.request_count+1,1000000) end,
              window_start=case when customer_access_limits.window_start<=? then excluded.window_start else customer_access_limits.window_start end
            returning request_count
            """,Integer.class,hash,ts(now),ts(now.minusSeconds(seconds)),ts(now.minusSeconds(seconds)));
    }
    private static Timestamp ts(Instant at){return at==null?null:Timestamp.from(at);}
    private static Instant at(ResultSet r,String col)throws SQLException{var value=r.getTimestamp(col);return value==null?null:value.toInstant();}
}
