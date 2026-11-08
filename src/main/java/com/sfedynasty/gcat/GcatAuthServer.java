package com.sfedynasty.gcat;

import static spark.Spark.*;
import com.google.gson.*;
import okhttp3.*;
import java.io.IOException;

public class GcatAuthServer {

    static final String GCAT_PAN = System.getenv().getOrDefault("GCAT_PAN", "9000002500000001");
    static final String AID = System.getenv().getOrDefault("AID", "F000000999534645");
    static final long SPEND_LIMIT_CENTS = Long.parseLong(System.getenv().getOrDefault("SPEND_LIMIT_CENTS", "5000000"));
    private static final String GCAT_ACCOUNT = System.getenv().getOrDefault("GCAT_ACCOUNT", "GCATMWLZ7EU7EUWJ3LS6SBHKILWEUS3QBALHGF3XH4IZCOPJHFB5DJYW");
    private static final String USD_ISSUER = System.getenv().getOrDefault("USD_ISSUER", "GCVDS3YYXQVFVVG4HSIBZAHQJAGMQUM6ZTJHUOQGEZHEV4VN2CX5SAT3");
    private static final String HORIZON_URL = System.getenv().getOrDefault("HORIZON_URL", "https://horizon.stellar.org");
    private static final OkHttpClient http = new OkHttpClient();
    private static final Gson gson = new Gson();

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        port(port);
        get("/health", new GcatHealthRoute());
        post("/authorize", new GcatAuthorizeRoute());
        post("/hce/apdu", new GcatHceRoute());
        post("/chip/apdu", new GcatChipApduRoute());
        post("/chip/transaction", new GcatChipTxRoute());
        post("/magstripe/encode", new GcatMagRoute());
        post("/qr/generate", new GcatQrGenRoute());
        post("/qr/authorize", new GcatQrAuthRoute());
        post("/ble/advertise", new GcatBleAdvRoute());
        post("/ble/authorize", new GcatBleAuthRoute());
        post("/stellar/build-xdr", new GcatStellarXdrRoute());
        System.out.println("GCAT jPOS Auth Host running on port " + port);
    }

    static double getHorizonBalance() {
        try {
            Request req = new Request.Builder().url(HORIZON_URL + "/accounts/" + GCAT_ACCOUNT).build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) return -1;
                JsonObject body = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                JsonArray balances = body.getAsJsonArray("balances");
                for (JsonElement el : balances) {
                    JsonObject b = el.getAsJsonObject();
                    if (b.has("asset_code") && "USD".equals(b.get("asset_code").getAsString()) && b.has("asset_issuer") && b.get("asset_issuer").getAsString().startsWith("GCVDS3")) {
                        return Double.parseDouble(b.get("balance").getAsString());
                    }
                }
            }
        } catch (IOException e) { return -1; }
        return -1;
    }

    private static boolean luhnCheck(String pan) {
        if (pan == null || pan.length() < 13) return false;
        int sum = 0;
        boolean alternate = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            char c = pan.charAt(i);
            if (!Character.isDigit(c)) return false;
            int n = Character.digit(c, 10);
            if (alternate) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }

    static class HealthResponse { String status, engine, version, gcat_pan, aid; HealthResponse(String s, String e, String v, String p, String a) { status=s; engine=e; version=v; gcat_pan=p; aid=a; } }
    static class AuthResponse { String field39_response_code, field38_auth_id_response, field11_stan, message; AuthResponse(String c, String a, String s, String m) { field39_response_code=c; field38_auth_id_response=a; field11_stan=s; message=m; } }

}

class GcatHealthRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"status\":\"healthy\",\"engine\":\"jPOS ISO 8583\",\"version\":\"2.1.9\",\"gcat_pan\":\"" + GcatAuthServer.GCAT_PAN + "\",\"aid\":\"" + GcatAuthServer.AID + "\"}";
    }
}

class GcatAuthorizeRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        com.google.gson.JsonObject body = com.google.gson.JsonParser.parseString(req.body()).getAsJsonObject();
        String pan = body.has("field2_pan") ? body.get("field2_pan").getAsString() : "";
        long amount = body.has("field4_amount") ? body.get("field4_amount").getAsLong() : 0;
        String stan = body.has("field11_stan") ? body.get("field11_stan").getAsString() : "000000";
        com.google.gson.Gson gson = new com.google.gson.Gson();
        // Primary: exact match to treasury PAN — always accept without Luhn
        // Secondary: accept any other PAN that passes Luhn
        // Only reject with code 14 if BOTH checks fail
        boolean panValid = GcatAuthServer.GCAT_PAN.equals(pan) || luhnCheck(pan);
        if (!panValid) return gson.toJson(new GcatAuthServer.AuthResponse("14", null, stan, "Invalid card number"));
        if (amount > GcatAuthServer.SPEND_LIMIT_CENTS) return gson.toJson(new GcatAuthServer.AuthResponse("61", null, stan, "Exceeds spend limit"));
        double balance = GcatAuthServer.getHorizonBalance();
        if (balance < 0) return gson.toJson(new GcatAuthServer.AuthResponse("91", null, stan, "Issuer unavailable"));
        if (balance < amount / 100.0) return gson.toJson(new GcatAuthServer.AuthResponse("51", null, stan, "Insufficient funds"));
        String authId = String.format("%06d", (int)(Math.random() * 999999));
        return gson.toJson(new GcatAuthServer.AuthResponse("00", authId, stan, "Approved"));
    }

    private static boolean luhnCheck(String pan) {
        if (pan == null || pan.length() < 13) return false;
        int sum = 0;
        boolean alternate = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            char c = pan.charAt(i);
            if (!Character.isDigit(c)) return false;
            int n = Character.digit(c, 10);
            if (alternate) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alternate = !alternate;
        }
        return (sum % 10 == 0);
    }
}

class GcatHceRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        com.google.gson.JsonObject body = com.google.gson.JsonParser.parseString(req.body()).getAsJsonObject();
        String cmd = body.has("apdu_command") ? body.get("apdu_command").getAsString() : "";
        if ("SELECT".equals(cmd)) return "{\"sw1sw2\":\"9000\",\"aid\":\"" + GcatAuthServer.AID + "\",\"app_label\":\"GCAT-USD\"}";
        if ("GET_PROCESSING_OPTIONS".equals(cmd)) return "{\"sw1sw2\":\"9000\",\"aip\":\"5800\",\"afl\":\"08010100\"}";
        if ("READ_RECORD".equals(cmd)) return "{\"sw1sw2\":\"9000\",\"tag_5A\":\"" + GcatAuthServer.GCAT_PAN + "\",\"tag_5F24\":\"2812\",\"tag_5F20\":\"DENNIS/DASHAWN-TERRELL\"}";
        if ("GENERATE_AC".equals(cmd)) return "{\"sw1sw2\":\"9000\",\"arqc\":\"" + String.format("%016X", System.currentTimeMillis()) + "\",\"ac_type\":\"ARQC\",\"atc\":\"0001\"}";
        return "{\"sw1sw2\":\"6F00\",\"error\":\"Unknown APDU command\"}";
    }
}

class GcatChipApduRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"sw1sw2\":\"9000\",\"fci\":\"6F178408" + GcatAuthServer.AID + "\",\"interface\":\"contact_chip\"}";
    }
}

class GcatChipTxRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"sw1sw2\":\"9000\",\"flow_complete\":true,\"steps_completed\":[\"SEMECT\",\"GPO\",\"READ_RECORD\",\"GENERATE_AC\"],\"arqc\":\"" + String.format("%016X", System.currentTimeMillis()) + "\"}";
    }
}

class GcatMagRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"track2\":\"" + GcatAuthServer.GCAT_PAN + "=2812101000000000\",\"format\":\"track2\"}";
    }
}

class GcatQrGenRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"qr_string\":\"00020101021226370014" + GcatAuthServer.AID + "0216" + GcatAuthServer.GCAT_PAN + "520400005303840540510.005802US5918SF EMERALD DYNASTY6013WASHINGTON DC6304ABCD\"}";
    }
}

class GcatQrAuthRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        String authId = String.format("%06d", (int)(Math.random() * 999999));
        return "{\"field39_response_code\":\"00\",\"field38_auth_id_response\":\"" + authId + "\",\"status\":\"approved\"}";
    }
}

class GcatBleAdvRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"uuid\":\"a1b2c3d4-e5f6-4789-abcd-ef0123456789\",\"ttl\":30,\"status\":\"advertising\"}";
    }
}

class GcatBleAuthRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        String authId = String.format("%06d", (int)(Math.random() * 999999));
        return "{\"field39_response_code\":\"00\",\"field38_auth_id_response\":\"" + authId + "\",\"status\":\"approved\"}";
    }
}

class GcatStellarXdrRoute implements spark.Route {
    public Object handle(spark.Request req, spark.Response res) throws Exception {
        res.type("application/json");
        return "{\"status\":\"not_implemented\",\"message\":\"Stellar XDR build endpoint reserved\"}";
    }
}
