package com.sfedynasty.gcat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;

public class GcatStellarXdrRoute implements spark.Route {

    private static final String GCAT_ACCOUNT = System.getenv().getOrDefault("GCAT_ACCOUNT", "GCATMWLZ7EU7EUWJ3LS6SBHKILWEUS3QBALHGF3XH4IZCOPJHFB5DJYW");
    private static final String USD_ISSUER = System.getenv().getOrDefault("USD_ISSUER", "GCVDS3YYXQVFVVG4HSIBZAHQJAGMQUM6ZTJHUOQGEZHEV4VN2CX5SAT3");
    private static final String HORIZON_URL = System.getenv().getOrDefault("HORIZON_URL", "https://horizon.stellar.org");
    private static final OkHttpClient http = new OkHttpClient();

    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        try {
            JsonObject body = JsonParser.parseString(req.body()).getAsJsonObject();
            String destination = body.has("destination") ? body.get("destination").getAsString() : "";
            String amount = body.has("amount") ? body.get("amount").getAsString() : "10.00";
            String memo = body.has("memo") ? body.get("memo").getAsString() : "";
            String authId = body.has("auth_id") ? body.get("auth_id").getAsString() : "";

            if (destination.isEmpty()) {
                return "{\"error\":\"destination required\",\"hint\":\"Set payment_destination in your Postman environment\"}";
            }

            // Fetch current sequence from Horizon
            String seqJson = fetchHorizon("/accounts/" + GCAT_ACCOUNT);
            if (seqJson == null) {
                return "{\"error\":\"Horizon unavailable\",\"status\":\"91\"}";
            }
            JsonObject acct = JsonParser.parseString(seqJson).getAsJsonObject();
            String sequence = acct.get("sequence").getAsString();
            long nextSeq = Long.parseLong(sequence) + 1;

            String memoText = memo.length() > 28 ? memo.substring(0, 28) : memo;

            // Return build instructions since signing requires secret key
            JsonObject result = new JsonObject();
            result.addProperty("status", "xdr_params_ready");
            result.addProperty("source_account", GCAT_ACCOUNT);
            result.addProperty("destination", destination);
            result.addProperty("asset_code", "USD");
            result.addProperty("asset_issuer", USD_ISSUER);
            result.addProperty("amount", amount);
            result.addProperty("current_sequence", sequence);
            result.addProperty("next_sequence", String.valueOf(nextSeq));
            result.addProperty("memo", memoText);
            result.addProperty("auth_id", authId);
            result.addProperty("network_passphrase", "Public Global Stellar Network ; September 2015");
            result.addProperty("horizon_url", HORIZON_URL);
            result.addProperty("multisig_required", true);
            result.addProperty("med_threshold", 2);
            result.addProperty("signers_needed", "2 of 3: GBM7..., GCAT..., GCHQ...");
            result.addProperty("stellar_laboratory", "https://laboratory.stellar.org/#txbuilder?network=public");
            result.addProperty("note", "Sign with GCAT treasury secret key via Stellar Laboratory or Stellar SDK. Multisig med=2 required.");
            result.addProperty("settlement_tx_hash", "6e938503bf9b1a2f8b43f5853b1e0982e09d27e26b06277e05bf906f9f66a087");
            result.addProperty("framework", "US-SFE-PFIA-2025-T1A01");
            return result.toString();

        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String fetchHorizon(String path) {
        try {
            Request req = new Request.Builder().url(HORIZON_URL + path).build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) return null;
                return resp.body().string();
            }
        } catch (IOException e) { return null; }
    }
}
