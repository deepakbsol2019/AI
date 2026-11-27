package com.book.spreadsheet.security;

import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.server.ResponseStatusException;

/** Service credentials are independent of browser sessions; tenant grants are server-side configuration. */
public final class SpreadsheetServiceTokens {
    private final JwtDecoder decoder;
    private final String issuer,audience;
    private final Map<String,Long> clients;
    private final Clock clock;
    public SpreadsheetServiceTokens(JwtDecoder decoder,String issuer,String audience,Map<String,Long> clients,Clock clock){
        this.decoder=decoder;this.issuer=issuer;this.audience=audience;this.clients=Map.copyOf(clients);this.clock=clock;
    }
    public long tenant(String authorization) {
        if(issuer.isBlank()||audience.isBlank()||clients.isEmpty()||decoder==null)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Spreadsheet service authentication is not configured.");
        if(authorization==null||!authorization.startsWith("Bearer ")||authorization.length()>16384||authorization.indexOf(',',7)>=0)throw denied();
        try {
            Jwt jwt=decoder.decode(authorization.substring(7));
            String client=jwt.getClaimAsString("client_id");
            if(client==null)client=jwt.getClaimAsString("azp");
            Long tenant=clients.get(client);
            var now=clock.instant();
            if(tenant==null || !issuer.equals(jwt.getClaimAsString("iss")) || !jwt.getAudience().contains(audience)
                    ||jwt.getExpiresAt()==null||!now.isBefore(jwt.getExpiresAt())
                    ||jwt.getIssuedAt()==null||jwt.getIssuedAt().isAfter(now.plusSeconds(60))
                    ||jwt.getNotBefore()!=null&&now.isBefore(jwt.getNotBefore()))throw denied();
            Object scope=jwt.getClaims().get("scope"),scp=jwt.getClaims().get("scp");
            boolean read=scope instanceof String text&&Arrays.asList(text.split(" ")).contains("spreadsheets.read")
                    ||scp instanceof Collection<?> values&&values.contains("spreadsheets.read");
            if(!read)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Spreadsheet read scope is required.");
            Object claim=jwt.getClaims().get("tid");
            if(claim!=null && !tenant.toString().equals(claim.toString()))throw denied();
            return tenant;
        } catch(ResponseStatusException failure){throw failure;}
        catch(RuntimeException failure){throw denied();}
    }
    private static ResponseStatusException denied(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"A valid spreadsheet service token is required.");}
}
