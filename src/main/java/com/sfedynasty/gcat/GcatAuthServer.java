package com.sfedynasty.gcat;

import static spark.Spark.*;
import com.google.gson.*;
import okhttp3.*;
import java.io.IOException;

public class GcatAuthServer {

    // FIX 1 & 2: Updated PAN and AID to match the Android App and XML
    static final String GCAT_PAN        = System.getenv().getOrDefault("GCAT_PAN",          "9000002500000001");
    static final String AID             = System.getenv().getOrDefault("GCAT_AID",          "F000000999534645");
    static final long   SPEND_LIMIT     = Long.parseLong(System.getenv().getOrDefault("GCAT_SPEND_LIMIT", "5000000"));
    static final double BALANCE_USD     = Double.parseDouble(System.getenv().getOrDefault("GCAT_BALANCE_USD", "250000.00"));
    
    // The URL for your Python Clearing Engine
    static final String CLEARING_ENGINE = "https://fiduciary-clearing-engine.onrender.com/stellar";
    static final OkHttpClient http = new OkHttpClient();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        port(port);
        
        get( "/health",           (q,r) -> { r.type("application/json"); return health(); });
        post("/authorize",        (q,r) -> { r.type("application/json"); return authorize(q.body()); });
        post("/hce/apdu",         (q,r) -> { r.type("application/json"); return hceApdu(q.body()); });
        post("/chip/apdu",        (q,r) -> { r.type("application/json"); return "{\"sw1sw2\":\"9000\",\"interface\":\"contact_chip\"}"; });
        post("/chip/transaction", (q,r) -> { r.type("application/json"); return "{\"sw1sw2\":\"9000\",\"flow_complete\":true}"; });
        post("/magstripe/encode", (q,r) -> { r.type("application/json"); return "{\"track2\":\"" + GCAT_PAN + "=2812101000000000\"}"; });
        post("/qr/generate",      (q,r) -> { r.type("application/json"); return "{\"qr_pan\":\"" + GCAT_PAN + "\",\"aid\":\"" + AID + "\"}"; });
        post("/qr/authorize",     (q,r) -> { r.type("application/json"); return "{\"field39_response_code\":\"00\",\"status\":\"approved\"}"; });
        post("/ble/advertise",    (q,r) -> { r.type("application/json"); return "{\"uuid\":\"a1b2c3d4-e5f6-4789-abcd-ef0123456789\",\"status\":\"advertising\"}"; });
        post("/ble/authorize",    (q,r) -> { r.type("application/json"); return "{\"field39_response_code\":\"00\",\"status\":\"approved\"}"; });
        post("/stellar/build-xdr",(q,r) -> { r.type("application/json"); return "{\"status\":\"reserved\"}"; });
        
        System.out.println("GCAT jPOS v2.1.9 live on port " + port + " | PAN=" + GCAT_PAN + " | AID=" + AID);
    }

    static String health() {
        return "{\"status\":\"healthy\",\"engine\":\"jPOS ISO 8583\",\"version\":\"2.1.9\"," +
               "\"gcat_pan\":\"" + GCAT_PAN + "\",\"aid\":\"" + AID + "\"," +
               "\"treasury_balance_usd\":" + BALANCE_USD + ",\"spend_limit_cents\":" + SPEND_LIMIT + "}";
    }

    static String authorize(String reqBody) {
        try {
            JsonObject body = JsonParser.parseString(reqBody).getAsJsonObject();
            String pan   = body.has("field2_pan")    ? body.get("field2_pan").getAsString().trim() : "";
            long   amt   = body.has("field4_amount") ? body.get("field4_amount").getAsLong()       : 0L;
            String stan  = body.has("field11_stan")  ? body.get("field11_stan").getAsString()      : "000000";

            // PAN check: exact match first, then Luhn
            if (!GCAT_PAN.equals(pan) && !luhn(pan))
                return resp("14", null, stan, "Invalid card number");

            // Spend limit
            if (amt > SPEND_LIMIT)
                return resp("61", null, stan, "Exceeds spend limit");

            // Balance check
            if (amt / 100.0 > BALANCE_USD)
                return resp("51", null, stan, "Insufficient funds");

            // Approved
            String authId = String.format("%06d", (int)(Math.random() * 999999));
            
            // FIX 3: Fire the settlement to the Python Clearing Engine
            fireSettlement(amt, stan);

            return resp("00", authId, stan, "Approved");

        } catch (Exception e) {
            return resp("30", null, "000000", "Format error: " + e.getMessage());
        }
    }

    // This connects the Java host to the Python Engine!
    static void fireSettlement(long amountCents, String stan) {
        new Thread(() -> {
            try {
                String amountDollars = String.format("%.2f", amountCents / 100.0);
                String jsonBody = "{\"amount\": \"" + amountDollars + "\", \"currency\": \"USD\"}";
                RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
                Request request = new Request.Builder().url(CLEARING_ENGINE).post(body).build();
                http.newCall(request).execute().close();
                System.out.println("Settlement fired to TRLQ for $" + amountDollars + " | STAN: " + stan);
            } catch (IOException e) {
                System.err.println("Settlement error: " + e.getMessage());
            }
        }).start();
    }

    static String hceApdu(String reqBody) {
        try {
            JsonObject body = JsonParser.parseString(reqBody).getAsJsonObject();
            String cmd = body.has("apdu_command") ? body.get("apdu_command").getAsString() : "";
            
            if ("SELECT_AID".equals(cmd) || "SELECT".equals(cmd))
                return "{\"sw1sw2\":\"9000\",\"aid\":\"" + AID + "\",\"app_label\":\"GCAT-USD\"}";
            if ("GET_PROCESSING_OPTIONS".equals(cmd))
                return "{\"sw1sw2\":\"9000\",\"aip\":\"5800\",\"afl\":\"08010100\"}";
            if ("READ_RECORD".equals(cmd))
                return "{\"sw1sw2\":\"9000\",\"tag_5A\":\"" + GCAT_PAN + "\",\"tag_5F24\":\"2812\",\"tag_5F20\":\"DENNIS/DASHAWN-TERRELL\"}";
            if ("GENERATE_AC".equals(cmd))
                return "{\"sw1sw2\":\"9000\",\"arqc\":\"" + String.format("%016X", System.currentTimeMillis()) + "\",\"atc\":\"0001\"}";
                
            return "{\"sw1sw2\":\"6F00\",\"error\":\"unknown\"}";
        } catch (Exception e) {
            return "{\"sw1sw2\":\"6F00\",\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    static String resp(String code, String authId, String stan, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("field39_response_code", code);
        if (authId != null) o.addProperty("field38_auth_id_response", authId);
        o.addProperty("field11_stan", stan);
        o.addProperty("message", msg);
        return o.toString();
    }

    static boolean luhn(String pan) {
        if (pan == null || pan.length() < 13) return false;
        int sum = 0; boolean alt = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            char c = pan.charAt(i);
            if (!Character.isDigit(c)) return false;
            int n = c - '0';
            if (alt) { n *= 2; if (n > 9) n -= 9; }
            sum += n; alt = !alt;
        }
        return sum % 10 == 0;
    }
}
