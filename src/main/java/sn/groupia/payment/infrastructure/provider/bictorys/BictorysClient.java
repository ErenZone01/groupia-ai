package sn.groupia.payment.infrastructure.provider.bictorys;
import java.net.http.HttpClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
final class BictorysClient {
    private final RestClient http;
    BictorysClient(RestClient http){this.http=http;}
    static RestClient.Builder builder(BictorysProperties p) {
        var transport=HttpClient.newBuilder().connectTimeout(p.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(transport); factory.setReadTimeout(p.readTimeout());
        return RestClient.builder().baseUrl(p.apiUrl().toString()).requestFactory(factory)
                .defaultHeader("X-Api-Key",p.apiKey()).defaultHeader("Accept","application/json");
    }
    record Response(int status,byte[] body) {
        @Override public String toString(){return "BictorysResponse[status="+status+"]";}
    }
    Response initiate(BictorysChargeRequest request) {
        return execute(http.post().uri("/pay/v1/charges").contentType(MediaType.APPLICATION_JSON).body(request),true);
    }
    Response status(String id){return execute(http.get().uri("/pay/v1/transactions/{id}/status",identifier(id)),false);}
    Response details(String id){return execute(http.get().uri("/pay/v1/transactions/{id}",identifier(id)),false);}
    private String identifier(String id) {
        if(id==null || !id.matches("[A-Za-z0-9_-]{1,160}")){throw new BictorysException("BICTORYS_TRANSACTION_ID_REQUIRED");}
        return id;
    }
    private Response execute(RestClient.RequestHeadersSpec<?> request,boolean creation) {
        try {
            return request.exchange((sent,response)->{
                int status=response.getStatusCode().value();
                if(creation?status!=201 && status!=202:status!=200){throw new BictorysException("BICTORYS_HTTP_"+status);}
                byte[] bytes=response.getBody().readNBytes(65537);
                if(bytes.length>65536){throw new BictorysException("BICTORYS_RESPONSE_TOO_LARGE");}
                return new Response(status,bytes);
            });
        } catch(BictorysException ex){throw ex;}
        catch(RuntimeException ex){throw new BictorysException("BICTORYS_TRANSPORT_UNCERTAIN");}
    }
}
